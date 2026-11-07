package dev.youtubeaa.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Extracts playable stream URLs using NewPipe Extractor.
 * NewPipe handles YouTube's obfuscated "n" parameter that would throttle direct URLs.
 */
object StreamExtractor {

    private var initialized = false

    fun init() {
        if (!initialized) {
            NewPipe.init(AndroidDownloader)
            initialized = true
        }
    }

    suspend fun getBestAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        init()
        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(url)
            extractor.fetchPage()
            extractor.audioStreams
                .maxByOrNull { it.averageBitrate }
                ?.content  // the stream URL
        } catch (e: Exception) {
            null
        }
    }

    // Simple OkHttp-backed downloader for NewPipe
    private object AndroidDownloader : Downloader() {
        private val client = okhttp3.OkHttpClient()

        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url())

            request.headers().forEach { (key, values) ->
                values.forEach { builder.header(key, it) }
            }

            request.dataToSend()?.let { body ->
                builder.post(okhttp3.RequestBody.create(null, body))
            }

            val response = client.newCall(builder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val headers = response.headers.toMultimap()

            return Response(response.code, response.message, headers, responseBody, request.url())
        }
    }
}
