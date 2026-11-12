package com.yagay.hongguo.kxg

import com.yagay.hongguo.CatalogPage
import com.yagay.hongguo.Drama
import com.yagay.hongguo.DramaDetail
import com.yagay.hongguo.Episode
import com.yagay.hongguo.FilterGroup
import com.yagay.hongguo.FilterOption
import com.yagay.hongguo.OpenHongguoSource
import com.yagay.hongguo.Section
import dev.openhongguo.DiscoverOptions
import dev.openhongguo.EpisodeOptions
import dev.openhongguo.NewReleaseQuery
import dev.openhongguo.SearchOptions
import dev.openhongguo.SeriesSummary
import dev.openhongguo.ShortFilterQuery
import dev.openhongguo.ShortRankQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Maps the Kaixinguo openhongguo SDK's true cursor pages and episode IDs
 * into rewrite's existing stable UI models, without changing Compose screens.
 * Account-backed recommendation requests remain in AccountManager/native
 * because the anonymous Kaixinguo SDK does not support account sessions.
 */
internal class KxgContentSource(private val sdk: OpenHongguoSource) {
    private val gate = Mutex()
    private val cursors = LinkedHashMap<String, MutableMap<Int, String>>()

    suspend fun list(
        section: Section,
        offset: Int,
        genre: String,
        board: String,
        query: String,
        filters: Map<String, String>,
    ): CatalogPage = gate.withLock {
        val key = listOf(section.name, genre, board, query, filters.toSortedMap().toString())
            .joinToString("|")
        if (offset == 0) cursors.remove(key)
        val cursor = if (offset == 0) "" else cursors[key]?.get(offset)
            ?: return@withLock CatalogPage(emptyList(), false, offset)
        val next = cursor.takeIf(String::isNotBlank)
        val rows: List<Drama>
        val more: Boolean
        val newCursor: String
        when (section) {
            Section.RECOMMEND -> {
                val page = sdk.discover(DiscoverOptions(cursor = next))
                rows = page.items.map(::toDrama)
                more = page.hasMore
                newCursor = page.cursor
            }
            Section.SEARCH -> {
                if (query.isBlank()) return@withLock CatalogPage(emptyList(), false, offset)
                val page = sdk.search(query, SearchOptions(cursor = next))
                rows = page.items.map(::toDrama)
                more = page.hasMore
                newCursor = page.cursor
            }
            Section.LATEST -> {
                val page = sdk.newReleases(NewReleaseQuery(cursor = next))
                rows = page.items.filter { it.online && it.seriesId.isNotBlank() }.map {
                    Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount,
                        it.description, categories = it.categories)
                }
                more = page.hasMore
                newCursor = page.cursor
            }
            Section.RANK -> {
                val content = when (board) {
                    "real" -> "human"
                    "comic" -> "comic_series_rank"
                    "ai" -> "ai_playlet"
                    else -> "all"
                }
                val page = sdk.shortRank(ShortRankQuery(content = content, cursor = next))
                rows = page.items.filter { it.seriesId.isNotBlank() }.map {
                    Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount)
                }
                more = page.hasMore
                newCursor = page.cursor
            }
            Section.BROWSE -> {
                val selected = filters.mapValues { listOf(it.value) } +
                    ("genre" to listOf(genre))
                val page = sdk.filter(ShortFilterQuery(selections = selected, cursor = next))
                rows = page.items.map(::toDrama)
                more = page.hasMore
                newCursor = page.cursor
            }
        }
        val unique = rows.filter { it.id.isNotBlank() }.distinctBy(Drama::id)
        val after = offset + unique.size
        val hasMore = more && newCursor.isNotBlank() && newCursor != cursor &&
            after > offset
        if (hasMore) {
            val slots = cursors.getOrPut(key) { mutableMapOf() }
            slots[after] = newCursor
            while (cursors.size > 25) cursors.remove(cursors.keys.first())
        }
        CatalogPage(unique, hasMore, after)
    }

    suspend fun filters(genre: String): List<FilterGroup> =
        sdk.filterPanel(genre).map { group ->
            FilterGroup(group.key, group.name, group.options.map {
                FilterOption(it.value, it.name)
            })
        }

    suspend fun suggestions(keyword: String): List<String> =
        sdk.suggest(keyword).map { it.text }.filter(String::isNotBlank).distinct()

    suspend fun detail(id: String): DramaDetail {
        val series = sdk.series(id)
        val episodes = mutableListOf<Episode>()
        val seen = HashSet<String>()
        var cursor: String? = null
        for (pageIndex in 0 until 40) {
            val page = sdk.episodes(id, EpisodeOptions(cursor = cursor, pageSize = 100))
            for (row in page.items) {
                if (row.id.isNotBlank() && seen.add(row.id)) {
                    episodes += Episode(
                        index = row.number.takeIf { it > 0 } ?: episodes.size + 1,
                        vid = row.id, title = row.title,
                    )
                }
            }
            if (!page.hasMore || page.cursor.isBlank() || page.cursor == cursor)
                break
            cursor = page.cursor
        }
        return DramaDetail(Drama(series.id, series.title, series.coverUrl,
            series.episodeCount, series.description), episodes.sortedBy(Episode::index))
    }

    private val reelCursors = mutableMapOf<String, String>()
    private val reelBuffers = mutableMapOf<String, MutableList<Drama>>()
    private val reelEnded = mutableSetOf<String>()

    suspend fun reelsPage(
        restart: Boolean,
        excluded: Set<String>,
        realOnly: Boolean,
        comicOnly: Boolean,
        firstItemOnly: Boolean,
    ): CatalogPage = gate.withLock {
        val kind = if (comicOnly) "comic" else if (realOnly) "real" else "all"
        if (restart) {
            reelCursors.remove(kind)
            reelBuffers.remove(kind)
            reelEnded.remove(kind)
        }
        val buffer = reelBuffers.getOrPut(kind) { mutableListOf() }
        val wanted = if (firstItemOnly) 1 else 5
        var attempts = 0
        while (buffer.count { it.id !in excluded } < wanted &&
            kind !in reelEnded && attempts++ < 3) {
            val cursor = reelCursors[kind].orEmpty()
            val page = when (kind) {
                "real", "comic" -> {
                    val content = if (kind == "real") "human" else "comic_series_rank"
                    val result = sdk.shortRank(ShortRankQuery(
                        content = content, cursor = cursor.takeIf(String::isNotBlank)))
                    Triple(
                        result.items.filter { it.seriesId.isNotBlank() }.map {
                            Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount)
                        }, result.hasMore, result.cursor
                    )
                }
                else -> {
                    val result = sdk.discover(DiscoverOptions(
                        cursor = cursor.takeIf(String::isNotBlank)))
                    Triple(result.items.map(::toDrama), result.hasMore, result.cursor)
                }
            }
            val known = buffer.mapTo(HashSet()) { it.id }
            buffer += page.first.filter { it.id.isNotBlank() && known.add(it.id) }
            if (!page.second || page.third.isBlank() || page.third == cursor) {
                reelEnded += kind
            } else {
                reelCursors[kind] = page.third
            }
        }
        buffer.removeAll { it.id in excluded }
        val result = buffer.take(wanted)
        repeat(result.size) { buffer.removeAt(0) }
        CatalogPage(result, buffer.isNotEmpty() || kind !in reelEnded, 0)
    }

    private fun toDrama(row: SeriesSummary): Drama =
        Drama(row.id, row.title, row.coverUrl, row.episodeCount, row.description)
}
