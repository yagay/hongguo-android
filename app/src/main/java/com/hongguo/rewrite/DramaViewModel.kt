package com.hongguo.rewrite

import android.app.Application
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
    private val publicWebsite = NativeWebsiteSource()
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var downloadJob: Job? = null
    private var requestId = 0L
    private var detailId = 0L
    private var firstFrameFor: String? = null
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true))
    )
    val state = mutable.asStateFlow()

    init {
        if (state.value.backend.isNotBlank()) {
            source = runCatching { WebContractClient(state.value.backend) }.getOrNull()
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
        pageJob = viewModelScope.launch {
            try {
                val result = if (api != null)
                    api.list(s.section, 0, s.genre, s.board, s.search, s.activeFilters, emptyList())
                else publicWebsite.list(s.section, 0, s.search)
                if (version != requestId) return@launch
                mutable.update { it.copy(items = result.items, nextOffset = result.nextOffset,
                    hasMore = result.hasMore, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == requestId) mutable.update {
                    it.copy(loading = false, error = errorText("加载剧集", e))
                }
            }
        }
        if (api != null && s.section == Section.BROWSE && s.filters.isEmpty()) {
            viewModelScope.launch {
                runCatching { api.filters(s.genre) }.onSuccess { rows ->
                    if (version == requestId) mutable.update { it.copy(filters = rows) }
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
                    ?: publicWebsite.list(s.section, s.nextOffset, s.search)
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

    fun show(modal: Modal) = mutable.update { it.copy(modal = modal) }
    fun closeModal() = show(Modal.NONE)

    fun saveBackend(value: String) {
        if (value.isBlank()) {
            source = null
            prefs.edit().remove("backend").apply()
            mutable.update { it.copy(backend = "", modal = Modal.NONE) }
            refresh()
            return
        }
        val candidate = runCatching { WebContractClient(value) }
        if (candidate.isFailure) {
            mutable.update { it.copy(error = "地址格式错误：" +
                (candidate.exceptionOrNull()?.message ?: "未知错误")) }
            return
        }
        source = candidate.getOrThrow()
        prefs.edit().putString("backend", value.trim()).apply()
        mutable.update { it.copy(backend = value.trim(), modal = Modal.NONE) }
        refresh()
    }

    fun cover(image: String): String = source?.coverUrl(image) ?: publicWebsite.coverUrl(image)

    fun open(drama: Drama, resumeEp: Int? = null) {
        detailJob?.cancel()
        val version = ++detailId
        firstFrameFor = null
        startEpisodeHint = resumeEp
        mutable.update { it.copy(detail = null, preparingDetail = true,
            playbackError = null, playbackUrl = null, playing = false) }
        val api = source
        detailJob = viewModelScope.launch {
            try {
                val result = api?.detail(drama.id) ?: publicWebsite.detail(drama.id)
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
                play(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == detailId) mutable.update { it.copy(preparingDetail = false,
                    playbackError = errorText("读取剧集列表", e)) }
            }
        }
    }

    fun play(index: Int) {
        val detail = state.value.detail ?: return
        if (index !in detail.episodes.indices) return
        val api = source
        val version = ++detailId
        firstFrameFor = null
        mutable.update { it.copy(episodeIndex = index, playbackUrl = null,
            playing = false, playbackError = if (api == null)
                "PLAYBACK_SOURCE_UNAVAILABLE: 官网只提供分集信息；原生视频取址尚未移植，播放需连接网页版服务"
                else null) }
        detailJob?.cancel()
        if (api == null) return
        detailJob = viewModelScope.launch {
            try {
                val url = api.playableUrl(detail.episodes[index].vid)
                if (version != detailId) return@launch
                mutable.update { it.copy(playbackUrl = url, playing = true) }
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
        mutable.update { it.copy(playbackError = "MEDIA_ERROR: " + message, playing = false) }
    }

    /** A history entry is created only after Media3 reports an actual rendered video frame. */
    fun firstFrame(expectedVid: String) {
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (ep.vid != expectedVid || firstFrameFor == ep.vid) return
        firstFrameFor = ep.vid
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
        val api = source ?: return
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val (id, ep) = api.resolveShare(message)
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
        val api = source ?: return
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

    private fun errorText(stage: String, error: Exception): String =
        PlaybackProblem.describe(stage, error)
}
