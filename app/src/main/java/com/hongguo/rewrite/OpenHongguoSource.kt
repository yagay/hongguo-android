package com.hongguo.rewrite

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
 * Isolated optional SDK bridge; does not replace the existing streaming,
 * login, recommendations, or encrypted Media3 playback stack.
 *
 * Apache-2.0 SDK 0.2.0 is lazy-created only on explicit read-only discovery or
 * metadata operations. Do not reset a user's anonymous session on media errors.
 */
internal class OpenHongguoSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val client: OpenhongguoClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OpenhongguoClient.create(applicationContext,
            ClientOptions(retryAttempts = 1, deviceResets = 0))
    }

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
        client.rankRows(checkedId(cellId), offset)
    suspend fun ranking(activityId: String, rankListId: String): Ranking =
        client.ranking(activityId, rankListId)
    suspend fun filterPanel(genre: String): List<FilterRow> =
        client.shortFilterPanel(genre)
    suspend fun filter(query: ShortFilterQuery = ShortFilterQuery()): ShortFilterPage =
        client.shortFilter(query)
    suspend fun categories(categoryId: String): CategoryPage =
        client.categories(checkedId(categoryId))
    suspend fun categoryLanding(categoryId: String, options: LandingOptions = LandingOptions()):
        CategoryPage = client.categoryLanding(checkedId(categoryId), options)

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
        )
    }
}
