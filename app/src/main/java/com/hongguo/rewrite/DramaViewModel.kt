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
    private val localDownloadJobs = mutableMapOf<String, Job>()
    private var requestId = 0L
    private var detailId = 0L
    private var firstFrameFor: String? = null
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true),
            logEnabled = diagnostics.enabled)
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
                    val video = nativeSource.openVideo(vid)
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
