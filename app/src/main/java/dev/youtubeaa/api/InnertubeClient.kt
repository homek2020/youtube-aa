package dev.youtubeaa.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Innertube API client for browsing (home feed, subscriptions, search).
 * Uses TVHTML5 client with Bearer token for account context.
 * Stream URL extraction is in StreamExtractor (NewPipe).
 */
class InnertubeClient(
    private val getToken: suspend () -> String?
) {
    private val http = OkHttpClient()
    private val gson = Gson()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val BASE = "https://www.youtube.com/youtubei/v1"

    // ---- Public API ----

    suspend fun getHomeFeed(): List<VideoItem> =
        parseAnyBrowse(post("browse", browseBody("FEwhat_to_watch")))

    suspend fun getSubscriptionsFeed(): List<VideoItem> =
        parseAnyBrowse(post("browse", browseBody("FEsubscriptions")))

    suspend fun search(query: String): List<VideoItem> {
        val body = context().apply { addProperty("query", query) }
        return parseSearchResults(post("search", body))
    }

    // ---- Request builders ----

    private suspend fun context(): JsonObject = JsonObject().apply {
        add("context", JsonObject().apply {
            add("client", JsonObject().apply {
                addProperty("clientName", "TVHTML5")
                addProperty("clientVersion", "7.20230405.08.01")
                addProperty("platform", "TV")
                addProperty("osName", "Other")
                addProperty("hl", "ru")
                addProperty("gl", "RU")
            })
        })
    }

    private suspend fun browseBody(browseId: String) =
        context().apply { addProperty("browseId", browseId) }

    private suspend fun post(endpoint: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val token = getToken()
            val req = Request.Builder()
                .url("$BASE/$endpoint?key=$API_KEY")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .header("User-Agent", "Mozilla/5.0 (SMART-TV; Linux) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "7")
                .header("X-YouTube-Client-Version", "7.20230405.08.01")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()

            val resp = http.newCall(req).execute()
            gson.fromJson(resp.body?.string() ?: "{}", JsonObject::class.java)
        }

    // ---- Parsers ----

    /**
     * Tries multiple known response shapes:
     *   1. TV: contents → tvBrowseRenderer → content → tvSurfaceContentRenderer → content → sectionListRenderer
     *   2. Web fallback: contents → sectionListRenderer
     *   3. Rich grid (home feed web): contents → richGridRenderer
     */
    private fun parseAnyBrowse(root: JsonObject): List<VideoItem> {
        val items = mutableListOf<VideoItem>()

        // Path 1 — TV response
        root.obj("contents")
            ?.obj("tvBrowseRenderer")?.obj("content")
            ?.obj("tvSurfaceContentRenderer")?.obj("content")
            ?.obj("sectionListRenderer")?.arr("contents")
            ?.let { sections -> collectFromSectionList(sections, items) }

        if (items.isNotEmpty()) return items

        // Path 2 — Web sectionListRenderer (sometimes returned for TV client too)
        root.obj("contents")
            ?.obj("sectionListRenderer")?.arr("contents")
            ?.let { sections -> collectFromSectionList(sections, items) }

        if (items.isNotEmpty()) return items

        // Path 3 — richGridRenderer (web home feed)
        root.obj("contents")
            ?.obj("richGridRenderer")?.arr("contents")
            ?.forEach { el ->
                el.asJsonObject.obj("richItemRenderer")
                    ?.obj("content")
                    ?.let { parseVideoRenderer(it) }
                    ?.let { items.add(it) }
            }

        return items
    }

    private fun collectFromSectionList(sections: JsonArray, out: MutableList<VideoItem>) {
        sections.forEach { section ->
            val s = section.asJsonObject

            // Shelf → horizontalListRenderer
            s.obj("shelfRenderer")?.obj("content")
                ?.obj("horizontalListRenderer")?.arr("items")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { out.add(it) } }

            // ItemSection (subscriptions grid)
            s.obj("itemSectionRenderer")?.arr("contents")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { out.add(it) } }
        }
    }

    private fun parseSearchResults(root: JsonObject): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        root.obj("contents")
            ?.obj("sectionListRenderer")?.arr("contents")
            ?.forEach { section ->
                section.asJsonObject.obj("itemSectionRenderer")?.arr("contents")
                    ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { items.add(it) } }
            }
        return items
    }

    private fun parseVideoRenderer(obj: JsonObject): VideoItem? {
        val r = obj.obj("compactVideoRenderer")
            ?: obj.obj("videoRenderer")
            ?: obj.obj("gridVideoRenderer")
            ?: return null

        val videoId = r.str("videoId") ?: return null

        val title = r.obj("title")?.arr("runs")?.get(0)?.asJsonObject?.str("text")
            ?: r.obj("title")?.str("simpleText")
            ?: return null

        val channel = r.obj("shortBylineText")?.arr("runs")?.get(0)?.asJsonObject?.str("text")
            ?: r.obj("longBylineText")?.arr("runs")?.get(0)?.asJsonObject?.str("text")
            ?: ""

        val thumbnail = r.obj("thumbnail")?.arr("thumbnails")
            ?.lastOrNull()?.asJsonObject?.str("url") ?: ""

        val duration = r.obj("lengthText")?.str("simpleText")
            ?: r.obj("lengthText")?.obj("accessibility")
                ?.obj("accessibilityData")?.str("label")
            ?: ""

        val views = r.obj("viewCountText")?.str("simpleText")
            ?: r.obj("shortViewCountText")?.str("simpleText")
            ?: ""

        return VideoItem(videoId, title, channel, thumbnail, duration, views)
    }

    // ---- JsonObject extensions ----
    private fun JsonObject.obj(key: String) = runCatching { getAsJsonObject(key) }.getOrNull()
    private fun JsonObject.arr(key: String) = runCatching { getAsJsonArray(key) }.getOrNull()
    private fun JsonObject.str(key: String) = runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
}

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val duration: String,
    val views: String = ""
)
