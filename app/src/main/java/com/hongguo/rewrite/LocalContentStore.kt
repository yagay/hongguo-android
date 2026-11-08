package com.hongguo.rewrite

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed persistence for local content only. Never stores account credentials.
 *
 * Existing preference keys and JSON formats are retained intentionally: this
 * refactor must not invalidate user history, downloaded files or feed memory.
 * The caller owns UI state; the store only validates, reads and writes data.
 */
internal class LocalContentStore(private val prefs: SharedPreferences) {
    private val seriesId = Regex("[0-9]{1,30}")

    @Synchronized fun offlineEpisodes(): List<OfflineEpisode> {
        val data = runCatching { JSONArray(prefs.getString("offline_episodes_v1", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until minOf(data.length(), 200)).mapNotNull { i ->
            val row = data.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id")
            val vid = row.optString("vid")
            val uri = row.optString("uri")
            if (!id.matches(seriesId) || !vid.matches(seriesId) ||
                !uri.startsWith("content://")) return@mapNotNull null
            OfflineEpisode(
                id, row.optString("title").take(200), row.optString("cover").take(2048),
                row.optInt("episode", 1).coerceAtLeast(1), vid, uri,
                row.optLong("savedAt"),
            )
        }.distinctBy { it.documentUri }
    }

    @Synchronized fun saveOfflineEpisodes(rows: List<OfflineEpisode>): List<OfflineEpisode> {
        val normalized = rows.distinctBy { it.documentUri }.take(200)
        val data = JSONArray()
        normalized.forEach { row ->
            data.put(JSONObject().put("id", row.seriesId)
                .put("title", row.title).put("cover", row.cover)
                .put("episode", row.episode).put("vid", row.vid)
                .put("uri", row.documentUri).put("savedAt", row.savedAt))
        }
        prefs.edit().putString("offline_episodes_v1", data.toString()).apply()
        return normalized
    }

    @Synchronized fun watchLater(): List<Drama> {
        val rows = runCatching { JSONArray(prefs.getString("watch_later_v1", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until minOf(rows.length(), 200)).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!id.matches(seriesId)) return@mapNotNull null
            Drama(id, item.optString("title"), item.optString("cover"),
                item.optInt("total").coerceAtLeast(0))
        }.distinctBy { it.id }
    }

    @Synchronized fun saveWatchLater(rows: List<Drama>): List<Drama> {
        val normalized = rows.filter { it.id.matches(seriesId) }
            .distinctBy { it.id }.take(200)
        val data = JSONArray()
        normalized.forEach {
            data.put(JSONObject().put("id", it.id).put("title", it.title)
                .put("cover", it.cover).put("total", it.total))
        }
        prefs.edit().putString("watch_later_v1", data.toString()).apply()
        return normalized
    }

    @Synchronized fun recentQueries(): List<String> {
        val data = runCatching { JSONArray(prefs.getString("recent_queries", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until data.length()).map { data.optString(it) }
            .filter { it.isNotBlank() && it.length <= 80 }.distinct().take(12)
    }

    @Synchronized fun rememberQuery(value: String): List<String> {
        val clean = value.trim().take(80)
        if (clean.isEmpty()) return recentQueries()
        val values = (listOf(clean) + recentQueries()).distinct().take(12)
        prefs.edit().putString("recent_queries",
            JSONArray().apply { values.forEach(::put) }.toString()).apply()
        return values
    }

    @Synchronized fun recentlySwiped(): List<String> = runCatching {
        val stored = JSONArray(prefs.getString("recent_official_reels_v1", "[]"))
        (0 until minOf(stored.length(), 120)).map { stored.optString(it) }
            .filter { it.matches(seriesId) }
    }.getOrDefault(emptyList())

    @Synchronized fun rememberSwiped(id: String) {
        if (!id.matches(seriesId)) return
        val last = (recentlySwiped().filterNot { it == id } + id).takeLast(120)
        prefs.edit().putString("recent_official_reels_v1",
            JSONArray(last).toString()).apply()
    }
}
