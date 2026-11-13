package com.yagay.hongguo

import android.content.Context
import dev.openhongguo.ClientOptions
import dev.openhongguo.OpenhongguoClient
import dev.openhongguo.DiscoverOptions
import dev.openhongguo.SearchOptions
import dev.openhongguo.NewReleaseQuery
import dev.openhongguo.ShortFilterQuery
import dev.openhongguo.ShortRankQuery
import dev.openhongguo.LandingOptions
import dev.openhongguo.Series
import dev.openhongguo.Person
import dev.openhongguo.WorkPage
import dev.openhongguo.HotSearch
import dev.openhongguo.NewReleasePage
import dev.openhongguo.Album
import dev.openhongguo.FilterRow
import dev.openhongguo.ShortFilterPage
import dev.openhongguo.ShortRanking
import dev.openhongguo.Ranking
import dev.openhongguo.RankList
import dev.openhongguo.Suggestion
import dev.openhongguo.CategoryPage
import dev.openhongguo.Page
import dev.openhongguo.SeriesSummary

/**
 * The Kaixinguo SDK now provides the catalog and anonymous playback model.
 * Authenticated feed, comments and account sessions remain separate.
 *
 * Apache-2.0 SDK 0.2.0 is lazy-created only on explicit read-only discovery or
 * metadata operations. Do not reset a user's anonymous session on media errors.
 */
