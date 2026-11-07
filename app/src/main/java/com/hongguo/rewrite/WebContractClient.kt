package com.hongguo.rewrite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Isolated adapter for sunnysky123/hongguo-web HTTP contract; replaceable by a native source. */
class WebContractClient(
    private val base: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val diagnostics: DiagnosticLog? = null,
) {
    private val origin: HttpUrl = normalizedBase(base)
    @Volatile private var key: String? = null
    private val keyLock = Any()

    companion object {
        fun normalizedBase(input: String): HttpUrl {
            val url = input.trim().trimEnd('/').plus("/").toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty()) { "地址不能包含账号密码" }
            require(url.fragment == null && url.query == null) { "请填写 API 服务根地址" }
            require(url.encodedPath == "/") { "请填写域名及端口，不要附带 API 路径" }
            require(url.scheme == "https" || url.host == "127.0.0.1"
                || url.host == "localhost" || url.host.startsWith("192.168.")
                || url.host.startsWith("10.") || Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*").matches(url.host)) {
                "公共网络服务必须使用 HTTPS"
            }
            return url
        }
    }

    private suspend fun ensureKey(): String {
        key?.let { return it }
        return withContext(Dispatchers.IO) {
            synchronized(keyLock) {
                key ?: run {
                    val req = Request.Builder().url(url("api-key")).get().build()
                    val body = execute(req)
                    body.optString("api_key").takeIf { it.startsWith("hg_") }
                        ?: throw IOException("API 没有返回有效的 api_key")
                }.also { key = it }
            }
        }
    }

    private fun url(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = origin.newBuilder().addPathSegments(path.trimStart('/'))
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun execute(req: Request): JSONObject {
        val started = System.nanoTime()
        val endpoint = when (req.url.encodedPath) {
            "/api-key" -> "KEY"
            "/health" -> "HEALTH"
            "/rank" -> "RANK"
            "/latest" -> "LATEST"
            "/browse" -> "BROWSE"
            "/filters" -> "FILTERS"
            "/search" -> "SEARCH"
            "/episodes" -> "EPISODES"
            "/stream" -> "STREAM"
            "/share/resolve" -> "SHARE"
            "/download/task" -> "DOWNLOAD"
            else -> "OTHER"
        }
        var status = 0
        try {
            return client.newCall(req).execute().use { response: Response ->
                status = response.code
                val body = response.body?.string().orEmpty()
                diagnostics?.record("SERVER_HTTP", "REPLY_" + endpoint,
                    (System.nanoTime() - started) / 1_000_000,
                    httpStatus = response.code, bytes = body.length)
                if (!response.isSuccessful) {
                    throw IOException("HTTP " + response.code + " (" + req.url.encodedPath + ")")
                }
                if (body.isBlank()) throw IOException("EMPTY_RESPONSE: " + req.url.encodedPath)
                try { JSONObject(body) } catch (_: Exception) {
                    throw IOException("INVALID_JSON: " + req.url.encodedPath)
                }
            }
        } catch (e: IOException) {
            if (status == 0) diagnostics?.record("SERVER_HTTP", "IO_" + endpoint,
                (System.nanoTime() - started) / 1_000_000)
            throw e
        }
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val apiKey = ensureKey()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path, params)).header("x-api-key", apiKey).get().build()
            try { execute(req) } catch (e: IOException) {
                // Refresh key once for authentication errors, never retry invalid JSON blindly.
                if (e.message?.startsWith("HTTP 401") != true) throw e
                key = null
                val fresh = ensureKey()
                execute(req.newBuilder().header("x-api-key", fresh).build())
            }
        }
    }

    private suspend fun post(path: String, payload: JSONObject): JSONObject {
        val apiKey = ensureKey()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path))
                .header("x-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            execute(req)
        }
    }

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("health")).build()
        execute(req).optBoolean("ok")
    }

    suspend fun list(
        section: Section,
        offset: Int,
        genre: String,
        board: String,
        query: String,
        filters: Map<String, String>,
        seen: List<String>,
    ): CatalogPage {
        val params = mutableMapOf("limit" to "30")
        val path = when (section) {
            Section.RECOMMEND -> { params["board"] = "recommend"; "rank" }
            Section.RANK -> { params["board"] = board; "rank" }
            Section.LATEST -> {
                params["genre"] = genre; params["only_today"] = "false"; "latest"
            }
            Section.BROWSE -> {
                params["genre"] = genre
                params.putAll(filters)
                "browse"
            }
            Section.SEARCH -> { params["q"] = query; "search" }
        }
        if (offset > 0) params["offset"] = offset.toString()
        if (offset > 0 && section in setOf(Section.LATEST, Section.BROWSE))
            params["seen"] = seen.takeLast(100).joinToString(",")
        val response = get(path, params)
        val list = response.optJSONArray(if (section == Section.SEARCH) "results" else "items")
            ?: JSONArray()
        val rows = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let(::parseDrama)
        }.distinctBy { it.id }
        val next = response.optInt("next_offset", offset + rows.size)
        // First-page rank responses intentionally omit has_more, so offer a single explicit probe.
        val hasMore = if (response.has("has_more")) response.optBoolean("has_more")
            else rows.size >= 30
        return CatalogPage(rows, hasMore && next > offset, next)
    }

    suspend fun filters(genre: String): List<FilterGroup> {
        val response = get("filters", mapOf("genre" to genre))
        val raw = response.optJSONArray("rows") ?: JSONArray()
        val mapping = mapOf(
            "category_dim_theme" to "theme", "category_dim_role" to "setting",
            "category_dim_epoch" to "background", "online_time" to "days",
            "creation_status" to "status",
        )
        return (0 until raw.length()).mapNotNull { i ->
            val row = raw.optJSONObject(i) ?: return@mapNotNull null
            val type = row.optString("type")
            val key = mapping[type] ?: type
            if (key !in setOf("theme", "setting", "background", "sort", "days", "gender", "status"))
                return@mapNotNull null
            val items = row.optJSONArray("items") ?: JSONArray()
            val options = (0 until items.length()).mapNotNull { j ->
                val opt = items.optJSONObject(j) ?: return@mapNotNull null
                val name = opt.optString("name", opt.optString("show_name"))
                val value = opt.optString("id", opt.optString("selector_item_id"))
                if (name.isBlank() || value.isBlank()) null else FilterOption(value, name)
            }
            FilterGroup(key, row.optString("row_name", key),
                listOf(FilterOption("", "全部")) + options)
        }.distinctBy { it.key }
    }

    suspend fun detail(id: String): DramaDetail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val result = get("episodes", mapOf("series_id" to id))
        val meta = result.optJSONObject("meta") ?: JSONObject()
        val drama = Drama(id, meta.optString("title", "未命名"),
            meta.optString("cover"), result.optJSONArray("episodes")?.length() ?: 0,
            meta.optString("abstract"))
        val eps = result.optJSONArray("episodes") ?: JSONArray()
        val items = (0 until eps.length()).mapNotNull { i ->
            val entry = eps.optJSONObject(i) ?: return@mapNotNull null
            val vid = entry.optString("vid")
            if (!vid.matches(Regex("[0-9]{1,30}"))) null
            else Episode(entry.optInt("index", i + 1), vid, entry.optString("title"))
        }
        return DramaDetail(drama, items)
    }

    suspend fun playableUrl(vid: String): String {
        require(vid.matches(Regex("[0-9]{1,30}")))
        val apiKey = ensureKey()
        return url("stream", mapOf("vid" to vid, "api_key" to apiKey)).toString()
    }

    suspend fun resolveShare(message: String): Pair<String, Int> {
        val result = post("share/resolve", JSONObject().put("text", message))
        val id = result.optString("series_id")
        if (!id.matches(Regex("[0-9]{1,30}"))) throw IOException("没有识别到剧集")
        val ep = result.optJSONObject("episode")?.optInt("index", 1) ?: 1
        return id to ep
    }

    suspend fun startDownload(id: String, from: Int, to: Int): DownloadTask {
        val result = post("download/task", JSONObject()
            .put("series_id", id).put("start", from).put("end", to))
        return parseTask(result.optJSONObject("task") ?: JSONObject())
    }

    suspend fun downloadTask(id: String): DownloadTask = parseTask(
        get("download/task", mapOf("id" to id)).optJSONObject("task") ?: JSONObject()
    )

    fun coverUrl(image: String): String {
        if (image.isBlank()) return ""
        val parsed = runCatching { image.toHttpUrl() }.getOrNull() ?: return ""
        val allowed = listOf("fqnovelpic.com", "byteimg.com", "qznovelvod.com",
            "douyinpic.com", "pstatp.com")
        if (allowed.none { parsed.host == it || parsed.host.endsWith("." + it) }) return ""
        return url("img", mapOf("url" to image)).toString()
    }

    private fun parseDrama(json: JSONObject): Drama? {
        val id = json.optString("series_id")
        if (!id.matches(Regex("[0-9]{1,30}"))) return null
        return Drama(id,
            json.optString("title", json.optString("series_title", "未命名短剧")),
            json.optString("cover", json.optString("series_cover")),
            json.optInt("episode_cnt", json.optInt("total")),
            json.optString("abstract", json.optString("series_intro")),
        )
    }

    private fun parseTask(json: JSONObject): DownloadTask {
        val failures = json.optJSONArray("failed") ?: JSONArray()
        return DownloadTask(json.optString("id"), json.optString("status"),
            json.optInt("done"), json.optInt("total"),
            (0 until failures.length()).map { failures.optInt(it) })
    }
}
