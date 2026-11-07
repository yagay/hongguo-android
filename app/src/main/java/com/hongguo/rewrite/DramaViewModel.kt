package com.hongguo.rewrite

import android.app.Application
import android.net.Uri
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.SleepTimer
import java.io.File
import java.io.IOException
import java.io.EOFException
import org.json.JSONArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

class DramaViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    private val prefs = application.getSharedPreferences("web_rewrite_settings", 0)
    private var source: WebContractClient? = null
    private val diagnostics = DiagnosticLog(File(application.filesDir, "diagnostics"),
        prefs.getBoolean("diagnostic_enabled", true))
    private val publicWebsite = NativeWebsiteSource(diagnostics = diagnostics)
    private val nativeSource = NativeHongguoSource(diagnostics = diagnostics)
    private val sleepTimer = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    @Volatile private var activeNativeVideo: RemoteVideo? = null
    @Volatile private var activeNativeVid: String? = null
    fun nativeVideoFor(vid: String): RemoteVideo? =
        if (activeNativeVid == vid) activeNativeVideo else null
    private fun closeNativeVideo() {
        activeNativeVideo?.close()
        activeNativeVideo = null
        activeNativeVid = null
    }
    private var searchJob: Job? = null
    private var suggestionVersion = 0L
    private fun recentQueries(): List<String> {
        val data = runCatching {
            JSONArray(prefs.getString("recent_queries", "[]"))
        }.getOrElse { JSONArray() }
        return (0 until data.length()).map { data.optString(it) }
            .filter { it.isNotBlank() && it.length <= 80 }.distinct().take(12)
    }
    private fun rememberQuery(value: String) {
        val clean = value.trim().take(80)
        if (clean.isEmpty()) return
        val values = (listOf(clean) + recentQueries()).distinct().take(12)
        prefs.edit().putString("recent_queries",
            JSONArray().apply { values.forEach(::put) }.toString()).apply()
        mutable.update { it.copy(recentSearches = values) }
    }
    fun clearSearchHistory() {
        prefs.edit().remove("recent_queries").apply()
        mutable.update { it.copy(recentSearches = emptyList()) }
    }
    private var recommendationPrefillBatches = 0
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var downloadJob: Job? = null
    private var commentsJob: Job? = null
    private var socialJob: Job? = null
    private var relatedJob: Job? = null
    private var relatedGeneration = 0L
    private var socialGeneration = 0L
    private data class SocialCache(val stats: SocialStats, val savedAt: Long)
    private val socialCache = linkedMapOf<String, SocialCache>()
    private var danmakuJob: Job? = null
    private var commentsGeneration = 0L
    private var danmakuGeneration = 0L
    private val localDownloadJobs = mutableMapOf<String, Job>()
    private var requestId = 0L
    private var detailId = 0L
    private var pendingFeedAdvance = false
    private var feedPrefetchJob: Job? = null
    private val prefetchedFeedDetails = linkedMapOf<String, DramaDetail>()

    private fun prefetchFollowingFeedItem(index: Int) {
        val next = state.value.items.getOrNull(index + 1) ?: return
        if (source != null || prefetchedFeedDetails.containsKey(next.id)) return
        feedPrefetchJob?.cancel()
        feedPrefetchJob = viewModelScope.launch {
            try {
                val detail = nativeSource.detail(next.id)
                if (state.value.feedMode && state.value.items.getOrNull(
                        state.value.feedIndex + 1)?.id == next.id) {
                    prefetchedFeedDetails[next.id] = detail
                    while (prefetchedFeedDetails.size > 3)
                        prefetchedFeedDetails.remove(prefetchedFeedDetails.keys.first())
                    diagnostics.record("FEED", "NEXT_DETAIL_PREFETCHED")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.record("FEED", "NEXT_DETAIL_UNAVAILABLE")
            }
        }
    }
    private var firstFrameFor: String? = null
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true),
            likedDramaIds = prefs.getStringSet("local_liked_dramas", emptySet())?.toSet().orEmpty(),
            recentSearches = recentQueries(),
            qualityLimit = prefs.getInt("quality_limit", 0)
                .takeIf { it in setOf(0, 360, 480, 720, 1080) } ?: 0,
            logEnabled = diagnostics.enabled,
            danmakuEnabled = prefs.getBoolean("danmaku_enabled", false),
            danmakuSpeed = prefs.getInt("danmaku_speed", 1).coerceIn(0, 2),
            danmakuDensity = prefs.getInt("danmaku_density", 4).coerceIn(1, 5),
            danmakuFontSize = prefs.getInt("danmaku_font_size", 14).coerceIn(12, 18))
    )
    val state = mutable.asStateFlow()

    init {
        diagnostics.record("APP", "START")
        if (state.value.backend.isNotBlank()) {
            source = runCatching { WebContractClient(state.value.backend,
                diagnostics = diagnostics) }.getOrNull()
        }
        refresh()
    }

    /** Debounced autocomplete, canceled for every edit or navigation. */
    fun setSearch(value: String) {
        val keyword = value.take(80)
        val version = ++suggestionVersion
        searchJob?.cancel()
        mutable.update { it.copy(search = keyword, suggestions = emptyList(),
            suggestionsLoading = keyword.trim().length >= 2 && source == null) }
        if (keyword.trim().length < 2 || source != null) return
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val rows = nativeSource.suggestions(keyword)
                if (version == suggestionVersion && state.value.search == keyword) {
                    mutable.update { it.copy(suggestions = rows, suggestionsLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.record("SEARCH", "SUGGEST_UNAVAILABLE")
                if (version == suggestionVersion) {
                    mutable.update { it.copy(suggestions = emptyList(), suggestionsLoading = false) }
                }
            }
        }
    }

    fun chooseSuggestion(value: String) {
        setSearch(value)
        search()
    }

    fun section(section: Section) {
        if (section == Section.SEARCH && state.value.search.isBlank()) {
            mutable.update { it.copy(section = section, items = emptyList(), error = null) }
            return
        }
        mutable.update { it.copy(section = section) }
        refresh()
    }

    fun board(value: String) {
        mutable.update { it.copy(board = value) }
        if (state.value.section == Section.RANK) refresh()
    }

    fun genre(value: String) {
        if (value !in setOf("short_play", "comic_series", "ai_series")) return
        mutable.update { it.copy(genre = value, filters = emptyList(), activeFilters = emptyMap()) }
        if (state.value.section in setOf(Section.LATEST, Section.BROWSE)) refresh()
    }

    fun search() {
        val term = state.value.search.trim()
        if (term.isBlank()) return
        suggestionVersion++
        searchJob?.cancel()
        rememberQuery(term)
        mutable.update { it.copy(section = Section.SEARCH, search = term,
            suggestions = emptyList(), suggestionsLoading = false) }
        refresh()
    }

    fun chooseFilter(key: String, option: String) = mutable.update {
        it.copy(activeFilters = it.activeFilters.toMutableMap().apply {
            if (option.isBlank()) remove(key) else put(key, option)
        })
    }

    fun refresh() {
        pageJob?.cancel()
        recommendationPrefillBatches = 0
        val version = ++requestId
        val s = state.value
        mutable.update { it.copy(items = emptyList(), loading = true, moreLoading = false,
            error = null, hasMore = false, nextOffset = 0) }
        val api = source
        diagnostics.record("CATALOG", if (api != null) "SOURCE_API" else "SOURCE_WEBSITE")
        pageJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = if (api != null)
                    api.list(s.section, 0, s.genre, s.board, s.search, s.activeFilters, emptyList())
                else nativeSource.list(s.section, 0, s.genre, s.board, s.search, s.activeFilters)
                if (version != requestId) return@launch
                mutable.update { it.copy(items = result.items, nextOffset = result.nextOffset,
                    hasMore = result.hasMore, loading = false) }
                diagnostics.record("CATALOG", "SUCCESS", elapsed(started))
                if (api == null && s.section == Section.RECOMMEND) {
                    recommendationPrefillBatches = 1
                    if (RecommendationLoadingPolicy.shouldPrefill(
                            native = true, section = s.section,
                            feedMode = state.value.feedMode,
                            total = state.value.items.size,
                            hasMore = state.value.hasMore,
                            fetchedBatches = recommendationPrefillBatches,
                            newItems = result.items.size,
                        )) more()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestId) mutable.update {
                    it.copy(loading = false, error = errorText("加载剧集", e))
                }
            }
        }
        if (s.section == Section.BROWSE && s.filters.isEmpty()) {
            viewModelScope.launch {
                try {
                    val groups = if (api != null) api.filters(s.genre)
                        else nativeSource.filters(s.genre)
                    if (version == requestId) mutable.update { it.copy(filters = groups) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.record("FILTERS", "LOAD_FAIL")
                }
            }
        }
    }

    fun more() {
        val s = state.value
        if (s.loading || s.moreLoading || !s.hasMore) return
        mutable.update { it.copy(moreLoading = true, error = null) }
        val version = requestId
        pageJob = viewModelScope.launch {
            try {
                val result = source?.list(s.section, s.nextOffset, s.genre,
                    s.board, s.search, s.activeFilters, s.items.map { it.id })
                    ?: nativeSource.list(s.section, s.nextOffset,
                        s.genre, s.board, s.search, s.activeFilters)
                if (version != requestId) return@launch
                val merged = (s.items + result.items).distinctBy(Drama::id)
                val added = (merged.size - s.items.size).coerceAtLeast(0)
                val continued = result.hasMore && result.nextOffset > s.nextOffset && added > 0
                mutable.update {
                    it.copy(items = merged, nextOffset = result.nextOffset,
                        hasMore = continued, moreLoading = false)
                }
                if (!continued && result.hasMore) {
                    diagnostics.record("CATALOG", "PAGINATION_NO_PROGRESS")
                }
                if (pendingFeedAdvance) {
                    pendingFeedAdvance = false
                    navigateFeed(1)
                } else if (state.value.feedMode) {
                    prefetchFollowingFeedItem(state.value.feedIndex)
                } else if (source == null && s.section == Section.RECOMMEND) {
                    recommendationPrefillBatches++
                    if (RecommendationLoadingPolicy.shouldPrefill(
                            native = true, section = s.section, feedMode = false,
                            total = merged.size, hasMore = continued,
                            fetchedBatches = recommendationPrefillBatches,
                            newItems = added,
                        )) more()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingFeedAdvance = false
                if (version == requestId) mutable.update { it.copy(moreLoading = false,
                    error = errorText("加载更多", e)) }
            }
        }
    }

    private fun clearSocial() {
        socialGeneration++
        socialJob?.cancel()
        mutable.update {
            it.copy(socialForSeries = null, socialStats = null,
                socialStatsLoading = false)
        }
    }

    /** Auto-load server-sourced statistics for the opened drama.
     *
     * Website and anonymous app reads run independently; each successful
     * response updates the rail as soon as it arrives. Neither blocks video,
     * comments, or Media3. No account writes or synthetic counters.
     */
    private fun loadSocialStats(seriesId: String) {
        socialJob?.cancel()
        val generation = ++socialGeneration
        val now = System.currentTimeMillis()
        val cached = socialCache[seriesId]?.takeIf {
            now - it.savedAt in 0..120_000
        }?.stats
        mutable.update {
            it.copy(socialForSeries = seriesId, socialStats = cached,
                socialStatsLoading = true)
        }
        socialJob = viewModelScope.launch {
            suspend fun applyStats(candidate: SocialStats, label: String) {
                if (generation != socialGeneration ||
                    state.value.detail?.drama?.id != seriesId) return
                mutable.update { s ->
                    val merged = s.socialStats?.merge(candidate) ?: candidate
                    s.copy(socialStats = merged)
                }
                val combined = state.value.socialStats
                if (combined != null) {
                    socialCache[seriesId] = SocialCache(combined, System.currentTimeMillis())
                    while (socialCache.size > 16) socialCache.remove(socialCache.keys.first())
                }
                diagnostics.record("SOCIAL", label + "_READY")
            }

            try {
                coroutineScope {
                    launch {
                        try {
                            applyStats(nativeSource.websiteSocial(seriesId), "WEB")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            diagnostics.record("SOCIAL", "WEB_FAIL")
                        }
                    }
                    launch {
                        try {
                            applyStats(nativeSource.appSocial(seriesId), "APP")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            diagnostics.record("SOCIAL", "APP_FAIL")
                        }
                    }
                }
            } finally {
                if (generation == socialGeneration &&
                    state.value.detail?.drama?.id == seriesId)
                    mutable.update { it.copy(socialStatsLoading = false) }
            }
        }
    }

    private fun clearEngagement() {
        commentsGeneration++
        danmakuGeneration++
        commentsJob?.cancel()
        danmakuJob?.cancel()
        mutable.update {
            it.copy(commentsForVid = null, comments = emptyList(),
                commentsTotal = null,
                commentsCursor = "", commentsHasMore = false,
                commentsLoading = false, commentsError = null,
                commentsLimited = false,
                danmakuForVid = null, danmakuItems = emptyList(),
                danmakuLoadedUntilMs = 0L, danmakuLoading = false,
                danmakuError = null)
        }
    }

    fun playIfDifferent(index: Int) {
        if (index in (state.value.detail?.episodes?.indices ?: IntRange.EMPTY) &&
            state.value.episodeIndex != index) play(index)
    }

    fun setDanmaku(enabled: Boolean) {
        prefs.edit().putBoolean("danmaku_enabled", enabled).apply()
        diagnostics.record("DANMAKU", if (enabled) "ENABLED" else "DISABLED")
        mutable.update { it.copy(danmakuEnabled = enabled,
            danmakuError = if (enabled) null else it.danmakuError) }
        if (!enabled) danmakuJob?.cancel()
    }

    fun setDanmakuSpeed(value: Int) {
        if (value !in 0..2) return
        prefs.edit().putInt("danmaku_speed", value).apply()
        mutable.update { it.copy(danmakuSpeed = value) }
    }

    fun setDanmakuDensity(value: Int) {
        if (value !in 1..5) return
        prefs.edit().putInt("danmaku_density", value).apply()
        mutable.update { it.copy(danmakuDensity = value) }
    }

    fun setDanmakuFontSize(value: Int) {
        if (value !in setOf(12, 14, 16, 18)) return
        prefs.edit().putInt("danmaku_font_size", value).apply()
        mutable.update { it.copy(danmakuFontSize = value) }
    }

    /** Open episode-specific anonymous comments. Never reuses stale pages. */
    /**
     * Called when opening the comments drawer. A background first-page request
     * started on episode selection remains in flight; do not cancel/restart it.
     * A manual refresh still uses loadComments(reset = true).
     */
    fun ensureCommentsLoaded() {
        val s = state.value
        val vid = s.detail?.episodes?.getOrNull(s.episodeIndex)?.vid ?: return
        if (s.commentsForVid == vid &&
            (s.commentsLoading || s.commentsTotal != null ||
                (s.commentsError == null && s.comments.isNotEmpty()))) return
        loadComments(reset = true)
    }

    fun loadComments(reset: Boolean = true) {
        val detail = state.value.detail ?: return
        val vid = detail.episodes.getOrNull(state.value.episodeIndex)?.vid ?: return
        val sid = detail.drama.id
        val s = state.value
        if (!reset && (s.commentsLoading || !s.commentsHasMore ||
            s.commentsCursor.isBlank() || s.commentsForVid != vid)) return
        val cursor = if (reset) "" else s.commentsCursor
        val generation = if (reset) ++commentsGeneration else commentsGeneration
        commentsJob?.cancel()
        mutable.update {
            it.copy(commentsForVid = vid, commentsLoading = true,
                commentsTotal = if (reset) null else it.commentsTotal,
                commentsError = null,
                comments = if (reset) emptyList() else it.comments,
                commentsCursor = if (reset) "" else it.commentsCursor,
                commentsHasMore = if (reset) false else it.commentsHasMore)
        }
        commentsJob = viewModelScope.launch {
            diagnostics.record("COMMENTS", if (reset) "FIRST_PAGE" else "NEXT_PAGE")
            try {
                val page = nativeSource.comments(sid, vid, cursor)
                if (generation != commentsGeneration ||
                    state.value.detail?.episodes?.getOrNull(state.value.episodeIndex)?.vid != vid)
                    return@launch
                mutable.update {
                    it.copy(comments = (it.comments + page.items).distinctBy(CommentEntry::id).take(500),
                        commentsCursor = page.nextCursor,
                        commentsHasMore = page.hasMore,
                        commentsTotal = page.total ?: it.commentsTotal,
                        commentsLoading = false, commentsLimited = page.limited)
                }
                diagnostics.record("COMMENTS",
                    if (page.limited) "PUBLIC_LIMITED" else "READ_SUCCESS")
                diagnostics.record("COMMENTS",
                    if (page.total == null) "TOTAL_UNKNOWN" else "TOTAL_READY")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == commentsGeneration) {
                    val problem = PlaybackProblem.describe("读取评论", e)
                    diagnostics.record("COMMENTS", "READ_FAIL_" + problem.substringBefore(" · "))
                    mutable.update { it.copy(commentsError = problem, commentsLoading = false) }
                }
            }
        }
    }

    /** Fetch timed comments ahead of the current player position, only on demand. */
    fun ensureDanmaku(positionMs: Long, durationMs: Long) {
        val s = state.value
        // Stop retrying a rejected anonymous endpoint every few seconds.
        // An explicit off/on toggle allows another attempt.
        if (!s.danmakuEnabled || s.danmakuLoading || s.danmakuError != null ||
            durationMs !in 1000L..86_400_000L) return
        val detail = s.detail ?: return
        val vid = detail.episodes.getOrNull(s.episodeIndex)?.vid ?: return
        val sid = detail.drama.id
        val same = s.danmakuForVid == vid
        val start = if (same) s.danmakuLoadedUntilMs else 0L
        if (start >= durationMs || (same && positionMs + 15_000 < start)) return
        val generation = danmakuGeneration
        mutable.update { it.copy(danmakuForVid = vid, danmakuLoading = true,
            danmakuError = null) }
        danmakuJob = viewModelScope.launch {
            diagnostics.record("DANMAKU", "FETCH_START")
            try {
                val page = nativeSource.danmaku(sid, vid, start, durationMs)
                if (generation != danmakuGeneration ||
                    state.value.detail?.episodes?.getOrNull(state.value.episodeIndex)?.vid != vid)
                    return@launch
                mutable.update { it.copy(
                    danmakuForVid = vid,
                    danmakuItems = (it.danmakuItems + page.items)
                        .distinctBy(DanmakuEntry::id).takeLast(600),
                    danmakuLoadedUntilMs = page.nextMs.coerceAtMost(durationMs),
                    danmakuLoading = false,
                ) }
                diagnostics.record("DANMAKU",
                    if (page.items.isEmpty()) "READ_EMPTY" else "READ_NONEMPTY")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == danmakuGeneration) {
                    val problem = PlaybackProblem.describe("读取弹幕", e)
                    diagnostics.record("DANMAKU", "READ_FAIL_" + problem.substringBefore(" · "))
                    mutable.update { it.copy(danmakuError = problem, danmakuLoading = false) }
                }
            }
        }
    }

    fun show(modal: Modal) {
        mutable.update { it.copy(modal = modal) }
        if (modal == Modal.SETTINGS) refreshDiagnostics()
    }
    fun closeModal() = show(Modal.NONE)

    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = diagnostics.snapshot()
            mutable.update { it.copy(logText = snapshot) }
        }
    }

    fun setLoggingEnabled(enabled: Boolean) {
        diagnostics.enabled = enabled
        prefs.edit().putBoolean("diagnostic_enabled", enabled).apply()
        if (enabled) diagnostics.record("SETTINGS", "ENABLED")
        mutable.update { it.copy(logEnabled = enabled,
            logNotice = if (enabled) "日志记录已开启" else "日志记录已关闭") }
        refreshDiagnostics()
    }

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            diagnostics.clear()
            mutable.update { it.copy(logText = diagnostics.snapshot(),
                logNotice = "日志已清空") }
        }
    }

    fun exportDiagnostics(uri: Uri) {
        diagnostics.record("SETTINGS", "EXPORT_REQUEST")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val output = getApplication<Application>().contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开导出文件")
                output.writer(Charsets.UTF_8).use { it.write(diagnostics.snapshot()) }
                diagnostics.record("SETTINGS", "EXPORT_OK")
                mutable.update { it.copy(logNotice = "诊断日志已导出为 TXT 文件") }
            } catch (error: Exception) {
                diagnostics.record("SETTINGS", "EXPORT_FAIL")
                mutable.update { it.copy(logNotice = "导出失败，请重新选择保存位置") }
            }
        }
    }

    private fun elapsed(started: Long): Long =
        (System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L

    fun saveBackend(value: String) {
        if (value.isBlank()) {
            source = null
            prefs.edit().remove("backend").apply()
            diagnostics.record("SETTINGS", "SOURCE_WEBSITE")
            mutable.update { it.copy(backend = "", settingsMessage = "已切换为官网公开目录模式") }
            refresh()
            return
        }
        val candidate = runCatching { WebContractClient(value) }
        if (candidate.isFailure) {
            diagnostics.record("SETTINGS", "INVALID_ADDRESS")
            mutable.update { it.copy(settingsMessage = "API 地址格式错误，请检查域名和协议") }
            return
        }
        source = WebContractClient(value.trim(), diagnostics = diagnostics)
        prefs.edit().putString("backend", value.trim()).apply()
        diagnostics.record("SETTINGS", "SOURCE_API")
        mutable.update { it.copy(backend = value.trim(), settingsMessage = "API 地址已保存") }
        refresh()
    }

    fun cover(image: String): String = source?.coverUrl(image) ?: nativeSource.coverUrl(image)

    fun loadRelated(seriesId: String) {
        if (!seriesId.matches(Regex("[0-9]{1,30}"))) return
        relatedJob?.cancel()
        val generation = ++relatedGeneration
        mutable.update { it.copy(
            relatedSeries = seriesId, relatedWorks = emptyList(),
            relatedLoading = true, relatedError = null,
        ) }
        relatedJob = viewModelScope.launch {
            try {
                val rows = nativeSource.related(seriesId)
                if (generation == relatedGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    mutable.update { it.copy(relatedWorks = rows,
                        relatedLoading = false, relatedError = null) }
                    diagnostics.record("RELATED", "SUCCESS")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == relatedGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    mutable.update { it.copy(
                        relatedLoading = false,
                        relatedError = errorText("相关作品", e)
                    ) }
                    diagnostics.record("RELATED", "UNAVAILABLE")
                }
            }
        }
    }

    /** Start a dedicated one-episode-per-drama vertical recommendation feed. */
    fun startFeed(drama: Drama? = null) {
        val s = state.value
        val selected = drama ?: s.items.firstOrNull() ?: return
        val index = s.items.indexOfFirst { it.id == selected.id }
        if (index < 0 || s.section != Section.RECOMMEND) return
        pendingFeedAdvance = false
        mutable.update { it.copy(feedIndex = index) }
        open(selected, resumeEp = 1, feed = true)
        prefetchFollowingFeedItem(index)
        if (index + 2 >= s.items.size && s.hasMore) more()
    }

    fun navigateFeed(direction: Int) {
        val s = state.value
        if (!s.feedMode || direction !in setOf(-1, 1)) return
        val index = s.feedIndex + direction
        val target = s.items.getOrNull(index)
        if (target != null && target.id != s.detail?.drama?.id) {
            pendingFeedAdvance = false
            mutable.update { it.copy(feedIndex = index) }
            open(target, resumeEp = 1, feed = true)
            prefetchFollowingFeedItem(index)
            if (index + 2 >= s.items.size && s.hasMore) more()
        } else if (direction > 0 && s.hasMore && !s.moreLoading) {
            pendingFeedAdvance = true
            more()
        } else if (direction > 0 && s.moreLoading) {
            pendingFeedAdvance = true
        }
    }

    fun exitFeedToEpisode(index: Int) {
        mutable.update { it.copy(feedMode = false) }
        play(index)
    }

    fun open(drama: Drama, resumeEp: Int? = null, feed: Boolean = false) {
        ++relatedGeneration
        relatedJob?.cancel()
        clearSocial()
        clearEngagement()
        detailJob?.cancel()
        val version = ++detailId
        firstFrameFor = null
        startEpisodeHint = resumeEp
        mutable.update { it.copy(detail = null, preparingDetail = true,
            feedMode = feed, relatedWorks = emptyList(), relatedSeries = null,
            relatedLoading = false, relatedError = null,
            playbackError = null, playbackUrl = null, playing = false) }
        val api = source
        diagnostics.record("DETAIL", "START")
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = api?.detail(drama.id)
                    ?: prefetchedFeedDetails.remove(drama.id)
                    ?: nativeSource.detail(drama.id)
                if (version != detailId) return@launch
                val resolved = result.copy(drama = result.drama.copy(
                    title = result.drama.title.ifBlank { drama.title },
                    cover = result.drama.cover.ifBlank { drama.cover },
                    total = result.episodes.size,
                    synopsis = result.drama.synopsis.ifBlank { drama.synopsis },
                ))
                val remembered = startEpisodeHint ?: state.value.library
                    .firstOrNull { it.drama.id == drama.id }?.episode
                val index = resolved.episodes.indexOfFirst { it.index == remembered }
                    .takeIf { it >= 0 } ?: 0
                mutable.update { it.copy(detail = resolved, episodeIndex = index,
                    preparingDetail = false) }
                diagnostics.record("DETAIL", "SUCCESS", elapsed(started))
                loadSocialStats(resolved.drama.id)
                loadRelated(resolved.drama.id)
                play(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == detailId) {
                    val message = errorText("读取剧集列表", e)
                    mutable.update { it.copy(preparingDetail = false,
                        playbackError = message, error = message) }
                }
            }
        }
    }

    fun play(index: Int) {
        val detail = state.value.detail ?: return
        if (index !in detail.episodes.indices) return
        clearEngagement()
        val api = source
        val version = ++detailId
        val vid = detail.episodes[index].vid
        detailJob?.cancel()
        closeNativeVideo()
        firstFrameFor = null
        val checkpoint = library.checkpoint(detail.drama.id, vid)
        mutable.update { it.copy(episodeIndex = index, playbackUrl = null,
            resumePositionMs = checkpoint,
            playing = false, playbackError = null) }
        // Independent IO request: comment totals are available before tapping
        // the comment action, without delaying playback address preparation.
        loadComments(reset = true)
        diagnostics.record("PLAYBACK", "START")
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                if (api != null) {
                    val url = api.playableUrl(vid)
                    if (version != detailId) return@launch
                    mutable.update { it.copy(playbackUrl = url, playing = true) }
                } else {
                    val ahead = detail.episodes.getOrNull(index + 1)?.vid
                    val video = nativeSource.openVideo(vid, ahead, state.value.qualityLimit)
                    if (version != detailId) {
                        video.close()
                        return@launch
                    }
                    activeNativeVideo = video
                    activeNativeVid = vid
                    mutable.update { it.copy(playbackUrl = "native://episode/" + vid,
                        playing = true) }
                }
                diagnostics.record("PLAYBACK", "SOURCE_READY", elapsed(started))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == detailId) mutable.update {
                    it.copy(playbackError = errorText("准备播放", e), playing = false)
                }
            }
        }
    }

    /** Session-only timer. The media player pauses when time expires. */
    fun sleepAfterMinutes(minutes: Int) {
        if (minutes == 0) sleepTimer.cancel() else sleepTimer.afterMinutes(minutes)
        mutable.update { it.copy(sleepTimerLabel = sleepTimer.label()) }
        diagnostics.record("TIMER", if (minutes == 0) "DISABLED" else "MINUTES_SET")
    }

    fun sleepAfterEpisodes(count: Int) {
        if (count == 0) sleepTimer.cancel() else sleepTimer.afterEpisodes(count)
        mutable.update { it.copy(sleepTimerLabel = sleepTimer.label()) }
        diagnostics.record("TIMER", "EPISODES_SET")
    }

    /** Returns true exactly once when the timer fires. */
    fun shouldPauseForTimer(): Boolean {
        val expired = sleepTimer.poll()
        if (expired) {
            mutable.update { it.copy(sleepTimerLabel = "关闭") }
            diagnostics.record("TIMER", "EXPIRED")
        } else {
            val label = sleepTimer.label()
            if (state.value.sleepTimerLabel != label) {
                mutable.update { it.copy(sleepTimerLabel = label) }
            }
        }
        return expired
    }

    fun setQualityLimit(value: Int, positionMs: Long, durationMs: Long) {
        if (value !in setOf(0, 360, 480, 720, 1080)) return
        prefs.edit().putInt("quality_limit", value).apply()
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex)
        if (ep != null && s.backend.isBlank() && s.qualityLimit != value) {
            savePlaybackPosition(ep.vid, positionMs, durationMs)
            mutable.update { it.copy(qualityLimit = value) }
            play(s.episodeIndex)
        } else mutable.update { it.copy(qualityLimit = value) }
        diagnostics.record("PLAYBACK", "QUALITY_PREFERENCE_SET")
    }

    fun retryPlayback() = play(state.value.episodeIndex)

    fun mediaError(expectedVid: String, message: String) {
        if (state.value.detail?.episodes?.getOrNull(state.value.episodeIndex)?.vid != expectedVid) return
        mutable.update { it.copy(playbackError = message, playing = false) }
        diagnostics.record("MEDIA", "FAIL_" + message.substringBefore(" · "))
    }

    /** A history entry is created only after Media3 reports an actual rendered video frame. */
    fun firstFrame(expectedVid: String) {
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (ep.vid != expectedVid || firstFrameFor == ep.vid) return
        firstFrameFor = ep.vid
        diagnostics.record("MEDIA", "FIRST_FRAME")
        library.record(s.detail.drama, ep.index)
        updateLibrary()
    }


    /** Media3-driven actual progress, persisted independently of history.
     * Do not record before the first decoded frame or for an old episode.
     */
    fun savePlaybackPosition(expectedVid: String, positionMs: Long, durationMs: Long) {
        val s = state.value
        val episode = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (episode.vid != expectedVid || firstFrameFor != expectedVid) return
        library.saveCheckpoint(s.detail.drama.id, expectedVid, episode.index,
            positionMs, durationMs)
    }

    fun finishEpisode(expectedVid: String) {
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (ep.vid == expectedVid) {
            library.clearCheckpoint(s.detail.drama.id, expectedVid)
            diagnostics.record("MEDIA", "EPISODE_FINISHED")
        }
    }

    fun nextEpisode(expectedVid: String? = null) {
        val s = state.value
        if (expectedVid != null && s.detail?.episodes?.getOrNull(s.episodeIndex)?.vid != expectedVid) return
        if (sleepTimer.episodeEnded()) {
            diagnostics.record("TIMER", "EPISODES_EXPIRED")
            mutable.update { it.copy(sleepTimerLabel = "关闭", playing = false) }
            return
        }
        mutable.update { it.copy(sleepTimerLabel = sleepTimer.label()) }
        if (s.feedMode) {
            navigateFeed(1)
            return
        }
        val index = s.episodeIndex + 1
        if (index < (s.detail?.episodes?.size ?: 0)) play(index)
    }

    fun closePlayer() {
        pendingFeedAdvance = false
        feedPrefetchJob?.cancel()
        prefetchedFeedDetails.clear()
        ++relatedGeneration
        relatedJob?.cancel()
        clearSocial()
        clearEngagement()
        ++detailId
        detailJob?.cancel()
        closeNativeVideo()
        firstFrameFor = null
        mutable.update { it.copy(detail = null, playbackUrl = null,
            feedMode = false, relatedSeries = null, relatedWorks = emptyList(),
            relatedLoading = false, relatedError = null,
            resumePositionMs = 0L, playbackError = null,
            preparingDetail = false, playing = false) }
    }

    fun favorite() {
        val s = state.value
        val detail = s.detail ?: return
        val ep = detail.episodes.getOrNull(s.episodeIndex)?.index ?: 1
        library.toggle(detail.drama, ep)
        updateLibrary()
    }

    /** Local-only 'like' is independent from saved favorites.
     * Never invent public likes or claim to post an upstream reaction.
     */
    fun toggleLocalLike() {
        val id = state.value.detail?.drama?.id ?: return
        val saved = state.value.likedDramaIds.toMutableSet()
        if (!saved.add(id)) saved.remove(id)
        prefs.edit().putStringSet("local_liked_dramas", saved).apply()
        mutable.update { it.copy(likedDramaIds = saved.toSet()) }
        diagnostics.record("LIBRARY", "LOCAL_LIKE_TOGGLED")
    }

    fun likeIfNotAlready() {
        val id = state.value.detail?.drama?.id ?: return
        if (id !in state.value.likedDramaIds) toggleLocalLike()
    }

    fun remove(id: String, fav: Boolean) {
        library.remove(id, fav)
        updateLibrary()
    }

    fun clear(fav: Boolean) {
        library.clear(fav)
        updateLibrary()
    }

    private fun updateLibrary() = mutable.update {
        it.copy(library = library.list(false), favorites = library.list(true))
    }

    fun share(message: String) {
        val api = source
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val (id, ep) = if (api != null) api.resolveShare(message)
                    else nativeSource.resolveShare(message)
                mutable.update { it.copy(modal = Modal.NONE) }
                open(Drama(id, "分享的短剧"), ep)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = errorText("解析分享", e), modal = Modal.NONE) }
            }
        }
    }

    fun download(from: Int, to: Int) {
        val s = state.value
        val api = source
        if (api == null) {
            diagnostics.record("DOWNLOAD", "NATIVE_PENDING")
            mutable.update { it.copy(playbackError =
                "本机下载尚未接入；原生播放已经独立，下载需要继续完成本地文件保存模块") }
            return
        }
        val id = s.detail?.drama?.id ?: return
        downloadJob = viewModelScope.launch {
            try {
                val task = api.startDownload(id, from, to)
                mutable.update { it.copy(downloadTasks = (it.downloadTasks + task)
                    .distinctBy(DownloadTask::id), modal = Modal.DOWNLOAD) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = errorText("下载", e)) }
            }
        }
    }

    /** Export a single decrypted MP4 to the user-chosen SAF document.
     * Foreground-process task: not resumable after process death.
     */
    fun downloadNative(vid: String, uri: Uri) {
        if (!vid.matches(Regex("[0-9]{1,30}"))) return
        val taskId = "local-" + System.currentTimeMillis() + "-" + vid.takeLast(6)
        mutable.update { s ->
            s.copy(downloadTasks = (
                listOf(DownloadTask(taskId, "等待下载", 0, 1, emptyList())) + s.downloadTasks
            ).take(40), modal = Modal.DOWNLOAD)
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var video: RemoteVideo? = null
            val started = System.nanoTime()
            diagnostics.record("DOWNLOAD", "START")
            try {
                video = nativeSource.openVideo(vid)
                val total = video.total
                if (total <= 0) throw IOException("下载资源长度无效")
                val output = getApplication<Application>()
                    .contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("无法打开目标文件")
                output.use { stream ->
                    var offset = 0L
                    var shownPercent = -1
                    val buffer = ByteArray(32 * 1024)
                    while (offset < total) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(512 * 1024L, total - offset).toInt()
                        video.openRead(offset, count).use { input ->
                            var copied = 0
                            while (copied < count) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer, 0, minOf(buffer.size, count - copied))
                                if (n <= 0) throw EOFException("下载片段读取中断")
                                stream.write(buffer, 0, n)
                                copied += n
                            }
                        }
                        offset += count
                        val percent = (offset * 100 / total).toInt()
                        if (percent != shownPercent) {
                            shownPercent = percent
                            val label = "下载中 " + percent + "%"
                            mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                                if (it.id == taskId) it.copy(status = label) else it
                            }) }
                        }
                    }
                    stream.flush()
                }
                diagnostics.record("DOWNLOAD", "COMPLETE", elapsed(started))
                mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                    if (it.id == taskId) it.copy(status = "已保存到所选文件", done = 1) else it
                }) }
            } catch (e: CancellationException) {
                diagnostics.record("DOWNLOAD", "CANCELLED")
                mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                    if (it.id == taskId) it.copy(status = "已取消（目标文件可能不完整）") else it
                }) }
                throw e
            } catch (e: Exception) {
                diagnostics.record("DOWNLOAD", "FAILED", elapsed(started))
                val label = PlaybackProblem.describe("本机下载", e)
                mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                    if (it.id == taskId) it.copy(status = label, failed = listOf(1)) else it
                }) }
            } finally {
                video?.close()
                synchronized(localDownloadJobs) { localDownloadJobs.remove(taskId) }
            }
        }
        synchronized(localDownloadJobs) { localDownloadJobs[taskId] = job }
    }

    fun cancelDownload(taskId: String) {
        synchronized(localDownloadJobs) { localDownloadJobs[taskId] }?.cancel()
    }

    fun updateDownloads() {
        val api = source ?: return
        viewModelScope.launch {
            val current = state.value.downloadTasks
            val rows = current.map { item ->
                runCatching { api.downloadTask(item.id) }.getOrDefault(item)
            }
            mutable.update { it.copy(downloadTasks = rows) }
        }
    }

    private fun errorText(stage: String, error: Exception): String {
        val description = PlaybackProblem.describe(stage, error)
        val sourceStage = when (stage) {
            "加载剧集", "加载更多" -> "CATALOG"
            "读取剧集列表" -> "DETAIL"
            "准备播放" -> "PLAYBACK"
            "下载" -> "DOWNLOAD"
            "解析分享" -> "SHARE"
            else -> "APP"
        }
        diagnostics.record(sourceStage, "FAIL_" + description.substringBefore(" · "))
        return description
    }
}
