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

data class VideoItem(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String,
    val duration: String,
    val views: String = ""
)

/** A single page of results plus an optional token for the next page. */
data class FeedPage(
    val items: List<VideoItem>,
    val continuation: String? // null = no more pages
)

class InnertubeClient(
    private val getToken: suspend () -> String?
) {
    private val http = OkHttpClient()
    private val gson = Gson()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private val BASE = "https://www.youtube.com/youtubei/v1"

    // ---- Public API ----

    suspend fun getHomeFeed(continuation: String? = null): FeedPage =
        if (continuation == null) parseAnyBrowse(post("browse", browseBody("FEwhat_to_watch")))
        else parseContinuation(post("browse", continuationBody(continuation)))

    suspend fun getSubscriptionsFeed(continuation: String? = null): FeedPage =
        if (continuation == null) parseAnyBrowse(post("browse", browseBody("FEsubscriptions")))
        else parseContinuation(post("browse", continuationBody(continuation)))

    suspend fun search(query: String, continuation: String? = null): FeedPage {
        val body = if (continuation == null) {
            context().apply { addProperty("query", query) }
        } else {
            continuationBody(continuation)
        }
        val root = post("search", body)
        return if (continuation == null) parseSearchResults(root) else parseContinuation(root)
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

    private suspend fun continuationBody(token: String) =
        context().apply { addProperty("continuation", token) }

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

    // ---- First-page parsers ----

    private fun parseAnyBrowse(root: JsonObject): FeedPage {
        val items = mutableListOf<VideoItem>()

        // Path 1: TV response
        val tvSections = root.obj("contents")
            ?.obj("tvBrowseRenderer")?.obj("content")
            ?.obj("tvSurfaceContentRenderer")?.obj("content")
            ?.obj("sectionListRenderer")

        if (tvSections != null) {
            tvSections.arr("contents")?.let { collectFromSectionList(it, items) }
            return FeedPage(items, extractContinuation(tvSections))
        }

        // Path 2: Web sectionListRenderer
        val webSections = root.obj("contents")?.obj("sectionListRenderer")
        if (webSections != null) {
            webSections.arr("contents")?.let { collectFromSectionList(it, items) }
            return FeedPage(items, extractContinuation(webSections))
        }

        // Path 3: richGridRenderer (web home feed)
        val grid = root.obj("contents")?.obj("richGridRenderer")
        if (grid != null) {
            grid.arr("contents")?.forEach { el ->
                el.asJsonObject.obj("richItemRenderer")?.obj("content")
                    ?.let { parseVideoRenderer(it) }?.let { items.add(it) }
            }
            return FeedPage(items, extractContinuation(grid))
        }

        return FeedPage(items, null)
    }

    private fun parseSearchResults(root: JsonObject): FeedPage {
        val items = mutableListOf<VideoItem>()
        val sectionList = root.obj("contents")?.obj("sectionListRenderer") ?: return FeedPage(items, null)

        sectionList.arr("contents")?.forEach { section ->
            section.asJsonObject.obj("itemSectionRenderer")?.arr("contents")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { items.add(it) } }
        }
        return FeedPage(items, extractContinuation(sectionList))
    }

    // ---- Continuation-page parser ----

    private fun parseContinuation(root: JsonObject): FeedPage {
        val items = mutableListOf<VideoItem>()

        val cc = root.obj("continuationContents") ?: return FeedPage(items, null)

        // Browse continuation
        val sectionCont = cc.obj("sectionListContinuation")
        if (sectionCont != null) {
            sectionCont.arr("contents")?.let { collectFromSectionList(it, items) }
            return FeedPage(items, extractContinuation(sectionCont))
        }

        // Search continuation
        val itemCont = cc.obj("itemSectionContinuation")
        if (itemCont != null) {
            itemCont.arr("contents")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { items.add(it) } }
            return FeedPage(items, extractContinuation(itemCont))
        }

        // Rich grid continuation (home feed)
        val gridCont = cc.obj("richGridContinuation")
        if (gridCont != null) {
            gridCont.arr("contents")?.forEach { el ->
                el.asJsonObject.obj("richItemRenderer")?.obj("content")
                    ?.let { parseVideoRenderer(it) }?.let { items.add(it) }
            }
            return FeedPage(items, extractContinuation(gridCont))
        }

        return FeedPage(items, null)
    }

    // ---- Shared helpers ----

    private fun collectFromSectionList(sections: JsonArray, out: MutableList<VideoItem>) {
        sections.forEach { section ->
            val s = section.asJsonObject
            s.obj("shelfRenderer")?.obj("content")
                ?.obj("horizontalListRenderer")?.arr("items")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { out.add(it) } }
            s.obj("itemSectionRenderer")?.arr("contents")
                ?.forEach { item -> parseVideoRenderer(item.asJsonObject)?.let { out.add(it) } }
        }
    }

    private fun extractContinuation(parent: JsonObject): String? =
        parent.arr("continuations")
            ?.firstOrNull()?.asJsonObject
            ?.obj("nextContinuationData")
            ?.str("continuation")

    private fun parseVideoRenderer(obj: JsonObject): VideoItem? {
        val r = obj.obj("compactVideoRenderer")
            ?: obj.obj("videoRenderer")
            ?: obj.obj("gridVideoRenderer")
            ?: return null

        val videoId = r.str("videoId") ?: return null
        val title = r.obj("title")?.arr("runs")?.get(0)?.asJsonObject?.str("text")
            ?: r.obj("title")?.str("simpleText") ?: return null
        val channel = r.obj("shortBylineText")?.arr("runs")?.get(0)?.asJsonObject?.str("text")
            ?: r.obj("longBylineText")?.arr("runs")?.get(0)?.asJsonObject?.str("text") ?: ""
        val thumbnail = r.obj("thumbnail")?.arr("thumbnails")
            ?.lastOrNull()?.asJsonObject?.str("url") ?: ""
        val duration = r.obj("lengthText")?.str("simpleText") ?: ""
        val views = r.obj("viewCountText")?.str("simpleText")
            ?: r.obj("shortViewCountText")?.str("simpleText") ?: ""

        return VideoItem(videoId, title, channel, thumbnail, duration, views)
    }

    private fun JsonObject.obj(key: String) = runCatching { getAsJsonObject(key) }.getOrNull()
    private fun JsonObject.arr(key: String) = runCatching { getAsJsonArray(key) }.getOrNull()
    private fun JsonObject.str(key: String) = runCatching { get(key)?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
}