internal class OpenHongguoSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val client: OpenhongguoClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpenhongguoClient.create(applicationContext,
            ClientOptions(retryAttempts = 3, deviceResets = 3))
    }

    /**
     * Curated read-only discovery surfaces from the same published SDK.
     * Server cursor and actor identity remain source-defined; no fake ranks.
     */
    suspend fun browse(
        tab: String, cursor: String = "",
        selectedFilters: Map<String, List<String>> = emptyMap(),
    ): OpenHongguoBrowsePage {
        fun useCursor(value: String) = value.takeIf(String::isNotBlank)
        return when (tab) {
            "new" -> {
                val p = newReleases(NewReleaseQuery(cursor = useCursor(cursor)))
                OpenHongguoBrowsePage(p.items.map {
                    OpenHongguoBrowseItem(
                        id = it.seriesId, title = it.title, cover = it.coverUrl,
                        subtitle = it.note.ifBlank { it.heat },
                        episodes = it.episodeCount, playable = it.online,
                    )
                }, p.cursor, p.hasMore)
            }
            "actor" -> {
                val p = shortRank(ShortRankQuery(
                    content = "ranklist_celebrity",
                    board = "ranklist_series_actor",
                    cursor = useCursor(cursor)))
                OpenHongguoBrowsePage(p.items.map {
                    OpenHongguoBrowseItem(
                        id = it.userId, title = it.actor, cover = it.avatarUrl,
                        subtitle = listOf(it.score, it.intro).filter(String::isNotBlank)
                            .joinToString(" · "),
                        actorUserId = it.userId,
                    )
                }.filter { it.title.isNotBlank() }, p.cursor, p.hasMore)
            }
            "human", "comic", "ai" -> {
                val content = when (tab) {
                    "human" -> "human"
                    "comic" -> "comic_series_rank"
                    else -> "ai_playlet"
                }
                val p = shortRank(ShortRankQuery(content = content,
                    cursor = useCursor(cursor)))
                OpenHongguoBrowsePage(p.items.map {
                    OpenHongguoBrowseItem(it.seriesId, it.title, it.coverUrl,
                        it.score, it.episodeCount)
                }.filter { it.id.isNotBlank() }, p.cursor, p.hasMore)
            }
            "hot" -> {
                val p = hotSearch()
                val hits = p.guesses + p.boards.flatMap { it.items }
                val dramas = hits.distinctBy { it.seriesId }.map {
                    OpenHongguoBrowseItem(it.seriesId, it.title, it.coverUrl,
                        it.heat.ifBlank { it.searchScore }, it.episodeCount)
                }.filter { it.id.isNotBlank() }
                val topics = p.boards.flatMap { it.topics }.distinctBy { it.query }
                    .map { topic ->
                        OpenHongguoBrowseItem(id = "topic:${topic.id}",
                            title = topic.title, subtitle = topic.heat,
                            playable = false, searchQuery = topic.query)
                    }.filter { it.searchQuery.isNotBlank() }
                OpenHongguoBrowsePage(dramas + topics, "", false)
            }
            "filter", "filterComic", "filterAI" -> {
                val genre = when (tab) {
                    "filterComic" -> "comic_series"
                    "filterAI" -> "ai_series"
                    else -> "short_play"
                }
                val selections = selectedFilters + ("genre" to listOf(genre))
                // The SDK checks that the cursor continues this exact filter.
                val p = filter(ShortFilterQuery(
                    selections = selections, cursor = useCursor(cursor)))
                OpenHongguoBrowsePage(p.items.map {
                    OpenHongguoBrowseItem(it.id, it.title, it.coverUrl,
                        it.category, it.episodeCount)
                }, p.cursor, p.hasMore)
            }
            else -> throw IllegalArgumentException("Unknown discovery tab")
        }
    }

    /** Discovery/search pagers are bounded by the caller's page size. */
    fun discoverAll(options: DiscoverOptions = DiscoverOptions(), maxPages: Int = 5) =
        client.discoverAll(options, maxPages.coerceIn(1, 50))
    fun searchAll(query: String, options: SearchOptions = SearchOptions(),
        maxPages: Int = 5) =
        client.searchAll(query.take(120), options, maxPages.coerceIn(1, 50))

    /** Read-only episode metadata; existing player remains authoritative. */
    suspend fun series(id: String): Series = client.getSeries(checkedId(id))
    suspend fun episodes(
        id: String,
        options: dev.openhongguo.EpisodeOptions = dev.openhongguo.EpisodeOptions(),
    ): dev.openhongguo.Page<dev.openhongguo.Episode> =
        client.listEpisodes(checkedId(id), options)
    /** Playback model is only exposed to compatibility diagnostics; no DRM bypass. */
    suspend fun playbackModel(episodeId: String): dev.openhongguo.PlaybackResult =
        client.resolvePlayback(checkedId(episodeId))
    /** Explicit maintenance action; never called from autoplay or retry loops. */
    suspend fun resetAnonymousDeviceExplicitly() = client.resetDevice()

    suspend fun details(id: String): OpenHongguoMetadata =
        mapSeries(client.getSeries(checkedId(id)))

    suspend fun batchDetails(ids: List<String>): List<OpenHongguoMetadata> =
        client.getSeriesBatch(ids.distinct().take(10).map(::checkedId))
            .mapNotNull { it.series?.let(::mapSeries) }

    suspend fun seasons(id: String): List<Drama> =
        client.seasons(checkedId(id)).seasons.map {
            Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount)
        }.filter { it.id.isNotBlank() }

    suspend fun album(albumId: String): Album = client.getAlbum(checkedId(albumId))
    suspend fun related(id: String): List<Drama> =
        client.seriesRecommend(checkedId(id)).items.map(::toDrama)
            .distinctBy(Drama::id).take(40)
    suspend fun person(id: String): Person = client.person(checkedId(id))
    suspend fun personWorks(id: String, cursor: String = ""): WorkPage =
        client.personWorks(checkedId(id), cursor)
    suspend fun discover(options: DiscoverOptions = DiscoverOptions()): Page<SeriesSummary> =
        client.discover(options)
    suspend fun search(query: String, options: SearchOptions = SearchOptions()): Page<SeriesSummary> =
        client.search(query.take(120), options)
    suspend fun suggest(prefix: String): List<Suggestion> = client.suggest(prefix.take(80))
    suspend fun hotSearch(): HotSearch = client.hotSearch()
    suspend fun searchCues(): List<String> = client.searchCues()
    suspend fun newReleases(query: NewReleaseQuery = NewReleaseQuery()): NewReleasePage =
        client.newReleases(query)
    suspend fun shortRank(query: ShortRankQuery = ShortRankQuery()): ShortRanking =
        client.shortRank(query)
    suspend fun rankGroups(): List<RankList> = client.rankGroups()
    suspend fun rankRows(cellId: String, offset: Int = 0): Ranking =
        client.rankRows(cellId, offset)
    suspend fun ranking(activityId: String, rankListId: String): Ranking =
        client.ranking(activityId, rankListId)
    suspend fun filterPanel(genre: String): List<FilterRow> =
        client.shortFilterPanel(genre)
    suspend fun filter(query: ShortFilterQuery = ShortFilterQuery()): ShortFilterPage =
        client.shortFilter(query)
    suspend fun categories(categoryId: String): CategoryPage =
        client.categories(categoryId.trim().also { require(it.isNotEmpty()) })
    suspend fun categoryLanding(categoryId: String, options: LandingOptions = LandingOptions()):
        CategoryPage = client.categoryLanding(
            categoryId.trim().also { require(it.isNotEmpty()) }, options)

    /**
     * Reusable generic SDK explorer for real API results. Book categories/rank
     * rows are deliberately not relabeled as short dramas.
     */
    suspend fun browseExtended(
        surface: String, query: String, cursor: String = "",
    ): OpenHongguoBrowsePage {
        val next = cursor.takeIf(String::isNotBlank)
        return when (surface) {
            "discover" -> {
                val page = discover(DiscoverOptions(channel = query.takeIf(String::isNotBlank),
                    cursor = next))
                OpenHongguoBrowsePage(page.items.map {
                    OpenHongguoBrowseItem(it.id, it.title, it.coverUrl,
                        it.description, it.episodeCount)
                }, page.cursor, page.hasMore)
            }
            "search" -> {
                require(query.isNotBlank()) { "请输入搜索关键词" }
                val page = search(query, SearchOptions(cursor = next))
                OpenHongguoBrowsePage(page.items.map {
                    OpenHongguoBrowseItem(it.id, it.title, it.coverUrl,
                        it.description, it.episodeCount)
                }, page.cursor, page.hasMore)
            }
            "calendar" -> {
                require(query.matches(Regex("[0-9]{8}")))
                val page = newReleases(NewReleaseQuery(date = query, cursor = next))
                OpenHongguoBrowsePage(page.items.map {
                    OpenHongguoBrowseItem(it.seriesId, it.title, it.coverUrl,
                        it.note.ifBlank { it.heat }, it.episodeCount,
                        playable = it.online)
                }, page.cursor, page.hasMore)
            }
            "activity" -> {
                // Empty IDs preserve the SDK's optional default event path.
                // For a specific activity, require both source-provided IDs.
                val ids = query.trim().split(':', limit = 2)
                val board = if (query.isBlank()) "" to "" else {
                    require(ids.size == 2 && ids.all(String::isNotBlank)) {
                        "请输入 活动ID:榜单ID"
                    }
                    ids[0].trim() to ids[1].trim()
                }
                val ranking = ranking(board.first, board.second)
                OpenHongguoBrowsePage(ranking.items.map { it2 ->
                    OpenHongguoBrowseItem(
                        id = it2.seriesId.ifBlank { "activity:${it2.rank}" },
                        title = it2.title.ifBlank { it2.actor },
                        cover = it2.coverUrl,
                        subtitle = listOfNotNull(
                            it2.score.takeIf(String::isNotBlank),
                            it2.votes.takeIf { it > 0 }?.let { "${it} 票" }
                        ).joinToString(" · "),
                        episodes = it2.episodeCount,
                        playable = it2.seriesId.matches(Regex("[1-9][0-9]{0,29}"))
                    )
                }.filter { it.title.isNotBlank() }, "", false)
            }
            "rankGroups" -> {
                OpenHongguoBrowsePage(rankGroups().map {
                    OpenHongguoBrowseItem(it.id, it.name,
                        subtitle = "书城榜单 · ${it.size} 项", playable = false)
                }, "", false)
            }
            "rankRows" -> {
                val offset = cursor.toIntOrNull() ?: 0
                val page = rankRows(query, offset)
                OpenHongguoBrowsePage(page.items.map {
                    OpenHongguoBrowseItem(it.seriesId, it.title,
                        it.coverUrl, it.score, it.episodeCount,
                        playable = false)
                }, if (page.hasMore && page.nextOffset > offset)
                    page.nextOffset.toString() else "", page.hasMore)
            }
            "category" -> {
                val page = categoryLanding(query, LandingOptions(cursor = next))
                OpenHongguoBrowsePage(page.items.map {
                    OpenHongguoBrowseItem(it.id, it.title, it.coverUrl,
                        it.category, it.episodeCount, playable = false)
                }, if (page.hasMore && page.nextOffset > 0)
                    page.nextOffset.toString() else "", page.hasMore)
            }
            else -> throw IllegalArgumentException("Unknown SDK surface: $surface")
        }
    }

    private fun checkedId(id: String): String {
        require(id.matches(Regex("[1-9][0-9]{0,29}"))) { "Invalid public content ID" }
        return id
    }

    private fun toDrama(series: SeriesSummary): Drama =
        Drama(series.id, series.title, series.coverUrl,
            series.episodeCount, series.description)

    private fun mapSeries(series: Series): OpenHongguoMetadata {
        val rows = series.rows.map { OpenHongguoInfoRow(it.kind.value, it.text) }
        val tags = rows.filter { it.kind in setOf(3, 9, 40) }
            .flatMap { it.text.split('、', '，', ',', '/', '|') }
            .map(String::trim).filter(String::isNotBlank).distinct().take(16)
        val actors = rows.filter { it.kind == 33 }
            .flatMap { it.text.replace(Regex("^(?:演员|主演)[：:·\\s]*"), "")
                .split('、', '，', ',', ' ', '·') }
            .map(String::trim).filter(String::isNotBlank).distinct().take(20)
        val score = rows.firstOrNull { it.kind == 4 }?.text
            ?.takeIf { OpenHongguoMetadataParser.isRating(it) }
        val novel = series.novel?.takeIf { it.bookId.isNotBlank() }?.let {
            OpenHongguoNovel(it.bookId, it.title, it.coverUrl,
                when (it.status.value) { 0 -> "已完结"; 1 -> "连载中"; 3 -> "今日更新"; else -> "" },
                it.reads.takeIf { reads -> reads > 0 }, it.tags)
        }
        return OpenHongguoMetadata(
            seriesId = series.id, tags = tags, actors = actors,
            score = score, novel = novel,
            albumId = series.relatedAlbumId.takeIf(String::isNotBlank),
            season = series.seasonIndex.takeIf { it > 0 },
            statusText = rows.firstOrNull { it.kind == 11 }?.text,
            rows = rows,
            heat = series.heat.coerceAtLeast(0L),
            followCount = series.followCount.coerceAtLeast(0L),
            playCount = series.playCount.coerceAtLeast(0L),
        )
    }
}

data class OpenHongguoBrowseItem(
    val id: String,
    val title: String,
    val cover: String = "",
    val subtitle: String = "",
    val episodes: Int = 0,
    val playable: Boolean = true,
    val actorUserId: String = "",
    val searchQuery: String = "",
)

data class OpenHongguoBrowsePage(
    val items: List<OpenHongguoBrowseItem>,
    val cursor: String,
    val hasMore: Boolean,
)
