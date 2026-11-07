package com.hongguo.rewrite

import com.hongguotv.core.AppCommentPages
import com.hongguotv.core.PublicComments
import com.hongguotv.core.TimedComments
import org.json.JSONObject
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RecommendationFeed
import com.hongguotv.core.RecommendationCursor
import com.hongguotv.core.NoNewRecommendationsException
import com.hongguotv.core.Series
import com.hongguotv.core.FeedAccount
import com.hongguotv.core.DramaCategory
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.Rendition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Offline-server-free adapter for the GPL native core from
 * tsukiyo-zero/hongguoTV; the Compose UI knows only app models.
 *
 * All blocking I/O stays on Dispatchers.IO. The core owns anonymous
 * registration and bounded GET/POST retries. No WebView or local HTTP server.
 */
class NativeHongguoSource(
    private val repository: ContentRepository = ContentRepository(),
    private val diagnostics: DiagnosticLog? = null,
) {
    private val websiteCovers = NativeWebsiteSource(diagnostics = diagnostics)
    private val recommendationLock = Any()
    private var recommendationFeed = RecommendationFeed(repository.http)
    private var accountFeed: FeedAccount? = null

    /** Called only for a new catalog session; QR cookies never enter logs. */
    fun setAccountRecommendations(session: FeedAccount?) {
        synchronized(recommendationLock) { accountFeed = session }
    }
    private var recommendationBuffer = RecommendationAccumulator<Series> { it.id }
    private var recommendationOffset = 0
    private var recommendationFeedPage = 1
    private var recommendationPublicPage = 1
    private var recommendationUsePublic = false
    private var recommendationEnd = false
    /** Independent official one-drama-per-swipe cursor; never falls back to sorted web catalog. */
    private val reelsLock = Any()
    private var reelsCursor = RecommendationCursor()
    private var reelsFeed = RecommendationFeed(repository.http, cursor = reelsCursor)
    private var reelsPageNumber = 1
    private var realReelsCursor = RecommendationCursor()
    private var realReelsFeed = RecommendationFeed(repository.http, cursor = realReelsCursor,
        category = DramaCategory.REAL)
    private var realReelsPageNumber = 1
    private var realReelsEnd = false

    /** Classifies only explicit official type tokens; genre themes and titles are not evidence. */
    internal fun isExplicitReal(series: Series): Boolean =
        OfficialReelsType.isReal(series.tags)

    /** Dynamically paged official real-drama distribution feed. */
    private fun realCategoryPage(): com.hongguotv.core.CatalogPage {
        if (realReelsEnd) throw NoNewRecommendationsException()
        val page = realReelsFeed.page(realReelsPageNumber)
        realReelsPageNumber++
        if (!page.hasMore) realReelsEnd = true
        return page
    }

    suspend fun officialReelsPage(
        restart: Boolean = false,
        alreadyShown: Set<String> = emptySet(),
        realOnly: Boolean = false,
    ): CatalogPage = withContext(Dispatchers.IO) {
        synchronized(reelsLock) {
            if (restart) {
                reelsCursor = RecommendationCursor()
                reelsFeed = RecommendationFeed(repository.http,
                    account = synchronized(recommendationLock) { accountFeed },
                    cursor = reelsCursor)
                reelsPageNumber = 1
                realReelsCursor = RecommendationCursor()
                realReelsFeed = RecommendationFeed(repository.http,
                    cursor = realReelsCursor, category = DramaCategory.REAL)
                realReelsPageNumber = 1
                realReelsEnd = false
            }
            val excluded = alreadyShown.filterTo(linkedSetOf()) {
                it.matches(Regex("[0-9]{1,30}"))
            }
            // The server itself can deduplicate its official feed with the seen IDs.
            excluded.toList().takeLast(300).forEach(reelsCursor::markSeen)
            if (reelsPageNumber > 100) {
                reelsCursor = RecommendationCursor()
                reelsFeed = RecommendationFeed(repository.http,
                    account = synchronized(recommendationLock) { accountFeed },
                    cursor = reelsCursor)
                reelsPageNumber = 1
                excluded.toList().takeLast(300).forEach(reelsCursor::markSeen)
            }
            val candidates = mutableListOf<Series>()
            var upstreamMore = true
            var officialFailure: Exception? = null
            // Always try dynamic official recommendations first, including in real-only mode.
            // In real-only mode, admit exclusively explicit server-typed real dramas.
            repeat(if (realOnly) 2 else 3) {
                if (candidates.size >= 5 || !upstreamMore) return@repeat
                try {
                    val page = reelsFeed.page(reelsPageNumber)
                    reelsPageNumber++
                    upstreamMore = page.hasMore
                    candidates += page.items.filter { it.id !in excluded &&
                        (!realOnly || isExplicitReal(it)) }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    officialFailure = failure
                    upstreamMore = false
                }
            }
            var realCategoryFailure: Exception? = null
            if (realOnly && candidates.size < 5 && !realReelsEnd) {
                // Official genre selector is authoritative when general recommendation
                // cards omit their content type. It also has its own changing cursor.
                repeat(2) {
                    if (candidates.size >= 5 || realReelsEnd) return@repeat
                    try {
                        val page = realCategoryPage()
                        candidates += page.items.filter { it.id !in excluded }
                        diagnostics?.record("REELS", "REAL_CATEGORY_FALLBACK")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        realCategoryFailure = failure
                        // Keep the same cursor for manual retry; no invented fixed list.
                        return@repeat
                    }
                }
            }
            val unique = candidates.distinctBy { it.id }
            if (unique.isEmpty()) {
                throw realCategoryFailure ?: officialFailure ?: NoNewRecommendationsException()
            }
            diagnostics?.record("REELS",
                if (realOnly) "REAL_DYNAMIC_BATCH" else "OFFICIAL_DYNAMIC_BATCH")
            CatalogPage(unique.map { it.toDrama() },
                hasMore = if (realOnly) (upstreamMore || !realReelsEnd) else upstreamMore,
                nextOffset = reelsPageNumber)
        }
    }

    private val episodeComments by lazy { AppCommentPages() }
    private val publicComments by lazy { PublicComments(repository.http) }
    private val timedComments by lazy { TimedComments() }
    private val discoveryTopics = mutableMapOf<String, List<FilterOption>>()
    private val nativeFilterGroups = mutableMapOf<String, List<FilterGroup>>()
    private val filterCursors = mutableMapOf<String, MutableMap<Int, DiscoveryCursor>>()

    companion object {
        const val PAGE_SIZE = 24

        fun boardFor(value: String): RankBoard = when (value) {
            "real" -> RankBoard.REAL
            "comic" -> RankBoard.COMIC
            "ai" -> RankBoard.AI
            else -> RankBoard.ALL
        }

        fun categoryFor(genre: String): DramaCategory = when (genre) {
            "comic_series" -> DramaCategory.COMIC
            "ai_series" -> DramaCategory.AI
            else -> DramaCategory.REAL
        }

        private val filterKeys = mapOf(
            "theme" to "category_dim_theme",
            "setting" to "category_dim_role",
            "background" to "category_dim_epoch",
            "days" to "online_time",
            "status" to "creation_status",
        )

        /** Match the website's first-party landpage selector values. */
        fun selectionFor(
            genre: String, active: Map<String, String> = emptyMap(),
            latest: Boolean = false,
        ): DiscoverySelection {
            val values = linkedMapOf(
                "genre" to genre,
                "sort" to "online_time",
            )
            if (latest && genre != "short_play") values["online_time"] = "days_7"
            for ((key, value) in active) {
                if (value.isBlank()) continue
                val upstream = filterKeys[key] ?: key
                if (upstream in DiscoverySelection.labels) values[upstream] = value
            }
            return DiscoverySelection(values)
        }
    }

    /** Official website autocomplete; no login or device registration. */
    suspend fun suggestions(prefix: String): List<String> = withContext(Dispatchers.IO) {
        repository.searchSuggestions(prefix.trim().take(80))
    }

    /** Build a small three-drama UI batch from anonymous feed pages.
     * Runs on Dispatchers.IO; one lock protects this cursor across calls.
     * Extra results from public catalog pages stay queued for later scrolling.
     */
    private fun recommendationBatch(offset: Int): CatalogPage =
        synchronized(recommendationLock) {
            require(offset >= 0)
            if (offset == 0) {
                recommendationFeed = RecommendationFeed(repository.http,
                    account = accountFeed)
                recommendationBuffer = RecommendationAccumulator { it.id }
                recommendationOffset = 0
                recommendationFeedPage = 1
                recommendationPublicPage = 1
                recommendationUsePublic = false
                recommendationEnd = false
            } else if (offset != recommendationOffset) {
                throw IOException("RECOMMEND_CURSOR_EXPIRED")
            }
            var attempts = 0
            var officialAttempts = 0
            while (recommendationBuffer.available < RecommendationLoadingPolicy.BATCH_SIZE &&
                !recommendationEnd && attempts < RecommendationLoadingPolicy.MAX_TOTAL_REQUESTS
            ) {
                attempts++
                if (!recommendationUsePublic &&
                    officialAttempts >= RecommendationLoadingPolicy.MAX_OFFICIAL_REQUESTS
                ) {
                    recommendationUsePublic = true
                    diagnostics?.record("FEED", "CATALOG_PUBLIC_TOP_UP")
                }
                try {
                    if (recommendationUsePublic) {
                        val page = recommendationPublicPage++
                        if (page > 100) {
                            recommendationEnd = true
                            break
                        }
                        val response = repository.home(page)
                        val added = recommendationBuffer.add(response.items)
                        if (!response.hasMore || added == 0) recommendationEnd = true
                        diagnostics?.record("FEED", "PUBLIC_BATCH")
                    } else {
                        officialAttempts++
                        val response = recommendationFeed.page(recommendationFeedPage++)
                        val added = recommendationBuffer.add(response.items)
                        if (response.items.size <= 3)
                            diagnostics?.record("FEED", "SMALL_BATCH")
                        if (!response.hasMore || added == 0) {
                            recommendationUsePublic = true
                            diagnostics?.record("FEED", "CATALOG_PUBLIC_TOP_UP")
                        }
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (!recommendationUsePublic) {
                        recommendationUsePublic = true
                        diagnostics?.record("FEED", "CATALOG_PUBLIC_FALLBACK")
                    } else {
                        recommendationEnd = true
                        diagnostics?.record("FEED", "CATALOG_SOURCES_UNAVAILABLE")
                        if (recommendationBuffer.available == 0) throw error
                    }
                }
            }
            val rows = recommendationBuffer.take(RecommendationLoadingPolicy.BATCH_SIZE)
            recommendationOffset = offset + rows.size
            CatalogPage(
                items = rows.map { it.toDrama() },
                hasMore = rows.isNotEmpty() &&
                    (recommendationBuffer.available > 0 || !recommendationEnd),
                nextOffset = recommendationOffset,
            )
        }

    suspend fun list(
        section: Section, offset: Int, genre: String, board: String,
        query: String, filters: Map<String, String>,
    ): CatalogPage = withContext(Dispatchers.IO) {
        if (section == Section.RECOMMEND) return@withContext recommendationBatch(offset)
        val page = (offset / PAGE_SIZE + 1).coerceIn(1, 100)
        // Unlike a generic /category route, the landpage selector explicitly
        // sorts by online_time, so the "最新" tab does not silently show random
        // category items. Its continuation cursor is bound to the selection.
        if (section == Section.LATEST) {
            return@withContext filteredPage(genre, emptyMap(), page, latest = true)
        }
        if (section == Section.BROWSE) {
            try {
                return@withContext filteredPage(genre, filters, page, latest = false)
            } catch (problem: IOException) {
                // Website categories are a fallback only when no advanced
                // filters were requested. Never pretend to have applied them.
                if (filters.keys.any { it != "topic" }) throw problem
                diagnostics?.record("BROWSE", "WEBSITE_FALLBACK")
            }
        }
        val origin = when (section) {
            Section.RECOMMEND -> error("handled above")
            Section.RANK -> repository.rankPage(boardFor(board), page)
            Section.BROWSE -> repository.discover(
                categoryFor(genre), filters["topic"].orEmpty(), page)
            Section.LATEST -> error("handled above")
            Section.SEARCH -> {
                if (query.isBlank()) return@withContext CatalogPage(emptyList(), false, 0)
                // The public cross-category endpoint does not need a registered device.
                repository.searchAll(query.trim().take(80), page)
            }
        }
        if (section == Section.BROWSE) {
            origin.discovery?.topics?.let { topics ->
                synchronized(discoveryTopics) {
                    discoveryTopics[genre] = topics.map { FilterOption(it.id, it.label) }
                }
            }
        }
        CatalogPage(
            items = origin.items.map { it.toDrama() },
            hasMore = origin.hasMore,
            nextOffset = page * PAGE_SIZE,
        )
    }

    /** Keep cursor state for a single native category/selection.
     * A refresh starts a new session, never reuses an old offset token.
     */
    private fun filteredPage(
        genre: String, active: Map<String, String>, page: Int, latest: Boolean,
    ): CatalogPage {
        val selection = selectionFor(genre, active, latest)
        val key = (if (latest) "LATEST:" else "BROWSE:") +
            genre + ":" + selection.values.toSortedMap().toString()
        val cursor = if (page <= 1) null else synchronized(filterCursors) {
            filterCursors[key]?.get(page)
        } ?: if (page > 1) throw IOException("FILTER_CURSOR_EXPIRED") else null
        val result = repository.filteredDiscovery(selection, cursor)
        if (page == 1) {
            synchronized(filterCursors) {
                filterCursors[key] = mutableMapOf()
                while (filterCursors.size > 20) filterCursors.remove(filterCursors.keys.first())
            }
            if (!latest) synchronized(nativeFilterGroups) {
                nativeFilterGroups[genre] = mapGroups(result.groups)
            }
        }
        result.next?.let { next ->
            synchronized(filterCursors) { filterCursors[key]?.set(page + 1, next) }
        }
        return CatalogPage(result.items.map { it.toDrama() },
            result.next != null, page * PAGE_SIZE)
    }

    suspend fun filters(genre: String): List<FilterGroup> = withContext(Dispatchers.IO) {
        synchronized(nativeFilterGroups) { nativeFilterGroups[genre] }?.let {
            return@withContext it
        }
        // The native response provides all official categories, not merely
        // the single "topic" option exposed by the SSR website.
        val fromApi = runCatching {
            repository.filteredDiscovery(selectionFor(genre)).groups
        }.getOrNull()
        if (fromApi != null) {
            val mapped = mapGroups(fromApi)
            synchronized(nativeFilterGroups) { nativeFilterGroups[genre] = mapped }
            return@withContext mapped
        }
        val topics = synchronized(discoveryTopics) { discoveryTopics[genre] }
            ?: repository.discover(categoryFor(genre)).discovery?.topics
                ?.map { FilterOption(it.id, it.label) }.orEmpty()
        synchronized(discoveryTopics) { discoveryTopics[genre] = topics }
        listOf(FilterGroup("topic", "题材", listOf(FilterOption("", "全部")) + topics))
    }

    private fun mapGroups(groups: List<DiscoveryGroup>): List<FilterGroup> =
        groups.filter { it.type != "genre" }.map { group ->
            val key = when (group.type) {
                "category_dim_theme" -> "theme"
                "category_dim_role" -> "setting"
                "category_dim_epoch" -> "background"
                "online_time" -> "days"
                "creation_status" -> "status"
                else -> group.type
            }
            FilterGroup(key, group.label, listOf(FilterOption("", "全部")) +
                group.options.map { FilterOption(it.id, it.label) })
        }

    /**
     * Source-separated requests allow the UI to show website counters as soon
     * as the website responds; a slow app registration never blocks that.
     */
    suspend fun websiteSocial(id: String): SocialStats = websiteCovers.socialStats(id)

    suspend fun websiteLabels(id: String): DramaLabels = websiteCovers.labels(id)

    suspend fun appSocial(id: String): SocialStats = withContext(Dispatchers.IO) {
        SocialStatsParser.application(repository.socialDetail(id), id)
    }

    /** Signed first-party related-work cells; upcoming seasons never playable. */
    suspend fun related(id: String): List<RelatedDrama> = withContext(Dispatchers.IO) {
        repository.related(id).map {
            RelatedDrama(it.series.toDrama(), it.label, it.upcoming)
        }
    }

    suspend fun detail(id: String): DramaDetail = withContext(Dispatchers.IO) {
        val detail = repository.detail(id)
        DramaDetail(detail.series.toDrama(), detail.episodes.mapIndexed { i, vid ->
            Episode(i + 1, vid)
        })
    }

    /**
     * Open the actual MP4 range stream, rather than fabricating a playback URL.
     * Decryption is performed by the GPL core, and Media3 reads via NativeVideoDataSource.
     *
     * Prefer an Android-compatible AVC rendition, falling back to HEVC only
     * when available; VVC/bytevc2 is never sent to the decoder.
     */
    suspend fun openVideo(
        vid: String, nextVid: String? = null, qualityLimit: Int = 0
    ): RemoteVideo =
        withContext(Dispatchers.IO) {
        diagnostics?.record("PLAYBACK", "REGISTER_AND_MODEL_START")
        val started = System.nanoTime()
        val ladder = try {
            // Retrieve the next episode's metadata within the same bounded
            // playback request; the upstream core retains it briefly for
            // handoff without starting a second anonymous registration.
            val ahead = nextVid?.takeIf {
                it != vid && it.matches(Regex("[0-9]{1,30}"))
            }?.let { listOf(it) }.orEmpty()
            repository.streamLadder(vid, metadataAhead = ahead)
        } catch (error: Exception) {
            val code = PlaybackProblem.describe("播放模型", error).substringBefore(" · ")
            diagnostics?.record("PLAYBACK", "MODEL_FAIL_" + code,
                (System.nanoTime() - started) / 1_000_000)
            throw error
        }
        diagnostics?.record("PLAYBACK", "MODEL_READY",
            (System.nanoTime() - started) / 1_000_000)
        val eligible = ladder.renditions.filter(::canDecode)
        if (eligible.isEmpty()) {
            diagnostics?.record("PLAYBACK", "CODEC_UNSUPPORTED")
            throw IOException("CODEC_NOT_SUPPORTED")
        }
        val candidates = eligible.sortedWith(
            compareBy<Rendition> { QualityPolicy.rank(it.quality, qualityLimit) }
                .thenByDescending { codecName(it.codec) == "video/avc" }
        ).take(4)
        var last: IOException? = null
        for (candidate in candidates) {
            val video = RemoteVideo(repository.http, candidate.toStreamInfo())
            try {
                video.prepare(initialBytes = 64 * 1024)
                diagnostics?.record("PLAYBACK", "RANGE_PREPARED")
                return@withContext video
            } catch (error: IOException) {
                video.close()
                last = error
                diagnostics?.record("PLAYBACK", "RANGE_FAIL")
            }
        }
        throw last ?: IOException("MEDIA_PREPARATION_FAILED")
    }

    /** First-party read-only comment pages; website public reviews are
     * an explicit limited fallback if the anonymous app API fails.
     */
    suspend fun comments(series: String, vid: String, cursor: String = ""): CommentsPage =
        withContext(Dispatchers.IO) {
            val result: JSONObject
            var limited = false
            if (cursor.isBlank()) {
                result = try {
                    episodeComments.read(series, vid, "")
                } catch (failure: Exception) {
                    diagnostics?.record("COMMENTS", "APP_FALLBACK")
                    limited = true
                    publicComments.read(series, vid).getJSONObject("comments")
                }
            } else {
                result = episodeComments.read(series, vid, cursor)
            }
            val rows = result.optJSONArray("items")
                ?: throw IOException("COMMENTS_INVALID")
            val items = (0 until minOf(rows.length(), 100)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id")
                val text = row.optString("content").trim().take(2000)
                if (id.isBlank() || text.isBlank()) return@mapNotNull null
                CommentEntry(id, row.optString("author").ifBlank { "匿名用户" }.take(80),
                    text, row.optLong("likes", 0).coerceAtLeast(0),
                    row.optLong("created", 0).coerceAtLeast(0),
                    row.optString("avatar").take(4096))
            }.distinctBy { it.id }
            val total = result.optLong("total", -1L).takeIf { it >= 0L }
            CommentsPage(items, if (limited) "" else result.optString("nextCursor"),
                !limited && result.optBoolean("hasMore"), limited, total)
        }

    /** Timestamped danmaku for current episode, never fabricated locally. */
    suspend fun danmaku(series: String, vid: String, startMs: Long, durationMs: Long): DanmakuPage =
        withContext(Dispatchers.IO) {
            val result = timedComments.read(series, vid, startMs, durationMs)
            val rows = result.getJSONArray("items")
            val items = (0 until minOf(rows.length(), 100)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id")
                val content = row.optString("text").trim().take(180)
                val time = row.optLong("timeMS", -1)
                if (id.isBlank() || content.isBlank() || time < 0) null
                else DanmakuEntry(id, content, time)
            }.distinctBy { it.id }
            DanmakuPage(items, result.getLong("nextMS"))
        }

    fun coverUrl(image: String) = websiteCovers.coverUrl(image)

    fun resolveShare(message: String): Pair<String, Int> {
        // Accept a first-party permalink only. Short links and other domains
        // require a separate, explicitly verified resolver.
        val match = Regex(
            """https?://(?:www\.)?hongguoduanju\.com/(?:player|detail)/([0-9]{1,30})(?:/([0-9]{1,30}))?"""
        ).find(message) ?: throw IOException("SHARE_LINK_NOT_SUPPORTED")
        return match.groupValues[1] to
            (match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
    }

    private fun com.hongguotv.core.Series.toDrama(): Drama {
        val episodes = Regex("[0-9]+").find(badge)?.value?.toIntOrNull() ?: 0
        return Drama(id = id, title = title, cover = cover,
            total = episodes, synopsis = description,
            completionStatus = DramaCompletion.fromOfficialText(badge),
            categories = DramaTagParser.categoriesFromCore(tags))
    }

    private fun canDecode(row: Rendition): Boolean {
        val mime = codecName(row.codec) ?: return false
        return android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            .codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) }
            }
    }

    private fun codecName(codec: String): String? = when (codec.lowercase()) {
        "h264", "avc", "avc1", "avc3", "bytevc0" -> "video/avc"
        "h265", "hevc", "hvc1", "hev1", "bytevc1" -> "video/hevc"
        else -> null
    }
}
