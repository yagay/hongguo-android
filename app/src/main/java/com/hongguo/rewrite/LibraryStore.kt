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

    @Synchronized fun favoriteGroups(): Map<String, String> {
        val root = runCatching {
            JSONObject(prefs.getString("favorite_groups", "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        return root.keys().asSequence().mapNotNull { id ->
            val group = root.optString(id)
            if (id.matches(Regex("[0-9]{1,30}")) && group.length in 1..24)
                id to group else null
        }.toMap()
    }

    @Synchronized fun setFavoriteGroup(seriesId: String, group: String) {
        require(seriesId.matches(Regex("[0-9]{1,30}")))
        require(group.length <= 24 && group.none { it.isISOControl() })
        val groups = favoriteGroups().toMutableMap()
        if (group.isBlank() || group == "默认") groups.remove(seriesId)
        else groups[seriesId] = group.trim()
        val root = JSONObject()
        groups.forEach { (key, value) -> root.put(key, value) }
        prefs.edit().putString("favorite_groups", root.toString()).apply()
    }

    @Synchronized fun replaceFavoriteGroups(groups: Map<String, String>) {
        require(groups.size <= 200 && groups.all { (id, name) ->
            id.matches(Regex("[0-9]{1,30}")) && name.length in 1..24 &&
                name.none { it.isISOControl() }
        })
        val saved = JSONObject()
        groups.forEach { (id, name) -> saved.put(id, name) }
        check(prefs.edit().putString("favorite_groups", saved.toString()).commit()) {
            "无法恢复收藏分组"
        }
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
    @Synchronized fun exportLists(): JSONObject {
        fun encode(rows: List<Watched>) = JSONArray().apply {
            rows.forEach {
                put(JSONObject().put("id", it.drama.id).put("title", it.drama.title)
                    .put("cover", it.drama.cover).put("total", it.drama.total)
                    .put("episode", it.episode).put("updated", it.updated))
            }
        }
        return JSONObject().put("history", encode(list(false)))
            .put("favorites", encode(list(true)))
    }

    /** Validate all imported rows before touching either current list. */
    @Synchronized fun importLists(data: JSONObject, favoriteGroups: Map<String, String> = emptyMap()) {
        fun decode(key: String): List<Watched> {
            val array = data.getJSONArray(key)
            require(array.length() <= 200) { "片单条目超过 200 部" }
            return (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                val id = row.getString("id")
                val title = row.getString("title")
                require(id.matches(Regex("[0-9]{1,30}")) && title.length <= 200) {
                    "备份片单内容无效"
                }
                val cover = row.optString("cover")
                require(cover.length <= 2048) { "封面地址过长" }
                Watched(Drama(id, title, cover, row.optInt("total").coerceIn(0, 100000)),
                    row.optInt("episode", 1).coerceIn(1, 100000),
                    row.optLong("updated"))
            }.distinctBy { it.drama.id }
        }
        val history = decode("history")
        val favorites = decode("favorites")
        require(favoriteGroups.size <= 200 && favoriteGroups.all { (id, name) ->
            id.matches(Regex("[0-9]{1,30}")) &&
                name.length in 1..24 && name.none { it.isISOControl() }
        })
        val groupsJson = JSONObject()
        favoriteGroups.forEach { (id, name) -> groupsJson.put(id, name) }
        fun encode(rows: List<Watched>) = JSONArray().apply {
            rows.forEach {
                put(JSONObject().put("id", it.drama.id).put("title", it.drama.title)
                    .put("cover", it.drama.cover).put("total", it.drama.total)
                    .put("episode", it.episode).put("updated", it.updated))
            }
        }.toString()
        prefs.edit().putString("history", encode(history))
            .putString("favorites", encode(favorites))
            .putString("favorite_groups", groupsJson.toString()).commit().also {
                check(it) { "无法保存本机片单" }
            }
    }



    /**
     * Bounded per-episode checkpoints. Stored separately from the history
     * list: periodic playback writes must not reshuffle history or favorites.
     */
    @Synchronized fun checkpoint(seriesId: String, vid: String): Long {
        val rows = progressRows()
        return rows.firstOrNull {
            it.optString("sid") == seriesId && it.optString("vid") == vid
        }?.let { ResumePolicy.resumable(
            it.optLong("position"), it.optLong("duration")
        ) } ?: 0L
    }

    @Synchronized fun saveCheckpoint(
        seriesId: String, vid: String, episode: Int,
        positionMs: Long, durationMs: Long,
    ) {
        if (!seriesId.matches(Regex("[0-9]{1,30}")) ||
            !vid.matches(Regex("[0-9]{1,30}")) ||
            !ResumePolicy.persistable(positionMs, durationMs)) return
        val rows = progressRows().filterNot {
            it.optString("sid") == seriesId && it.optString("vid") == vid
        }.take(199)
        val item = JSONObject()
            .put("sid", seriesId).put("vid", vid)
            .put("episode", episode.coerceAtLeast(1))
            .put("position", positionMs)
            .put("duration", durationMs)
            .put("updated", System.currentTimeMillis())
        prefs.edit().putString("checkpoints",
            JSONArray().apply { put(item); rows.forEach { put(it) } }.toString()).apply()
    }

    @Synchronized fun clearCheckpoint(seriesId: String, vid: String) {
        val kept = progressRows().filterNot {
            it.optString("sid") == seriesId && it.optString("vid") == vid
        }
        prefs.edit().putString("checkpoints",
            JSONArray().apply { kept.forEach { put(it) } }.toString()).apply()
    }

    private fun progressRows(): List<JSONObject> {
        val values = runCatching {
            JSONArray(prefs.getString("checkpoints", "[]") ?: "[]")
        }.getOrElse { JSONArray() }
        return (0 until minOf(values.length(), 200)).mapNotNull {
            values.optJSONObject(it)
        }
    }

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
