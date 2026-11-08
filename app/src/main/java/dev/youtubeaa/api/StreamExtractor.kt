package dev.youtubeaa.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** Resolves playable audio URLs via NewPipe Extractor (handles signature/n-param deobfuscation). */
object StreamExtractor {

    private var initialized = false

    @Synchronized
    private fun init() {
        if (!initialized) {
            NewPipe.init(OkHttpDownloader)
            initialized = true
        }
    }

    suspend fun getBestAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        init()
        runCatching {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            extractor.audioStreams.maxByOrNull { it.averageBitrate }?.content
        }.getOrNull()
    }

    private object OkHttpDownloader : Downloader() {
        private val client = OkHttpClient()

        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url())

            request.headers().forEach { (key, values) ->
                builder.removeHeader(key)
                values.forEach { builder.addHeader(key, it) }
            }

            val method = request.httpMethod()
            val body = request.dataToSend()?.toRequestBody()
                ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null
            builder.method(method, body)

            client.newCall(builder.build()).execute().use { response ->
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string(),
                    response.request.url.toString()
                )
            }
        }
    }
}
