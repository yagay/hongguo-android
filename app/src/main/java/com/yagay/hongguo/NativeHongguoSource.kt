package com.yagay.hongguo

import kotlinx.coroutines.sync.withLock

import com.hongguotv.core.AppCommentPages
import com.hongguotv.core.PublicComments
import com.hongguotv.core.TimedComments
import org.json.JSONObject
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RecommendationFeed
import com.hongguotv.core.PlaybackDeviceSession
import com.hongguotv.core.FeedResponseKind
import com.hongguotv.core.FeedResponseSummary
import com.hongguotv.core.PersonalizationEvidence
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
import com.hongguotv.core.StreamLadder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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
    private val recommendationDeviceSession: PlaybackDeviceSession = PlaybackDeviceSession.shared,
) {
    private val websiteCovers = NativeWebsiteSource(diagnostics = diagnostics)
    // Only host-level startup timings are retained (no signed URL, key,
    // cookies, or permanent blacklists).
    private val cdnHealth = CdnStartupHealth()
    // Media3 source preparation is now scheduled by NativeStreamPreloader.
    private val recommendationLock = Any()
    private var recommendationFeed = RecommendationFeed(repository.http)
    private var accountFeed: FeedAccount? = null

    /** Safe request diagnostics contain route, code and counts only, never IDs or credentials. */
    private fun recordReelResponse(summary: FeedResponseSummary) {
        val route = when (summary.kind) {
            FeedResponseKind.DYNAMIC_TAB -> "TAB"
            FeedResponseKind.DYNAMIC_CELL -> "CELL"
            FeedResponseKind.CATEGORY -> "CATEGORY"
        }
        val identity = if (summary.hadAccountCookie) "COOKIE" else "GUEST"
        val signing = if (summary.signedDeviceIdentity) "SIGNED" else "UNSIGNED"
        val outcome = if (summary.businessCode == 0) "OK"
            else "BUSINESS_" + summary.businessCode.coerceIn(-1, 9999)
        diagnostics?.record("REELS_WIRE", "${route}_${identity}_${signing}_${outcome}")
        val flag = when (summary.personalizationFlag) {
            PersonalizationEvidence.SERVER_ENABLED -> "CANDIDATE_TRUE"
            PersonalizationEvidence.SERVER_DISABLED -> "CANDIDATE_FALSE"
            PersonalizationEvidence.UNDECLARED -> "NO_CANDIDATE"
        }
        diagnostics?.record("REELS_WIRE",
            "${route}_ITEMS_${summary.itemCount.coerceIn(0,999)}_${flag}" +
                if (summary.serverSessionPresent) "_SESSION" else "_NOSESSION")
        if (summary.freshCount >= 0) {
            diagnostics?.record("REELS_WIRE",
                "${route}_FRESH_${summary.freshCount.coerceIn(0,999)}")
        }
        if (summary.tabOnlyRecovery) {
            diagnostics?.record("REELS_WIRE", "TAB_RECOVERY_AFTER_CELL_DUPLICATES")
        }
    }

    private fun reelFeed(
        account: FeedAccount?, cursor: RecommendationCursor,
        category: DramaCategory? = null,
    ): RecommendationFeed = RecommendationFeed(repository.http,
        account = account, cursor = cursor, category = category,
        // Use the same registered device and dedicated recommendation signer
        // as the upstream 7.4.3.32 official-feed integration. Never attach
        // this anonymous registration identity to category selectors.
        // Code 110 falls back to the account Cookie without losing login.
        playbackSession = if (account != null && category == null)
            recommendationDeviceSession else null,
        onSummary = ::recordReelResponse)

    /** Called only for a new catalog session; QR cookies never enter logs. */
    fun setAccountRecommendations(session: FeedAccount?) {
        synchronized(recommendationLock) {
            accountFeed = session
        }
    }
    private var recommendationBuffer = RecommendationAccumulator<Series> { it.id }
    private var recommendationOffset = 0
    private var recommendationFeedPage = 1
    private var recommendationPublicPage = 1
    private var recommendationUsePublic = false
    private var recommendationEnd = false
    /** Independent official one-drama-per-swipe cursor; never falls back to sorted web catalog. */
    private val reelsLock = kotlinx.coroutines.sync.Mutex()
    private var reelsCursor = RecommendationCursor()
    private var reelsFeed = reelFeed(null, reelsCursor)
    private var reelsPageNumber = 1
    // Exhaustion is scoped only to a live swipe cursor. Every explicit new
    // session retries the official dynamic stream before category fallback.
    private var mainReelsExhausted = false
    /** A saturated guest feed must not suppress the first new account request. */
    private var reelsAccountSession: FeedAccount? = null
    private var realReelsCursor = RecommendationCursor()
    private var realReelsFeed = reelFeed(null, realReelsCursor, DramaCategory.REAL)
    private var realReelsPageNumber = 1
    private var realReelsEnd = false
    private var comicReelsCursor = RecommendationCursor()
    private var comicReelsFeed = reelFeed(null, comicReelsCursor, DramaCategory.COMIC)
    private var comicReelsPageNumber = 1
    private var comicReelsEnd = false

    private fun comicCategoryPage(): com.hongguotv.core.CatalogPage {
        if (comicReelsEnd) throw NoNewRecommendationsException()
        val page = comicReelsFeed.page(comicReelsPageNumber)
        comicReelsPageNumber++
        if (!page.hasMore) comicReelsEnd = true
        return page
    }

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
        comicOnly: Boolean = false,
        firstItemOnly: Boolean = false,
        forceFresh: Boolean = false,
    ): CatalogPage = withContext(Dispatchers.IO) {
        // First screen must not wait for several feed pages before showing
        // the first drama. Subsequent pages are fetched after UI handoff.
        reelsLock.withLock {
            // Account verification may complete while the player is already open.
            // Finale/queued swipe continuation can request restart=false; never
            // let that reuse a previously initialized guest feed after login.
            // This check is under reelsLock, so all three cursors are rebound
            // atomically to one identity without mixing account and guest rows.
            val accountForSession = synchronized(recommendationLock) { accountFeed }
            val contextChanged = accountForSession != reelsAccountSession
            if (restart || contextChanged) {
                if (contextChanged) {
                    diagnostics?.record("REELS", "ACCOUNT_CONTEXT_CURSOR_RESET")
                    if (!restart)
                        diagnostics?.record("REELS", "ACCOUNT_LATE_BIND_REBUILT")
                }
                mainReelsExhausted = false
                diagnostics?.record("REELS", "MAIN_DYNAMIC_FRESH_SESSION")
                reelsAccountSession = accountForSession
                diagnostics?.record("REELS", if (accountForSession == null)
                    "ANONYMOUS_DYNAMIC_CONTEXT" else "ACCOUNT_DYNAMIC_SIGNATURE_ATTEMPT")
                reelsCursor = RecommendationCursor()
                reelsFeed = reelFeed(accountForSession, reelsCursor)
                reelsPageNumber = 1
                // A duplicate-only response from the previous session must
                // never bypass the official dynamic feed when the user returns
                // or explicitly refreshes. Keep seen IDs separate for UI dedup.
                if (forceFresh) diagnostics?.record("REELS", "FORCE_FRESH_CURSOR")
                realReelsCursor = RecommendationCursor()
                realReelsFeed = reelFeed(accountForSession, realReelsCursor, DramaCategory.REAL)
                // This confirms only the request's authenticated/anonymous
                // identity. It does not prove the server personalized its rank.
                diagnostics?.record("REELS", if (accountForSession != null)
                    "ACCOUNT_REQUEST_MODE" else "ANONYMOUS_REQUEST_MODE")
                realReelsPageNumber = 1
                realReelsEnd = false
                comicReelsCursor = RecommendationCursor()
                comicReelsFeed = reelFeed(accountForSession, comicReelsCursor, DramaCategory.COMIC)
                comicReelsPageNumber = 1
                comicReelsEnd = false
            }
            val excluded = alreadyShown.filterTo(linkedSetOf()) {
                it.matches(Regex("[0-9]{1,30}"))
            }
            // The server itself can deduplicate its official feed with the seen IDs.
            excluded.toList().takeLast(300).forEach(reelsCursor::markSeen)
            if (reelsPageNumber > 100) {
                reelsCursor = RecommendationCursor()
                reelsFeed = reelFeed(reelsAccountSession, reelsCursor)
                reelsPageNumber = 1
                excluded.toList().takeLast(300).forEach(reelsCursor::markSeen)
            }
            val candidates = mutableListOf<Series>()
            val sources = linkedMapOf<String, ReelItemAttribution>()
            val accountMode = reelsAccountSession != null
            val dynamicOrigin = if (accountMode) ReelRecommendationOrigin.ACCOUNT_DYNAMIC
                else ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_DYNAMIC
            val categoryOrigin = if (accountMode) ReelRecommendationOrigin.ACCOUNT_CATEGORY
                else ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_CATEGORY
            val dynamicEvidence = mutableSetOf<PersonalizationEvidence>()
            var upstreamMore = true
            var officialFailure: Exception? = null
            // Comic uses its own official category cursor rather than mixing
            // ordinary or real-person recommendations into the comic tab.
            if (comicOnly) {
                val comicItems = mutableListOf<Series>()
                var failure: Exception? = null
                repeat(if (firstItemOnly) 1 else 3) {
                    if (comicItems.size >= (if (firstItemOnly) 1 else 5) ||
                        comicReelsEnd) return@repeat
                    try {
                        val page = comicCategoryPage()
                        val received = page.items.filter { it.id !in excluded }
                        comicItems += received
                        received.forEach { sources.putIfAbsent(it.id,
                            ReelItemAttribution(categoryOrigin)) }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        failure = error
                        if (error is NoNewRecommendationsException) comicReelsEnd = true
                        diagnostics?.record("REELS",
                            "COMIC_" + OfficialReelsFailure.code(error))
                        return@repeat
                    }
                }
                val unique = comicItems.distinctBy { it.id }
                if (unique.isEmpty())
                    throw failure ?: NoNewRecommendationsException()
                diagnostics?.record("REELS", "COMIC_DYNAMIC_BATCH")
                return@withLock CatalogPage(unique.map { it.toDrama() },
                    hasMore = !comicReelsEnd, nextOffset = comicReelsPageNumber,
                    categoryFallback = true,
                    itemAttributions = sources.filterKeys { id -> unique.any { it.id == id } })
            }
            // Always try dynamic official recommendations first, including in real-only mode.
            // In real-only mode, admit exclusively explicit server-typed real dramas.
            repeat(if (mainReelsExhausted) 0 else if (firstItemOnly) 1 else if (realOnly) 2 else 3) {
                if (candidates.size >= (if (firstItemOnly) 1 else 5) ||
                    !upstreamMore) return@repeat
                try {
                    val page = reelsFeed.page(reelsPageNumber)
                    reelsPageNumber++
                    dynamicEvidence += page.personalizationEvidence
                    upstreamMore = page.hasMore
                    val received = page.items.filter { it.id !in excluded &&
                        (!realOnly || isExplicitReal(it)) }
                    candidates += received
                    received.forEach { sources.putIfAbsent(it.id,
                        ReelItemAttribution(dynamicOrigin, page.personalizationEvidence)) }
                    diagnostics?.record("REELS", if (accountMode)
                        "ACCOUNT_DYNAMIC_ITEMS_" + received.size else
                        "ANONYMOUS_DYNAMIC_ITEMS_" + received.size)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    officialFailure = failure
                    diagnostics?.record("REELS", "MAIN_" + OfficialReelsFailure.code(failure))
                    if (failure is NoNewRecommendationsException) {
                        mainReelsExhausted = true
                    }
                    upstreamMore = false
                }
            }
            // If all displayable cards come from a category selector, the signed-in
            // cookie cannot establish that any shown item was personalized.
            val dynamicCandidateCount = candidates.size
            var realCategoryFailure: Exception? = null
            if (realOnly &&
                candidates.size < (if (firstItemOnly) 1 else 5) && !realReelsEnd) {
                // Official genre selector is authoritative when general recommendation
                // cards omit their content type. It also has its own changing cursor.
                repeat(if (firstItemOnly) 1 else 2) {
                    if (candidates.size >= (if (firstItemOnly) 1 else 5) ||
                        realReelsEnd) return@repeat
                    try {
                        val page = realCategoryPage()
                        val received = page.items.filter { it.id !in excluded }
                        candidates += received
                        received.forEach { sources.putIfAbsent(it.id,
                            ReelItemAttribution(categoryOrigin)) }
                        diagnostics?.record("REELS", "REAL_CATEGORY_FALLBACK")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        realCategoryFailure = failure
                        if (failure is NoNewRecommendationsException) realReelsEnd = true
                        diagnostics?.record("REELS", "REAL_" + OfficialReelsFailure.code(failure))
                        // Keep the same cursor for manual retry; no invented fixed list.
                        return@repeat
                    }
                }
            }
            // The official general tab can saturate after returning the same
            // video IDs across its hosts. Continue the SAME live swipe session
            // using independent first-party real/comic category cursors.
            // This does not substitute the homepage's sorted public catalog.
            var categoryFailure: Exception? = null
            if (!realOnly && candidates.isEmpty()) {
                for (type in listOf(DramaCategory.REAL, DramaCategory.COMIC)) {
                    if (firstItemOnly && candidates.isNotEmpty()) break
                    if (type == DramaCategory.REAL && realReelsEnd) continue
                    if (type == DramaCategory.COMIC && comicReelsEnd) continue
                    try {
                        val page = if (type == DramaCategory.REAL) realCategoryPage()
                            else comicCategoryPage()
                        val received = page.items.filter { it.id !in excluded }
                        candidates += received
                        received.forEach { sources.putIfAbsent(it.id,
                            ReelItemAttribution(categoryOrigin)) }
                        diagnostics?.record("REELS",
                            if (type == DramaCategory.REAL)
                                "GENERAL_REAL_FALLBACK" else "GENERAL_COMIC_FALLBACK")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        categoryFailure = failure
                        if (failure is NoNewRecommendationsException) {
                            if (type == DramaCategory.REAL) realReelsEnd = true
                            else comicReelsEnd = true
                        }
                        diagnostics?.record("REELS",
                            if (type == DramaCategory.REAL)
                                "GENERAL_REAL_" + OfficialReelsFailure.code(failure)
                            else "GENERAL_COMIC_" + OfficialReelsFailure.code(failure))
                    }
                }
            }
            val unique = candidates.distinctBy { it.id }
            if (unique.isEmpty())
                throw categoryFailure ?: realCategoryFailure ?: officialFailure
                    ?: NoNewRecommendationsException()
            diagnostics?.record("REELS",
                when {
                    realOnly -> "REAL_DYNAMIC_BATCH"
                    mainReelsExhausted -> "GENERAL_CATEGORY_DYNAMIC_BATCH"
                    else -> "OFFICIAL_DYNAMIC_BATCH"
                })
            val containsCategory = unique.any { sources[it.id]?.origin == categoryOrigin }
            val evidence = when {
                dynamicCandidateCount == 0 || containsCategory -> PersonalizationEvidence.UNDECLARED
                PersonalizationEvidence.SERVER_ENABLED in dynamicEvidence &&
                    PersonalizationEvidence.SERVER_DISABLED in dynamicEvidence ->
                    PersonalizationEvidence.UNDECLARED
                PersonalizationEvidence.SERVER_ENABLED in dynamicEvidence ->
                    PersonalizationEvidence.SERVER_ENABLED
                PersonalizationEvidence.SERVER_DISABLED in dynamicEvidence ->
                    PersonalizationEvidence.SERVER_DISABLED
                else -> PersonalizationEvidence.UNDECLARED
            }
            diagnostics?.record("REELS", when {
                dynamicCandidateCount == 0 -> "ACCOUNT_OR_ANON_CATEGORY_FALLBACK"
                evidence == PersonalizationEvidence.SERVER_ENABLED -> "SERVER_PERSONALIZATION_ENABLED"
                evidence == PersonalizationEvidence.SERVER_DISABLED -> "SERVER_PERSONALIZATION_DISABLED"
                else -> "SERVER_PERSONALIZATION_UNDECLARED"
            })
            CatalogPage(unique.map { it.toDrama() },
                personalizationEvidence = evidence,
                categoryFallback = dynamicCandidateCount == 0,
                itemAttributions = sources.filterKeys { id -> unique.any { it.id == id } },
                hasMore = when {
                    realOnly -> (!mainReelsExhausted && upstreamMore) || !realReelsEnd
                    mainReelsExhausted -> !realReelsEnd || !comicReelsEnd
                    else -> upstreamMore || !realReelsEnd || !comicReelsEnd
                }, nextOffset = reelsPageNumber)
        }
    }

    /**
     * Optional, read-only A/B sample of the SAME official tab endpoint.
     * Fresh cursors ensure the probe cannot alter current playback or paging.
     * Different lists are a clue, never proof of server-side personalization:
     * anonymous feeds can also randomize or rotate experiments.
     */
    suspend fun comparePersonalizationSamples(): RecommendationComparison =
        withContext(Dispatchers.IO) {
            val session = synchronized(recommendationLock) { accountFeed }
                ?: throw IOException("未开启账号推荐，无法执行对照检查")
            // The normal repository uses an in-memory CookieJar for CDN routing.
            // Isolate both probes from that jar: the guest control MUST NEVER
            // inherit an authenticated Set-Cookie from an earlier app request.
            val probeHttp = repository.http.newBuilder()
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(13, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            // Compare TWO isolated account sessions with TWO independent guest
            // sessions. Account samples use the SAME registered-device signing
            // integration as the live reels; guests never inherit any cookies.
            // These probes are read-only and never advance the playing cursor.
            var successfulSignedSamples = 0
            fun accountSample(): List<String> {
                var signedSuccess = false
                val page = RecommendationFeed(
                    probeHttp, account = session, cursor = RecommendationCursor(),
                    playbackSession = recommendationDeviceSession,
                    onSummary = { summary ->
                        if (summary.kind == FeedResponseKind.DYNAMIC_TAB &&
                            summary.businessCode == 0 && summary.itemCount > 0)
                            signedSuccess = summary.signedDeviceIdentity
                    },
                ).page(1)
                if (signedSuccess) successfulSignedSamples++
                return page.items.map { it.id }.distinct().take(20)
            }
            fun guestSample(): List<String> =
                RecommendationFeed(probeHttp, cursor = RecommendationCursor())
                    .page(1).items.map { it.id }.distinct().take(20)

            val a = accountSample()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val b = guestSample()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val a2 = accountSample()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val c = guestSample()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (a.isEmpty() || b.isEmpty() || a2.isEmpty() || c.isEmpty())
                throw IOException("推荐接口未提供足够的对照数据")
            val overlap = a.toSet().intersect(b.toSet()).size
            val secondOverlap = a2.toSet().intersect(c.toSet()).size
            val guestRepeat = b.toSet().intersect(c.toSet()).size
            val accountRepeat = a.toSet().intersect(a2.toSet()).size
            fun percent(shared: Int, n1: Int, n2: Int): Int =
                (shared * 100 / minOf(n1, n2).coerceAtLeast(1)).coerceIn(0, 100)
            // Aggregate counts only; never persist titles, IDs or credentials.
            diagnostics?.record("REELS",
                "COMPARISON_SIGNED_ACCOUNT_${successfulSignedSamples}_OF_2")
            diagnostics?.record("REELS",
                "COMPARISON_PAIR1_OVERLAP_${percent(overlap, a.size, b.size)}PCT")
            diagnostics?.record("REELS",
                "COMPARISON_PAIR2_OVERLAP_${percent(secondOverlap, a2.size, c.size)}PCT")
            diagnostics?.record("REELS",
                "COMPARISON_ACCOUNT_BASELINE_${percent(accountRepeat, a.size, a2.size)}PCT")
            diagnostics?.record("REELS",
                "COMPARISON_GUEST_BASELINE_${percent(guestRepeat, b.size, c.size)}PCT")
            diagnostics?.record("REELS", "ACCOUNT_GUEST_GUEST_BASELINE_DONE")
            RecommendationComparison(
                accountCount = a.size,
                anonymousCount = b.size,
                overlapCount = overlap,
                identicalOrder = a == b,
                secondAnonymousCount = c.size,
                anonymousBaselineOverlap = guestRepeat,
                anonymousBaselineIdenticalOrder = b == c,
                secondAccountCount = a2.size,
                secondAccountGuestOverlap = secondOverlap,
                secondAccountGuestIdenticalOrder = a2 == c,
                accountBaselineOverlap = accountRepeat,
                accountBaselineIdenticalOrder = a == a2,
                signedAccountSamples = successfulSignedSamples,
            )
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

    suspend fun publicDetail(id: String): DramaPublicDetail = websiteCovers.publicDetail(id)

    suspend fun websiteLabels(id: String): DramaLabels = websiteCovers.labels(id)

    /**
     * A single signed video_detail response feeds counters AND the optional
     * openhongguo secondary info rows. Failure to parse optional metadata
     * never discards verified social data or interrupts playback.
     */
    suspend fun appEnrichment(id: String): Pair<SocialStats, OpenHongguoMetadata?> =
        withContext(Dispatchers.IO) {
            val response = repository.socialDetail(id)
            val social = SocialStatsParser.application(response, id)
            val metadata = runCatching {
                OpenHongguoMetadataParser.parse(response, id)
            }.getOrNull()
            social to metadata
        }

    suspend fun appSocial(id: String): SocialStats = appEnrichment(id).first

    /**
     * Only the official signed "系列剧" cell may drive automatic cross-season
     * playback. RelatedWorks.nextSeason requires a unique, consecutive,
     * matching, playable season; no arbitrary related recommendation qualifies.
     */
    suspend fun nextConfirmedSeason(id: String, currentTitle: String): Drama? =
        withContext(Dispatchers.IO) {
            val current = Series(id, currentTitle)
            com.hongguotv.core.RelatedWorks.nextSeason(
                current, repository.related(id),
            )?.series?.toDrama()
        }

    /** Signed first-party related-work cells; upcoming seasons never playable. */
    suspend fun related(id: String): List<RelatedDrama> = withContext(Dispatchers.IO) {
        repository.related(id).map {
            RelatedDrama(it.series.toDrama(), it.label, it.upcoming, it.reservationItemId)
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
    /**
     * Acquire a normal signed model and a prepared decrypting stream.
     * The TV-style preload coordinator owns selection and sample loading.
     * No speculative model request, second model lane or redundant warmup.
     */
    suspend fun openVideo(
        vid: String, nextVid: String? = null, qualityLimit: Int = 0,
    ): RemoteVideo = withContext(Dispatchers.IO) {
        diagnostics?.record("PLAYBACK", "REGISTER_AND_MODEL_START")
        val started = System.nanoTime()
        val ahead = nextVid?.takeIf {
            it != vid && it.matches(Regex("[0-9]{1,30}"))
        }?.let { listOf(it) }.orEmpty()
        val ladder = try {
            repository.streamLadder(vid, metadataAhead = ahead, speculative = false)
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
                .thenBy { cdnHealth.score(it.url) }
        ).take(4)
        var last: IOException? = null
        val startupHttp = repository.http.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        for ((index, candidate) in candidates.withIndex()) {
            val video = RemoteVideo(repository.http, candidate.toStreamInfo(), startupHttp)
            val start = System.nanoTime()
            try {
                // The remote index is required before Media3 can preload samples.
                video.prepare(initialBytes = 64 * 1024)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                diagnostics?.record("PLAYBACK", "RANGE_PREPARED",
                    (System.nanoTime() - start) / 1_000_000)
                cdnHealth.success(candidate.url,
                    (System.nanoTime() - start) / 1_000_000)
                diagnostics?.record("PLAYBACK", "CDN_CANDIDATE_${index + 1}_READY")
                return@withContext video
            } catch (cancel: CancellationException) {
                video.close()
                throw cancel
            } catch (error: IOException) {
                video.close()
                last = error
                cdnHealth.failure(candidate.url)
                diagnostics?.record("PLAYBACK", "RANGE_FAIL",
                    (System.nanoTime() - start) / 1_000_000)
                diagnostics?.record("PLAYBACK", "CDN_CANDIDATE_${index + 1}_FAILED")
            } catch (error: Exception) {
                video.close()
                throw error
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
