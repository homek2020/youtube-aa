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

    // Per-section video lists for browsing + skip queue
    private val feedCache = mutableMapOf<String, MutableList<VideoItem>>()
    // Continuation token per section; null = no more pages
    private val continuations = mutableMapOf<String, String?>()
    // Flat lookup for metadata when playback starts
    private val videoCache = mutableMapOf<String, VideoItem>()

    // Active playback queue
    private var queue: List<VideoItem> = emptyList()
    private var currentIndex: Int = -1

    companion object {
        const val ROOT_ID = "ROOT"
        const val HOME_ID = "HOME"
        const val SUBS_ID = "SUBSCRIPTIONS"
        const val LOAD_MORE_PREFIX = "loadmore:"
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

        player = ExoPlayer.Builder(this).build().apply { addListener(playerListener) }
    }

    // ---- Browse tree ----

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(BrowserRoot.EXTRA_FLAG_SEARCH_SUPPORTED, true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<List<MediaItem>>) {
        if (parentId == ROOT_ID) {
            result.sendResult(buildRootMenu())
            return
        }

        result.detach()
        scope.launch {
            // Return cached data immediately if available
            if (feedCache.containsKey(parentId)) {
                result.sendResult(buildPageItems(parentId))
                return@launch
            }

            // First load
            val page = when (parentId) {
                HOME_ID -> innertube.getHomeFeed()
                SUBS_ID -> innertube.getSubscriptionsFeed()
                else -> { result.sendResult(emptyList()); return@launch }
            }

            feedCache[parentId] = page.items.toMutableList()
            continuations[parentId] = page.continuation
            page.items.forEach { videoCache["video:${it.videoId}"] = it }

            result.sendResult(buildPageItems(parentId))
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<List<MediaItem>>) {
        result.detach()
        scope.launch {
            val page = innertube.search(query)
            page.items.forEach { videoCache["video:${it.videoId}"] = it }
            // Store under a search key so "Load more" works for search too
            val key = "search:$query"
            feedCache[key] = page.items.toMutableList()
            continuations[key] = page.continuation
            result.sendResult(buildPageItems(key))
        }
    }

    /** Builds MediaItem list for a section, appending "Load more" when continuation exists. */
    private fun buildPageItems(parentId: String): List<MediaItem> {
        val videos = feedCache[parentId] ?: return emptyList()
        val items = videos.map { v ->
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
        }.toMutableList()

        if (continuations[parentId] != null) {
            items += MediaItem(
                MediaDescriptionCompat.Builder()
                    .setMediaId("$LOAD_MORE_PREFIX$parentId")
                    .setTitle("Загрузить ещё")
                    .setSubtitle("${videos.size} видео загружено")
                    .build(),
                MediaItem.FLAG_PLAYABLE
            )
        }

        return items
    }

    private fun buildRootMenu() = listOf(
        folder(HOME_ID, "Главная", "Рекомендации"),
        folder(SUBS_ID, "Подписки", "Новые видео от каналов"),
    )

    private fun folder(id: String, title: String, subtitle: String) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaItem.FLAG_BROWSABLE
    )

    // ---- Playback callbacks ----

    private val sessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return

            // "Load more" tapped — fetch next page and refresh the list
            if (mediaId.startsWith(LOAD_MORE_PREFIX)) {
                val parentId = mediaId.removePrefix(LOAD_MORE_PREFIX)
                val token = continuations[parentId] ?: return
                scope.launch {
                    val page = when {
                        parentId == HOME_ID -> innertube.getHomeFeed(token)
                        parentId == SUBS_ID -> innertube.getSubscriptionsFeed(token)
                        parentId.startsWith("search:") -> innertube.search(
                            parentId.removePrefix("search:"), token
                        )
                        else -> return@launch
                    }
                    feedCache[parentId]?.addAll(page.items)
                    continuations[parentId] = page.continuation
                    page.items.forEach { videoCache["video:${it.videoId}"] = it }
                    notifyChildrenChanged(parentId)
                }
                return
            }

            if (!mediaId.startsWith("video:")) return
            val videoId = mediaId.removePrefix("video:")
            val video = videoCache[mediaId]

            // Set queue to the section that contains this video
            val sectionKey = feedCache.entries
                .firstOrNull { (_, list) -> list.any { it.videoId == videoId } }
                ?.key
            val sectionVideos = sectionKey?.let { feedCache[it] } ?: emptyList()

            if (sectionVideos.isNotEmpty()) {
                queue = sectionVideos
                currentIndex = sectionVideos.indexOfFirst { it.videoId == videoId }
                updateSessionQueue()
            }

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

        override fun onPlay()            = player.play()
        override fun onPause()           = player.pause()
        override fun onStop()            { player.stop(); setState(STATE_STOPPED) }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)

        override fun onSkipToNext() {
            val next = currentIndex + 1
            if (next < queue.size) playIndex(next)
        }

        override fun onSkipToPrevious() {
            if (player.currentPosition > 3_000) player.seekTo(0)
            else { val prev = currentIndex - 1; if (prev >= 0) playIndex(prev) }
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

    private fun updateSessionQueue() {
        session.setQueue(queue.mapIndexed { i, v ->
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
