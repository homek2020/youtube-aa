package dev.youtubeaa.media

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.*
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.youtubeaa.MainActivity
import dev.youtubeaa.api.InnertubeClient
import dev.youtubeaa.api.StreamExtractor
import dev.youtubeaa.api.VideoItem
import dev.youtubeaa.auth.AuthManager
import kotlinx.coroutines.*

class MusicService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var player: ExoPlayer
    private lateinit var authManager: AuthManager
    private lateinit var innertube: InnertubeClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared cache: mediaId → VideoItem, populated as user browses
    private val videoCache = mutableMapOf<String, VideoItem>()

    // Current playback queue (the list the user was browsing when they tapped play)
    private var queue: List<VideoItem> = emptyList()
    private var currentIndex: Int = -1

    companion object {
        const val ROOT_ID = "ROOT"
        const val HOME_ID = "HOME"
        const val SUBS_ID = "SUBSCRIPTIONS"
    }

    override fun onCreate() {
        super.onCreate()

        authManager = AuthManager(applicationContext)
        innertube = InnertubeClient { authManager.getValidAccessToken() }

        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        session = MediaSessionCompat(this, "YouTubeAA").apply {
            setSessionActivity(activityIntent)
            setCallback(sessionCallback)
            isActive = true
        }
        sessionToken = session.sessionToken

        player = ExoPlayer.Builder(this).build().apply {
            addListener(playerListener)
        }
    }

    // ---- Browse tree ----

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(BrowserRoot.EXTRA_FLAG_SEARCH_SUPPORTED, true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaItem>>) {
        result.detach()
        scope.launch {
            val items = when (parentId) {
                ROOT_ID -> buildRootMenu()
                HOME_ID -> videosToMediaItems(innertube.getHomeFeed())
                SUBS_ID -> videosToMediaItems(innertube.getSubscriptionsFeed())
                else -> emptyList()
            }
            result.sendResult(items)
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaItem>>) {
        result.detach()
        scope.launch {
            result.sendResult(videosToMediaItems(innertube.search(query)))
        }
    }

    private fun buildRootMenu() = listOf(
        folder(HOME_ID,  "Главная",   "Рекомендации"),
        folder(SUBS_ID, "Подписки",  "Новые видео от каналов"),
    )

    private fun folder(id: String, title: String, subtitle: String) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaItem.FLAG_BROWSABLE
    )

    /** Converts a list of VideoItems to MediaItems and caches them. */
    private fun videosToMediaItems(videos: List<VideoItem>): List<MediaItem> {
        videos.forEach { videoCache["video:${it.videoId}"] = it }
        return videos.map { v ->
            MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("video:${v.videoId}")
                    .setTitle(v.title)
                    .setSubtitle(v.channel)
                    .setDescription(v.duration)
                    .setIconUri(Uri.parse(v.thumbnailUrl))
                    .build(),
                MediaItem.FLAG_PLAYABLE
            )
        }
    }

    // ---- Playback ----

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId?.takeIf { it.startsWith("video:") } ?: return
            val videoId = mediaId.removePrefix("video:")
            val video = videoCache[mediaId]

            // Update the active queue to the section the user was browsing.
            // onLoadChildren sets videoCache in bulk, so we can recover the sibling list.
            val sectionVideos = videoCache.values
                .filter { "video:${it.videoId}" in videoCache }
                .toList()
            if (sectionVideos.isNotEmpty()) {
                setQueue(sectionVideos)
                currentIndex = sectionVideos.indexOfFirst { it.videoId == videoId }
            }

            // Show title/thumbnail immediately — before stream is ready
            video?.let { pushMetadata(it) }
            setState(STATE_BUFFERING)

            scope.launch {
                val url = StreamExtractor.getBestAudioUrl(videoId)
                if (url == null) { setState(STATE_ERROR); return@launch }

                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }
        }

        override fun onPlay()           = player.play()
        override fun onPause()          = player.pause()
        override fun onStop()           { player.stop(); setState(STATE_STOPPED) }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)

        override fun onSkipToNext() {
            val next = currentIndex + 1
            if (next < queue.size) playIndex(next)
        }

        override fun onSkipToPrevious() {
            if (player.currentPosition > 3_000) {
                player.seekTo(0)
            } else {
                val prev = currentIndex - 1
                if (prev >= 0) playIndex(prev)
            }
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            player.shuffleModeEnabled = shuffleMode != SHUFFLE_MODE_NONE
        }
    }

    private fun playIndex(index: Int) {
        currentIndex = index
        val video = queue[index]
        pushMetadata(video)
        setState(STATE_BUFFERING)
        scope.launch {
            val url = StreamExtractor.getBestAudioUrl(video.videoId)
            if (url == null) { setState(STATE_ERROR); return@launch }
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            player.prepare()
            player.play()
        }
    }

    private fun setQueue(videos: List<VideoItem>) {
        queue = videos
        session.setQueue(videos.mapIndexed { i, v ->
            MediaSessionCompat.QueueItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("video:${v.videoId}")
                    .setTitle(v.title)
                    .setSubtitle(v.channel)
                    .setIconUri(Uri.parse(v.thumbnailUrl))
                    .build(),
                i.toLong()
            )
        })
    }

    private fun pushMetadata(video: VideoItem) {
        session.setMetadata(
            Builder()
                .putString(METADATA_KEY_MEDIA_ID,      "video:${video.videoId}")
                .putString(METADATA_KEY_TITLE,         video.title)
                .putString(METADATA_KEY_ARTIST,        video.channel)
                .putString(METADATA_KEY_ALBUM_ART_URI, video.thumbnailUrl)
                .build()
        )
    }

    // ---- Player listener ----

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            setState(if (isPlaying) STATE_PLAYING else STATE_PAUSED)
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) setState(STATE_STOPPED)
        }
    }

    private fun setState(state: Int) {
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, player.currentPosition, 1f)
                .setActions(
                    ACTION_PLAY or ACTION_PAUSE or ACTION_PLAY_FROM_MEDIA_ID or
                    ACTION_PLAY_FROM_SEARCH or ACTION_SKIP_TO_NEXT or
                    ACTION_SKIP_TO_PREVIOUS or ACTION_SEEK_TO or
                    ACTION_SET_SHUFFLE_MODE
                )
                .build()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        session.release()
        player.release()
    }
}
