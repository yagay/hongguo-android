package com.hongguo.rewrite

import android.app.Application
import android.net.Uri
import com.hongguotv.core.RemoteVideo
import java.io.File
import java.io.IOException
import java.io.EOFException
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

class DramaViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    private val prefs = application.getSharedPreferences("web_rewrite_settings", 0)
    private var source: WebContractClient? = null
    private val diagnostics = DiagnosticLog(File(application.filesDir, "diagnostics"),
        prefs.getBoolean("diagnostic_enabled", true))
    private val publicWebsite = NativeWebsiteSource(diagnostics = diagnostics)
    private val nativeSource = NativeHongguoSource(diagnostics = diagnostics)
    @Volatile private var activeNativeVideo: RemoteVideo? = null
    @Volatile private var activeNativeVid: String? = null
    fun nativeVideoFor(vid: String): RemoteVideo? =
        if (activeNativeVid == vid) activeNativeVideo else null
    private fun closeNativeVideo() {
        activeNativeVideo?.close()
        activeNativeVideo = null
        activeNativeVid = null
    }
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var downloadJob: Job? = null
    private var commentsJob: Job? = null
    private var danmakuJob: Job? = null
    private var commentsGeneration = 0L
    private var danmakuGeneration = 0L
    private val localDownloadJobs = mutableMapOf<String, Job>()
    private var requestId = 0L
    private var detailId = 0L
    private var firstFrameFor: String? = null
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true),
            likedDramaIds = prefs.getStringSet("local_liked_dramas", emptySet())?.toSet().orEmpty(),
            logEnabled = diagnostics.enabled,
            danmakuEnabled = prefs.getBoolean("danmaku_enabled", false))
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

    fun setSearch(value: String) = mutable.update { it.copy(search = value) }

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
        if (state.value.search.isBlank()) return
        mutable.update { it.copy(section = Section.SEARCH) }
        refresh()
    }

    fun chooseFilter(key: String, option: String) = mutable.update {
        it.copy(activeFilters = it.activeFilters.toMutableMap().apply {
            if (option.isBlank()) remove(key) else put(key, option)
        })
    }

    fun refresh() {
        pageJob?.cancel()
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
                mutable.update {
                    it.copy(items = (it.items + result.items).distinctBy(Drama::id),
                        nextOffset = result.nextOffset, hasMore = result.hasMore,
                        moreLoading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestId) mutable.update { it.copy(moreLoading = false,
                    error = errorText("加载更多", e)) }
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

    /** Open episode-specific anonymous comments. Never reuses stale pages. */
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
                        commentsLoading = false, commentsLimited = page.limited)
                }
                diagnostics.record("COMMENTS", if (page.limited) "PUBLIC_LIMITED" else "READ_SUCCESS")
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
                diagnostics.record("DANMAKU", "READ_SUCCESS")
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

    fun open(drama: Drama, resumeEp: Int? = null) {
        clearEngagement()
        detailJob?.cancel()
        val version = ++detailId
        firstFrameFor = null
        startEpisodeHint = resumeEp
        mutable.update { it.copy(detail = null, preparingDetail = true,
            playbackError = null, playbackUrl = null, playing = false) }
        val api = source
        diagnostics.record("DETAIL", "START")
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = api?.detail(drama.id) ?: nativeSource.detail(drama.id)
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
        mutable.update { it.copy(episodeIndex = index, playbackUrl = null,
            playing = false, playbackError = null) }
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
                    val video = nativeSource.openVideo(vid, ahead)
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

    fun nextEpisode(expectedVid: String? = null) {
        val s = state.value
        if (expectedVid != null && s.detail?.episodes?.getOrNull(s.episodeIndex)?.vid != expectedVid) return
        val index = s.episodeIndex + 1
        if (index < (s.detail?.episodes?.size ?: 0)) play(index)
    }

    fun closePlayer() {
        clearEngagement()
        ++detailId
        detailJob?.cancel()
        closeNativeVideo()
        firstFrameFor = null
        mutable.update { it.copy(detail = null, playbackUrl = null,
            playbackError = null, preparingDetail = false, playing = false) }
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
