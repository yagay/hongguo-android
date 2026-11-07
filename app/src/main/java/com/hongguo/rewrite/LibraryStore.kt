package com.hongguo.rewrite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Atomic whole-list writes, bounded local data, never mixed with a cloud account. */
class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("web_rewrite_library", Context.MODE_PRIVATE)

    @Synchronized fun list(favorites: Boolean): List<Watched> {
        val value = prefs.getString(if (favorites) "favorites" else "history", "[]") ?: "[]"
        val rows = runCatching { JSONArray(value) }.getOrElse { JSONArray() }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            if (!id.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
            val drama = Drama(id, row.optString("title", "未命名短剧"),
                row.optString("cover"), row.optInt("total"),
                row.optString("synopsis"))
            Watched(drama, row.optInt("episode", 1).coerceAtLeast(1), row.optLong("updated"))
        }.distinctBy { it.drama.id }.take(200)
    }

    @Synchronized fun record(drama: Drama, episode: Int) {
        val now = System.currentTimeMillis()
        val old = list(false).filterNot { it.drama.id == drama.id }
        write(false, listOf(Watched(drama, episode.coerceAtLeast(1), now)) + old)
        val fav = list(true)
        if (fav.any { it.drama.id == drama.id }) {
            write(true, fav.map {
                if (it.drama.id == drama.id) it.copy(episode = episode.coerceAtLeast(1)) else it
            })
        }
    }

    @Synchronized fun toggle(drama: Drama, episode: Int): Boolean {
        val fav = list(true)
        val exists = fav.any { it.drama.id == drama.id }
        if (exists) write(true, fav.filterNot { it.drama.id == drama.id })
        else write(true, listOf(Watched(drama, episode.coerceAtLeast(1),
            System.currentTimeMillis())) + fav)
        return !exists
    }

    @Synchronized fun remove(id: String, favorites: Boolean) =
        write(favorites, list(favorites).filterNot { it.drama.id == id })

    @Synchronized fun clear(favorites: Boolean) = write(favorites, emptyList())

    private fun write(favorites: Boolean, rows: List<Watched>) {
        val data = JSONArray()
        rows.distinctBy { it.drama.id }.take(200).forEach { row ->
            data.put(JSONObject().put("id", row.drama.id)
                .put("title", row.drama.title).put("cover", row.drama.cover)
                .put("total", row.drama.total).put("synopsis", row.drama.synopsis)
                .put("episode", row.episode).put("updated", row.updated))
        }
        prefs.edit().putString(if (favorites) "favorites" else "history", data.toString()).apply()
    }
}
