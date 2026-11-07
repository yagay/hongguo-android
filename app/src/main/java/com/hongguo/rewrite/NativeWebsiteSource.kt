package com.hongguo.rewrite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Public website metadata only, with no Node dependency and no playback claims.
 * Adapted from hongguoTV ContentRepository and OOIUa/hongguo-android WebSource
 * (both GPL-3.0-only compatible).
 */
class NativeWebsiteSource(
    private val origin: HttpUrl = "https://hongguoduanju.com/".toHttpUrl(),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS).build(),
) {
    init {
        require(origin.scheme == "https" || origin.host == "127.0.0.1" || origin.host == "localhost")
        require(origin.encodedPath == "/")
    }

    suspend fun list(section: Section, offset: Int, query: String): CatalogPage {
        val page = (offset / PAGE_SIZE + 1).coerceIn(1, 100)
        return when (section) {
            Section.RECOMMEND -> home(page)
            Section.SEARCH -> search(query)
            else -> throw UnsupportedOperationException("此分类需要完整内容接口，暂不可通过公开网页读取")
        }
    }

    suspend fun search(query: String): CatalogPage {
        if (query.isBlank()) return CatalogPage(emptyList(), false, 0)
        val root = extractRouter(fetch("search", query.trim().take(120)))
        val list = find(root, "searchList") as? JSONArray
            ?: throw IOException("WEBSITE_STRUCTURE_CHANGED: searchList")
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::card) }
            .distinctBy { it.id }
        return CatalogPage(items, false, items.size)
    }

    suspend fun home(page: Int = 1): CatalogPage {
        val root = extractRouter(fetch("category", params = mapOf(
            "tab" to "1", "sort_type" to "1", "page" to page.toString(),
        )))
        val list = find(root, "recommendList") as? JSONArray
            ?: throw IOException("WEBSITE_STRUCTURE_CHANGED: recommendList")
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::card) }
            .distinctBy { it.id }
        val pages = (find(root, "totalPages") as? Number)?.toInt()?.coerceIn(1, 100) ?: 1
        return CatalogPage(items, items.isNotEmpty() && page < pages, page * PAGE_SIZE)
    }

    suspend fun detail(id: String): DramaDetail {
        require(ID.matches(id))
        val root = extractRouter(fetch("player", id))
        val raw = find(root, "vid_list") as? JSONArray
            ?: throw IOException("WEBSITE_STRUCTURE_CHANGED: vid_list")
        val episodes = (0 until raw.length()).mapNotNull { i ->
            raw.optString(i).takeIf { ID.matches(it) }?.let { Episode(i + 1, it) }
        }
        if (episodes.isEmpty() || episodes.size != raw.length() ||
            episodes.distinctBy { it.vid }.size != episodes.size)
            throw IOException("EPISODES_INVALID")
        val data = (find(root, "seriesDetail") as? JSONObject)
            ?: (find(root, "video_data") as? JSONObject) ?: root
        return DramaDetail(
            Drama(id,
                data.optString("series_name", data.optString("series_title", "未命名短剧")),
                cleanCover(data.optString("series_cover")),
                episodes.size,
                data.optString("series_intro")),
            episodes
        )
    }

    fun coverUrl(url: String): String {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return ""
        val domains = listOf("fqnovelpic.com", "byteimg.com", "qznovelvod.com",
            "douyinpic.com", "pstatp.com")
        return if (parsed.scheme == "https" &&
            domains.any { parsed.host == it || parsed.host.endsWith("." + it) }) url else ""
    }

    private suspend fun fetch(path: String, segment: String? = null,
                              params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = origin.newBuilder().addPathSegment(path)
            if (segment != null) builder.addPathSegment(segment)
            params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
            val request = Request.Builder().url(builder.build())
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/122.0 Mobile Safari/537.36")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("WEBSITE_HTTP_" + response.code)
                val body = response.body ?: throw IOException("WEBSITE_EMPTY_RESPONSE")
                val html = body.charStream().use { it.readText().take(MAX_HTML + 1) }
                if (html.length > MAX_HTML) throw IOException("WEBSITE_RESPONSE_TOO_LARGE")
                if (html.isBlank()) throw IOException("WEBSITE_EMPTY_RESPONSE")
                html
            }
        }

    private fun card(item: JSONObject): Drama? {
        val data = item.optJSONObject("video_data") ?: item
        val id = data.optString("series_id")
        if (!ID.matches(id)) return null
        return Drama(id,
            data.optString("series_name", data.optString("series_title",
                item.optString("name", "未命名短剧"))),
            cleanCover(data.optString("series_cover")), data.optInt("episode_cnt"),
            data.optString("series_intro"))
    }

    companion object {
        private val ID = Regex("[0-9]{1,30}")
        const val PAGE_SIZE = 20
        const val MAX_HTML = 4 * 1024 * 1024

        fun cleanCover(raw: String) = raw.replace("\\u002F", "/")
            .replace("\\u0026", "&").replace("&amp;", "&")

        fun extractRouter(html: String): JSONObject {
            val marker = html.indexOf("_ROUTER_DATA")
            if (marker < 0) throw IOException("WEBSITE_STRUCTURE_CHANGED: _ROUTER_DATA")
            val first = html.indexOf('{', marker)
            if (first < 0) throw IOException("WEBSITE_STRUCTURE_CHANGED: JSON")
            var depth = 0
            var quoted = false
            var escaped = false
            for (i in first until html.length) {
                val ch = html[i]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == '"') quoted = false
                    continue
                }
                when (ch) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            return try { JSONObject(html.substring(first, i + 1)) }
                            catch (_: Exception) { throw IOException("WEBSITE_STRUCTURE_CHANGED: JSON_PARSE") }
                        }
                    }
                }
            }
            throw IOException("WEBSITE_STRUCTURE_CHANGED: UNBALANCED")
        }

        fun find(node: Any?, key: String, depth: Int = 0): Any? {
            if (depth > 12) return null
            return when (node) {
                is JSONObject -> {
                    if (node.has(key)) node.opt(key)
                    else {
                        val iterator = node.keys()
                        var found: Any? = null
                        while (iterator.hasNext() && found == null) {
                            val child = node.opt(iterator.next())
                            if (child is JSONArray || child is JSONObject)
                                found = find(child, key, depth + 1)
                        }
                        found
                    }
                }
                is JSONArray -> {
                    var found: Any? = null
                    for (i in 0 until minOf(node.length(), 200)) {
                        val child = node.opt(i)
                        if (child is JSONArray || child is JSONObject) {
                            found = find(child, key, depth + 1)
                            if (found != null) break
                        }
                    }
                    found
                }
                else -> null
            }
        }
    }
}
