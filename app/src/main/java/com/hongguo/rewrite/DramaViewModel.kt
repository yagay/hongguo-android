package com.hongguo.rewrite

import android.app.Application
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.FeedAccount
import com.hongguotv.core.CloudProgress
import com.hongguotv.core.Series
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore

class DramaViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    internal val account = AccountManager(application, viewModelScope)
    private val prefs = application.getSharedPreferences("web_rewrite_settings", 0)
    private var source: WebContractClient? = null

    private fun readOfflineEpisodes(): List<OfflineEpisode> {
        val data = runCatching { JSONArray(prefs.getString("offline_episodes_v1", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until minOf(data.length(), 200)).mapNotNull { i ->
            val row = data.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id")
            val vid = row.optString("vid")
            val uri = row.optString("uri")
            if (!id.matches(Regex("[0-9]{1,30}")) ||
                !vid.matches(Regex("[0-9]{1,30}")) || !uri.startsWith("content://")) {
                return@mapNotNull null
            }
            OfflineEpisode(id, row.optString("title").take(200), row.optString("cover").take(2048),
                row.optInt("episode", 1).coerceAtLeast(1), vid, uri,
                row.optLong("savedAt"))
        }.distinctBy { it.documentUri }
    }

    private fun saveOfflineEpisodes(rows: List<OfflineEpisode>) {
        val data = JSONArray()
        rows.distinctBy { it.documentUri }.take(200).forEach { row ->
            data.put(org.json.JSONObject().put("id", row.seriesId)
                .put("title", row.title).put("cover", row.cover)
                .put("episode", row.episode).put("vid", row.vid)
                .put("uri", row.documentUri).put("savedAt", row.savedAt))
        }
        prefs.edit().putString("offline_episodes_v1", data.toString()).apply()
        mutable.update { it.copy(offlineEpisodes = rows.distinctBy { row -> row.documentUri }.take(200)) }
    }

    fun removeOfflineEpisode(documentUri: String) {
        saveOfflineEpisodes(state.value.offlineEpisodes.filterNot { it.documentUri == documentUri })
    }

    fun openOfflineEpisode(episode: OfflineEpisode) {
        if (episode !in state.value.offlineEpisodes) return
        viewModelScope.launch {
            val uri = runCatching { Uri.parse(episode.documentUri) }.getOrNull() ?: return@launch
            val accessible = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver
                    .openFileDescriptor(uri, "r")?.use { true } ?: false }
                    .getOrDefault(false)
            }
            if (!accessible) {
                mutable.update { it.copy(offlineLibraryMessage = "离线文件无法读取，请检查保存位置或文档访问权限") }
                return@launch
            }
            ++detailId
            detailJob?.cancel()
            clearPreloadedNext()
            closeNativeVideo()
            clearSocial()
            clearEngagement()
            firstFrameFor = null
            mutable.update { it.copy(
                detail = DramaDetail(
                    Drama(episode.seriesId, episode.title, episode.cover, episode.episode),
                    listOf(Episode(episode.episode, episode.vid))),
                episodeIndex = 0, offlinePlaybackUri = episode.documentUri,
                offlineLibraryMessage = null,
                playbackUrl = episode.documentUri, playing = true,
                feedMode = false, preparingDetail = false, playbackError = null,
                resumePositionMs = library.checkpoint(episode.seriesId, episode.vid),
                relatedSeries = null, relatedWorks = emptyList(),
                guessWorks = emptyList(), guessesLoading = false, guessesError = null)
            }
            // Offline items also require service-owned playback; the UI must
            // never be the only place that installs media into ExoPlayer.
            play(0)
        }
    }

    private fun readWatchLater(): List<Drama> {
        val rows = runCatching { JSONArray(prefs.getString("watch_later_v1", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until minOf(rows.length(), 200)).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!id.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
            Drama(id, item.optString("title"), item.optString("cover"),
                item.optInt("total").coerceAtLeast(0))
        }.distinctBy { it.id }
    }


    /** Explicit user-triggered update check, never pretends to be a background notification. */
    fun setEpisodeUpdateNotifications(enabled: Boolean) {
        val app = getApplication<Application>()
        val allowed = !enabled || Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            mutable.update { it.copy(settingsMessage = "请先授予通知权限") }
            return
        }
        val scheduled = runCatching {
            TrackingUpdatesWorker.schedule(app, enabled)
        }.isSuccess
        if (!scheduled) {
            diagnostics.record("TRACKING", "SCHEDULER_UNAVAILABLE")
            mutable.update { it.copy(settingsMessage = "后台检查暂不可用，请稍后再试") }
            return
        }
        prefs.edit().putBoolean("episode_update_notifications", enabled).apply()
        mutable.update { it.copy(episodeUpdateNotificationsEnabled = enabled,
            settingsMessage = if (enabled) "已启用追剧更新后台检查" else "已关闭追剧更新后台检查") }
    }

    fun checkTrackedUpdates() {
        if (state.value.trackingUpdates) return
        val snapshot = state.value
        val dramas = (snapshot.favorites.map { it.drama } + snapshot.watchLater)
            .distinctBy { it.id }.take(60)
        if (dramas.isEmpty()) {
            mutable.update { it.copy(trackingUpdateMessage = "请先添加本机收藏或稍后看") }
            return
        }
        mutable.update { it.copy(trackingUpdates = true, trackingUpdateMessage = null) }
        viewModelScope.launch {
            val deltas = linkedMapOf<String, Int>()
            var checked = 0
            var errors = 0
            for (drama in dramas) {
                currentCoroutineContext().ensureActive()
                try {
                    val detail = if (source == null) nativeSource.detail(drama.id)
                        else source!!.detail(drama.id)
                    val episodes = detail.episodes.size
                    val known = prefs.getInt("track_episode_" + drama.id, -1)
                    if (known >= 0 && episodes > known) deltas[drama.id] = episodes - known
                    // Preserve baseline from a prior successful check, and never decrease it
                    // when an incomplete API result reports fewer episodes.
                    if (episodes > 0 && episodes > known) {
                        prefs.edit().putInt("track_episode_" + drama.id, episodes).apply()
                    }
                    checked++
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { errors++ }
            }
            mutable.update { it.copy(trackedEpisodeUpdates = deltas,
                trackingUpdates = false,
                trackingUpdateMessage = "检查完成：${checked} 部成功、${errors} 部失败；${deltas.size} 部发现新增剧集。首次检查仅建立基准。") }
        }
    }

    fun toggleWatchLater(drama: Drama) {
        if (!drama.id.matches(Regex("[0-9]{1,30}"))) return
        val old = state.value.watchLater
        val updated = if (old.any { it.id == drama.id }) old.filterNot { it.id == drama.id }
            else (listOf(drama) + old).take(200)
        val data = JSONArray()
        updated.forEach {
            data.put(org.json.JSONObject().put("id", it.id).put("title", it.title)
                .put("cover", it.cover).put("total", it.total))
        }
        prefs.edit().putString("watch_later_v1", data.toString()).apply()
        mutable.update { it.copy(watchLater = updated) }
    }

    fun hideRecommendation(drama: Drama) {
        if (!drama.id.matches(Regex("[0-9]{1,30}"))) return
        val hidden = (state.value.hiddenRecommendationIds + drama.id).take(500).toSet()
        prefs.edit().putStringSet("hidden_recommendations_v1", hidden).apply()
        mutable.update { it.copy(hiddenRecommendationIds = hidden) }
    }

    fun restoreHiddenRecommendations() {
        prefs.edit().remove("hidden_recommendations_v1").apply()
        mutable.update { it.copy(hiddenRecommendationIds = emptySet()) }
    }

    fun setPreloadNext(enabled: Boolean) {
        prefs.edit().putBoolean("preload_next_episode", enabled).apply()
        mutable.update { it.copy(preloadNext = enabled) }
        if (!enabled) {
            clearPreloadedNext()
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
        }
    }

    fun setAutoPlayNext(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play_next", enabled).apply()
        mutable.update { it.copy(autoPlayNext = enabled) }
    }

    fun setThemeStyle(value: String) {
        if (value !in setOf("warm", "immersive", "cards")) return
        prefs.edit().putString("theme_style", value).apply()
        mutable.update { it.copy(themeStyle = value) }
    }

    fun setThemeMode(value: String) {
        if (value !in setOf("system", "light", "dark")) return
        prefs.edit().putString("theme_mode", value).apply()
        mutable.update { it.copy(themeMode = value) }
    }

    fun setNavigationStyle(value: String) {
        if (value !in setOf("classic", "glass")) return
        prefs.edit().putString("navigation_style", value).apply()
        mutable.update { it.copy(navigationStyle = value) }
    }

    fun setDefaultPlaybackSpeed(value: Float) {
        if (value !in setOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)) return
        prefs.edit().putFloat("default_playback_speed", value).apply()
        mutable.update { it.copy(defaultPlaybackSpeed = value) }
    }

    fun setSkipIntroSeconds(seconds: Int) {
        if (seconds !in 0..120) return
        prefs.edit().putInt("skip_intro_seconds", seconds).apply()
        mutable.update { it.copy(skipIntroSeconds = seconds) }
    }

    fun setSkipOutroSeconds(seconds: Int) {
        if (seconds !in 0..120) return
        prefs.edit().putInt("skip_outro_seconds", seconds).apply()
        mutable.update { it.copy(skipOutroSeconds = seconds) }
    }

    fun setAutomaticPlaybackRetry(enabled: Boolean) {
        prefs.edit().putBoolean("playback_auto_retry", enabled).apply()
        mutable.update { it.copy(retryPlaybackAutomatically = enabled) }
    }

    private val diagnostics = DiagnosticLog(File(application.filesDir, "diagnostics"),
        prefs.getBoolean("diagnostic_enabled", true))
    private val publicWebsite = NativeWebsiteSource(diagnostics = diagnostics)
    private val nativeSource = NativeHongguoSource(diagnostics = diagnostics)
    /** Independent, lazy, read-only SDK; never used by Media3, account auth or reel paging. */
    private val openHongguo = OpenHongguoSource(application)
    internal val openHongguoDiscovery: OpenHongguoSource get() = openHongguo
    private var sdkDetailJob: Job? = null
    private var sdkDetailGeneration = 0L

    private fun loadSdkDetails(seriesId: String) {
        sdkDetailJob?.cancel()
        val generation = ++sdkDetailGeneration
        mutable.update { it.copy(openHongguoLoading = true) }
        sdkDetailJob = viewModelScope.launch {
            try {
                val meta = withContext(Dispatchers.IO) { openHongguo.details(seriesId) }
                if (generation != sdkDetailGeneration ||
                    state.value.detail?.drama?.id != seriesId ||
                    !state.value.detailPageVisible) return@launch
                mutable.update { current ->
                    val d = current.detail
                    if (d?.drama?.id != seriesId) current
                    else current.copy(
                        openHongguoMetadata = OpenHongguoMetadataParser.merge(
                            current.openHongguoMetadata, meta),
                        detail = d.copy(drama = d.drama.copy(
                            actors = (d.drama.actors + meta.actors).distinct().take(20),
                            categories = (d.drama.categories + meta.tags).distinct().take(16),
                        )),
                    )
                }
                diagnostics.record("SDK", "SERIES_INFO_READY")
                // Resolve a multi-season album only when the verified metadata
                // says it exists. Do not generate season IDs from names.
                if (meta.albumId != null) {
                    val seasons = try {
                        withContext(Dispatchers.IO) { openHongguo.seasons(seriesId) }.ifEmpty {
                            withContext(Dispatchers.IO) { openHongguo.album(meta.albumId) }.seasons.map {
                                Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        try {
                            withContext(Dispatchers.IO) { openHongguo.album(meta.albumId) }.seasons.map {
                                Drama(it.seriesId, it.title, it.coverUrl, it.episodeCount)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList() }
                    }
                    if (generation == sdkDetailGeneration &&
                        state.value.detail?.drama?.id == seriesId && seasons.isNotEmpty()) {
                        mutable.update { it.copy(
                            openHongguoSeasons = seasons.distinctBy(Drama::id)
                                .filter { row -> row.id != seriesId }.take(30),
                        ) }
                        diagnostics.record("SDK", "SEASONS_READY")
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diagnostics.record("SDK", "SERIES_INFO_UNAVAILABLE") }
            finally {
                if (generation == sdkDetailGeneration)
                    mutable.update { it.copy(openHongguoLoading = false) }
            }
        }
    }
    private val sleepTimer = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    @Volatile private var activeNativeVideo: RemoteVideo? = null
    @Volatile private var activeNativeVid: String? = null
    private var preloadedNextVid: String? = null
    private var preloadedNextQuality = 0
    private var preloadedNextVideo: RemoteVideo? = null
    private var preloadEpisodeJob: Job? = null

    /** Stream prepared for a different series, not the next episode of this series. */
    private var reelWarmSeriesId: String? = null
    private var reelWarmVid: String? = null
    private var reelWarmQuality = 0
    private var reelWarmVideo: RemoteVideo? = null

    private fun clearReelWarmVideo() {
        reelWarmVideo?.close()
        reelWarmVideo = null
        reelWarmSeriesId = null
        reelWarmVid = null
    }

    private fun clearPreloadedNext() {
        preloadEpisodeJob?.cancel()
        preloadEpisodeJob = null
        preloadedNextVideo?.close()
        preloadedNextVideo = null
        preloadedNextVid = null
    }

    private fun preloadFollowingEpisode() {
        val s = state.value
        if (!s.preloadNext || (source != null && !s.feedMode)) return
        val next = s.detail?.episodes?.getOrNull(s.episodeIndex + 1)?.vid ?: return
        if (next == preloadedNextVid) return
        clearPreloadedNext()
        val generation = detailId
        val quality = s.qualityLimit
        preloadEpisodeJob = viewModelScope.launch {
            delay(850)
            var remote: RemoteVideo? = null
            try {
                // The upstream playback model intermittently returns an
                // empty response. Retry at most once for prefetch only, and
                // only while the target episode and quality are unchanged.
                remote = try {
                    nativeSource.openVideo(next, qualityLimit = quality)
                } catch (first: CancellationException) {
                    throw first
                } catch (first: Exception) {
                    if (!PlaybackProblem.isEmptyPlaybackModel(first)) throw first
                    diagnostics.record("PLAYBACK", "PREFETCH_EMPTY_RETRY")
                    delay(1_500)
                    if (generation != detailId || !state.value.preloadNext ||
                        state.value.detail?.episodes
                            ?.getOrNull(state.value.episodeIndex + 1)?.vid != next ||
                        state.value.qualityLimit != quality) return@launch
                    nativeSource.openVideo(next, qualityLimit = quality)
                }
                if (generation == detailId && state.value.preloadNext &&
                    state.value.detail?.episodes?.getOrNull(state.value.episodeIndex + 1)?.vid == next &&
                    state.value.qualityLimit == quality) {
                    preloadedNextVideo = remote
                    preloadedNextVid = next
                    preloadedNextQuality = quality
                    remote = null
                    diagnostics.record("PLAYBACK", "NEXT_NATIVE_PREPARED")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { diagnostics.record("PLAYBACK", "NEXT_PREFETCH_UNAVAILABLE") }
            finally { remote?.close() }
        }
    }
    fun nativeVideoFor(vid: String): RemoteVideo? =
        if (activeNativeVid == vid) activeNativeVideo else null
    private fun closeNativeVideo() {
        val video = activeNativeVideo
        // The service owns the active encrypted stream. Closing it here while
        // ExoPlayer still references it causes silent stalls after skip/auto-next.
        if (video != null && BackgroundPlaybackService.active?.ownsVideo(video) != true)
            video.close()
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

    /** Portable local lists only. Never include cookies, account secrets or API credentials. */
    fun exportLocalBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val data = library.exportLists()
                    .put("format", "hongguo-local-v1")
                    .put("watchLater", JSONArray().apply {
                        state.value.watchLater.forEach {
                            put(org.json.JSONObject().put("id", it.id).put("title", it.title)
                                .put("cover", it.cover).put("total", it.total))
                        }
                    })
                    .put("likedDramaIds", JSONArray().apply {
                        state.value.likedDramaIds.forEach(::put)
                    })
                    .put("completedDramaIds", JSONArray().apply {
                        state.value.completedDramaIds.forEach(::put)
                    })
                    .put("favoriteGroups", org.json.JSONObject().apply {
                        state.value.favoriteGroups.forEach { (id, group) -> put(id, group) }
                    })
                getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?.use { it.write(data.toString(2).toByteArray(Charsets.UTF_8)) }
                    ?: error("无法创建备份文件")
            }
            mutable.update { it.copy(settingsMessage = if (result.isSuccess)
                "本机片单备份已导出（不包含账号凭据）"
                else "备份导出失败，请检查文件位置") }
        }
    }

    fun importLocalBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { input ->
                        val data = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            require(data.size() + count <= 1024 * 1024) { "备份文件过大" }
                            data.write(chunk, 0, count)
                        }
                        data.toString("UTF-8")
                    } ?: error("无法读取备份文件")
                val backup = org.json.JSONObject(content)
                require(backup.optString("format") == "hongguo-local-v1") {
                    "不支持的备份格式"
                }
                val laterArray = backup.getJSONArray("watchLater")
                val likesArray = backup.getJSONArray("likedDramaIds")
                val completedArray = backup.optJSONArray("completedDramaIds") ?: JSONArray()
                val groupsObject = backup.optJSONObject("favoriteGroups") ?: org.json.JSONObject()
                require(laterArray.length() <= 200 && likesArray.length() <= 500 &&
                    completedArray.length() <= 500) {
                    "备份条目超过限制"
                }
                val later = (0 until laterArray.length()).map { i ->
                    val row = laterArray.getJSONObject(i)
                    val id = row.getString("id")
                    val title = row.getString("title")
                    require(id.matches(Regex("[0-9]{1,30}")) && title.length <= 200)
                    val cover = row.optString("cover")
                    require(cover.length <= 2048)
                    Drama(id, title, cover, row.optInt("total").coerceIn(0, 100000))
                }.distinctBy { it.id }
                val likes = (0 until likesArray.length()).map { likesArray.getString(it) }.toSet()
                require(likes.all { it.matches(Regex("[0-9]{1,30}")) })
                val completed = (0 until completedArray.length()).map {
                    completedArray.getString(it)
                }.toSet()
                require(completed.all { it.matches(Regex("[0-9]{1,30}")) })
                val groups = groupsObject.keys().asSequence().associateWith { groupsObject.getString(it) }
                require(groups.size <= 200 && groups.all { (id, group) ->
                    id.matches(Regex("[0-9]{1,30}")) && group.length in 1..24
                })
                // All sections have passed validation before replacing current data.
                library.importLists(backup, groups)
                prefs.edit().putString("watch_later_v1", JSONArray().apply {
                    later.forEach {
                        put(org.json.JSONObject().put("id", it.id)
                            .put("title", it.title).put("cover", it.cover).put("total", it.total))
                    }
                }.toString()).putStringSet("local_liked_dramas", likes)
                    .putStringSet("completed_drama_ids_v1", completed).commit().also {
                    check(it) { "无法保存本机备份" }
                }
                mutable.update {
                    it.copy(library = library.list(false), favorites = library.list(true),
                        watchLater = later, likedDramaIds = likes,
                        completedDramaIds = completed,
                        favoriteGroups = library.favoriteGroups())
                }
            }
            mutable.update { it.copy(settingsMessage = if (result.isSuccess)
                "本机片单已恢复"
                else "恢复失败：请确认是本应用导出的有效备份") }
        }
    }

    /** Screenshot-style search landing; real official rankings and hot terms only.
     * Failure of either source does not block searching or history.
     */
    fun loadSearchLanding(force: Boolean = false) {
        if (!force && (state.value.landingLoading || state.value.landingRank.isNotEmpty())) return
        mutable.update { it.copy(landingLoading = true, landingError = null) }
        viewModelScope.launch {
            val rank = try {
                nativeSource.list(Section.RANK, 0, "short_play", "all", "", emptyMap())
                    .items.take(18)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emptyList() }
            val hot = try {
                withContext(Dispatchers.IO) { openHongguo.browse("hot") }
                    .items.map { row -> row.searchQuery.ifBlank { row.title }.trim() }
                    .filter { it.isNotBlank() && it.length <= 80 }.distinct().take(12)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { emptyList() }
            mutable.update { it.copy(
                landingRank = rank,
                landingTerms = hot,
                landingLoading = false,
                landingError = if (rank.isEmpty() && hot.isEmpty())
                    "热搜数据暂不可用，可直接输入关键词搜索" else null
            ) }
            diagnostics.record("SEARCH", if (rank.isEmpty() && hot.isEmpty())
                "LANDING_UNAVAILABLE" else "LANDING_READY")
        }
    }

    fun clearSearchHistory() {
        prefs.edit().remove("recent_queries").apply()
        mutable.update { it.copy(recentSearches = emptyList()) }
    }
    private var pageJob: Job? = null
    private var detailJob: Job? = null
    private var downloadJob: Job? = null
    private var commentsJob: Job? = null
    private var socialJob: Job? = null
    private var labelsJob: Job? = null
    private var labelsGeneration = 0L
    private var relatedJob: Job? = null
    private var relatedGeneration = 0L
    private var guessesJob: Job? = null
    private var guessesGeneration = 0L
    private var socialGeneration = 0L
    private data class SocialCache(val stats: SocialStats, val savedAt: Long)
    private val socialCache = linkedMapOf<String, SocialCache>()
    private var danmakuJob: Job? = null
    private var commentsGeneration = 0L
    private var danmakuGeneration = 0L
    private val localDownloadJobs = mutableMapOf<String, Job>()
    private data class LocalDownloadTarget(
        val vid: String, val uri: Uri, val drama: Drama?, val episode: Episode?,
    )
    /** Retry destinations exist only while this process is alive. Never persist an expired URI. */
    private val retryableDownloads = mutableMapOf<String, LocalDownloadTarget>()
    /** Avoid overloading upstream decryption/network when batch saving episodes. */
    private val localDownloadSlots = Semaphore(2)
    private var requestId = 0L
    private var detailId = 0L
    /** Recent reel IDs are content IDs only, never account identifiers or cookies. */
    private fun recentlySwiped(): List<String> = runCatching {
        val stored = JSONArray(prefs.getString("recent_official_reels_v1", "[]"))
        (0 until minOf(stored.length(), 120)).map { stored.optString(it) }
            .filter { it.matches(Regex("[0-9]{1,30}")) }
    }.getOrDefault(emptyList())

    private fun rememberSwiped(id: String) {
        if (!id.matches(Regex("[0-9]{1,30}"))) return
        val last = (recentlySwiped().filterNot { it == id } + id).takeLast(120)
        prefs.edit().putString("recent_official_reels_v1",
            JSONArray(last).toString()).apply()
    }

    private var pendingFeedAdvance = false
    private var feedPrefetchJob: Job? = null
    private var officialFeedJob: Job? = null
    private var officialFeedGeneration = 0L
    /** Titles shown by the home recommendation grid are excluded from this swipe session. */
    private var homeFeedExcludedIds: Set<String> = emptySet()

    private fun loadOfficialReels(restart: Boolean = false) {
        val snapshot = state.value
        if (!snapshot.feedMode || snapshot.feedLoading) return
        val needsFirstDrama = snapshot.feedItems.isEmpty()
        val generation = if (restart) ++officialFeedGeneration else officialFeedGeneration
        // Non-restart continuation may be queued by the just-completed batch
        // coroutine itself. Cancelling it here would cancel the caller.
        if (restart) officialFeedJob?.cancel()
        val excluded = snapshot.feedItems.mapTo(linkedSetOf()) { it.id }
            .apply {
                addAll(snapshot.hiddenRecommendationIds)
                addAll(homeFeedExcludedIds)
                addAll(recentlySwiped())
            }
        mutable.update { it.copy(feedLoading = true, feedError = null) }
        officialFeedJob = viewModelScope.launch {
            try {
                val result = nativeSource.officialReelsPage(restart, excluded,
                    realOnly = snapshot.reelsMode == "real")
                if (generation != officialFeedGeneration || !state.value.feedMode) return@launch
                mutable.update { current ->
                    val merged = OfficialReelsPolicy.append(
                        current.feedItems, result.items,
                        current.hiddenRecommendationIds + homeFeedExcludedIds +
                            recentlySwiped().toSet())
                    current.copy(feedItems = merged,
                        feedHasMore = result.hasMore,
                        feedLoading = false,
                        feedError = if (merged.size == current.feedItems.size)
                            "官方暂未推荐新作品，可上滑重试" else null)
                }
                updateNotificationNavigation()
                diagnostics.record("REELS", "OFFICIAL_BATCH_READY")
                if (needsFirstDrama && state.value.feedMode) {
                    val first = state.value.feedItems.firstOrNull()
                    if (first != null) {
                        rememberSwiped(first.id)
                        open(first, resumeEp = 1, feed = true)
                    } else {
                        mutable.update { it.copy(preparingDetail = false,
                            feedError = "官方暂时没有新推荐，请点击重试") }
                    }
                }
                if (pendingFeedAdvance) {
                    pendingFeedAdvance = false
                    // A duplicate-only upstream response must not repeatedly
                    // page forever while the user remains on the last reel.
                    if (state.value.feedItems.size > snapshot.feedItems.size) navigateFeed(1)
                } else prefetchFollowingFeedItem(state.value.feedIndex)
                val latest = state.value
                if (!needsFirstDrama && OfficialReelsPolicy.needsMore(
                        latest.feedItems, latest.feedIndex, latest.hiddenRecommendationIds,
                        latest.feedHasMore, latest.feedLoading) &&
                    latest.feedItems.size > snapshot.feedItems.size) {
                    // One request ahead, bounded by the page's new visible items.
                    loadOfficialReels()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == officialFeedGeneration && state.value.feedMode) {
                    pendingFeedAdvance = false
                    mutable.update { it.copy(feedLoading = false,
                        preparingDetail = if (needsFirstDrama) false else it.preparingDetail,
                        feedError = OfficialReelsFailure.userMessage(error)) }
                    diagnostics.record("REELS",
                        "OFFICIAL_FAIL_" + OfficialReelsFailure.code(error))
                }
            }
        }
    }

    /**
     * A verified login alone does not authorize use of its Cookie for feeds.
     * Explicit account-recommendation opt-in updates the request identity,
     * invalidates the old anonymous cursor and keeps the current playing
     * drama while new items arrive. Never leak credentials to diagnostics.
     */
    fun setAccountRecommendations(enabled: Boolean) {
        account.setAccountRecommendations(enabled)
        applyRecommendationAccountIfChanged()
    }

    private fun applyRecommendationAccountIfChanged() {
        val session = account.recommendationAccount()
        if (session == appliedRecommendationSession) return
        appliedRecommendationSession = session
        nativeSource.setAccountRecommendations(session)
        diagnostics.record("REELS",
            if (session != null) "ACCOUNT_CONTEXT_ENABLED" else "ACCOUNT_CONTEXT_DISABLED")
        if (state.value.feedMode) {
            officialFeedJob?.cancel()
            officialFeedGeneration++
            pendingFeedAdvance = false
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
            prefetchedFeedDetails.clear()
            val snapshot = state.value
            // Do not stop/recreate Media3 playback solely for a settings
            // change. Preserve the visible drama while rebuilding the queue.
            val retained = OfficialReelsPolicy.retainCurrentOnAccountChange(
                snapshot.feedItems, snapshot.feedIndex, snapshot.detail?.drama)
            mutable.update { it.copy(
                feedItems = retained,
                feedIndex = 0,
                feedLoading = false,
                feedHasMore = true,
                feedError = null,
            ) }
            updateNotificationNavigation()
            loadOfficialReels(restart = true)
        } else if (state.value.section == Section.RECOMMEND &&
            state.value.detail == null) {
            refresh()
        }
    }

    /**
     * A feed-mode switch creates a fresh official cursor and resets the current
     * queue and playback; it does not touch the home recommendation catalog.
     */
    fun setReelsMode(mode: String) {
        if (mode !in setOf("all", "real") || mode == state.value.reelsMode) return
        prefs.edit().putString("reels_mode", mode).apply()
        val wasInFeed = state.value.feedMode
        if (wasInFeed) closePlayer()
        mutable.update { it.copy(reelsMode = mode) }
        if (wasInFeed) startFeed()
    }

    /** Explicit retry after a temporary upstream failure; never silently change to a fixed list. */
    fun retryOfficialReels() {
        if (!state.value.feedMode || state.value.feedLoading) return
        if (state.value.feedItems.isEmpty()) {
            mutable.update { it.copy(preparingDetail = true) }
        }
        loadOfficialReels()
    }
    private val prefetchedFeedDetails = linkedMapOf<String, DramaDetail>()
    /** Reuse the last few series details on reverse swipes, without refetching. */
    private val visitedFeedDetails = linkedMapOf<String, DramaDetail>()
    private fun rememberFeedDetail(detail: DramaDetail) {
        visitedFeedDetails.remove(detail.drama.id)
        visitedFeedDetails[detail.drama.id] = detail
        while (visitedFeedDetails.size > 4)
            visitedFeedDetails.remove(visitedFeedDetails.keys.first())
    }

    private fun prefetchFollowingFeedItem(index: Int) {
        val current = state.value
        if (!current.feedMode) return
        val next = current.feedItems.drop(index + 1)
            .firstOrNull { it.id !in current.hiddenRecommendationIds } ?: return
        if (reelWarmSeriesId == next.id && reelWarmVideo != null) return
        feedPrefetchJob?.cancel()
        feedPrefetchJob = viewModelScope.launch {
            var warming: RemoteVideo? = null
            val activeAtStart = state.value.detail?.drama?.id
            try {
                val detail = prefetchedFeedDetails[next.id] ?: nativeSource.detail(next.id)
                if (!isUpcomingReel(next.id)) return@launch
                prefetchedFeedDetails[next.id] = detail
                while (prefetchedFeedDetails.size > 3)
                    prefetchedFeedDetails.remove(prefetchedFeedDetails.keys.first())
                diagnostics.record("FEED", "NEXT_DETAIL_PREFETCHED")
                if (!state.value.preloadNext || detail.episodes.isEmpty()) return@launch
                // Do not compete with current video startup. Prepare the next
                // byte range only after Media3 has rendered the active first frame.
                val active = state.value.detail?.episodes?.getOrNull(state.value.episodeIndex)
                if (active == null || firstFrameFor != active.vid) return@launch
                val first = detail.episodes.first().vid
                val quality = state.value.qualityLimit
                warming = nativeSource.openVideo(first, qualityLimit = quality)
                if (!isUpcomingReel(next.id) || !state.value.preloadNext ||
                    state.value.qualityLimit != quality) return@launch
                clearReelWarmVideo()
                reelWarmSeriesId = next.id
                reelWarmVid = first
                reelWarmQuality = quality
                reelWarmVideo = warming
                warming = null
                diagnostics.record("FEED", "NEXT_VIDEO_PREPARED")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                diagnostics.record("FEED", "NEXT_PRELOAD_UNAVAILABLE")
            } finally {
                warming?.close()
                // Avoid competing playback-model requests. After the next
                // drama is warmed (or unavailable), give natural next-episode
                // playback its usual prefetch opportunity too.
                val current = state.value
                if (activeAtStart != null &&
                    current.detail?.drama?.id == activeAtStart &&
                    current.preloadNext && current.episodeIndex < current.detail.episodes.lastIndex &&
                    firstFrameFor == current.detail.episodes[current.episodeIndex].vid) {
                    preloadFollowingEpisode()
                }
            }
        }
    }

    private fun isUpcomingReel(seriesId: String): Boolean {
        val current = state.value
        return current.feedMode && current.feedItems.drop(current.feedIndex + 1)
            .firstOrNull { it.id !in current.hiddenRecommendationIds }?.id == seriesId
    }
    private var firstFrameFor: String? = null
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true),
            likedDramaIds = prefs.getStringSet("local_liked_dramas", emptySet())?.toSet().orEmpty(),
            watchLater = readWatchLater(),
            offlineEpisodes = readOfflineEpisodes(),
            favoriteGroups = library.favoriteGroups(),
            completedDramaIds = prefs.getStringSet("completed_drama_ids_v1", emptySet())?.toSet().orEmpty(),
            hiddenRecommendationIds = prefs.getStringSet("hidden_recommendations_v1", emptySet())?.toSet().orEmpty(),
            themeStyle = prefs.getString("theme_style", "warm")?.takeIf { it in setOf("warm", "immersive", "cards") } ?: "warm",
            themeMode = prefs.getString("theme_mode", "light")?.takeIf { it in setOf("system", "light", "dark") } ?: "light",
            navigationStyle = prefs.getString("navigation_style", "classic")?.takeIf { it in setOf("classic", "glass") } ?: "classic",
            reelsMode = prefs.getString("reels_mode", "all")
                ?.takeIf { it == "all" || it == "real" } ?: "all",
            autoPlayNext = prefs.getBoolean("auto_play_next", true),
            preloadNext = prefs.getBoolean("preload_next_episode", true),
            episodeUpdateNotificationsEnabled = prefs.getBoolean("episode_update_notifications", false),
            skipIntroSeconds = prefs.getInt("skip_intro_seconds", 0).coerceIn(0, 120),
            skipOutroSeconds = prefs.getInt("skip_outro_seconds", 0).coerceIn(0, 120),
            retryPlaybackAutomatically = prefs.getBoolean("playback_auto_retry", true),
            recentSearches = recentQueries(),
            defaultPlaybackSpeed = prefs.getFloat("default_playback_speed", 1f).coerceIn(0.75f, 3f),
            qualityLimit = prefs.getInt("quality_limit", 0)
                .takeIf { it in setOf(0, 360, 480, 720, 1080) } ?: 0,
            logEnabled = diagnostics.enabled,
            danmakuEnabled = prefs.getBoolean("danmaku_enabled", false),
            danmakuSpeed = prefs.getInt("danmaku_speed", 1).coerceIn(0, 2),
            danmakuDensity = prefs.getInt("danmaku_density", 4).coerceIn(1, 5),
            danmakuFontSize = prefs.getInt("danmaku_font_size", 14).coerceIn(12, 18))
    )
    val state = mutable.asStateFlow()
    private var appliedRecommendationSession: FeedAccount? = null

    init {
        if (state.value.episodeUpdateNotificationsEnabled) {
            runCatching { TrackingUpdatesWorker.schedule(application, true) }
                .onFailure { diagnostics.record("TRACKING", "STARTUP_SCHEDULER_UNAVAILABLE") }
        }
        diagnostics.record("APP", "START")
        // The saved login is verified asynchronously after homepage init.
        // If account recommendations were previously opted in, a late
        // profile verification must refresh the anonymous starter feed.
        // Log-out and account switches must likewise invalidate that cursor.
        viewModelScope.launch {
            account.state.collect {
                applyRecommendationAccountIfChanged()
            }
        }
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

    /** Navigate from verified detail labels to the existing native search results.
     * The catalog owns the search request; don't show an inert tag or a fake tag page.
     */
    fun openTagResults(label: String) {
        val term = label.trim().take(80)
        if (term.isBlank() || state.value.detail == null ||
            !state.value.detailPageVisible) return
        closePlayer()
        setSearch(term)
        search()
        diagnostics.record("DETAIL", "TAG_SEARCH")
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
        val feedAccount = account.recommendationAccount()
        appliedRecommendationSession = feedAccount
        nativeSource.setAccountRecommendations(feedAccount)
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
                // Catalog pagination is only for the home grid. Reels have their
                // own official session and must never advance from this fixed 3-item list.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingFeedAdvance = false
                if (version == requestId) mutable.update { it.copy(moreLoading = false,
                    error = errorText("加载更多", e)) }
            }
        }
    }

    /** Public site enrichment is optional, scoped to the active series and never blocks playback. */
    private fun loadLabels(seriesId: String) {
        val generation = ++labelsGeneration
        labelsJob?.cancel()
        labelsJob = viewModelScope.launch {
            try {
                val labels = nativeSource.websiteLabels(seriesId)
                if (generation == labelsGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    mutable.update { current ->
                        val d = current.detail ?: return@update current
                        current.copy(detail = d.copy(drama = d.drama.copy(
                            actors = labels.actors.ifEmpty { d.drama.actors },
                            categories = labels.categories.ifEmpty { d.drama.categories },
                        )))
                    }
                    diagnostics.record("DETAIL", "TAGS_READY")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                diagnostics.record("DETAIL", "TAGS_UNAVAILABLE")
            }
            // Series reviews and actors have a separate success/failure path.
            try {
                val extras = nativeSource.publicDetail(seriesId)
                if (generation == labelsGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    mutable.update { current ->
                        val d = current.detail ?: return@update current
                        if (d.drama.id != seriesId) current
                        else current.copy(
                            publicDetail = extras,
                            detail = d.copy(drama = d.drama.copy(
                                actors = (d.drama.actors + extras.cast.map { it.name })
                                    .distinct().take(20),
                            )),
                        )
                    }
                    diagnostics.record("DETAIL", "PUBLIC_SERIES_READY")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                diagnostics.record("DETAIL", "PUBLIC_SERIES_UNAVAILABLE")
            }
        }
    }

    private fun clearSocial() {
        labelsGeneration++
        labelsJob?.cancel()
        socialGeneration++
        socialJob?.cancel()
        mutable.update {
            it.copy(socialForSeries = null, socialStats = null,
                publicDetail = null, openHongguoMetadata = null, openHongguoSeasons = emptyList(),
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
                // Official app series_status is updated asynchronously; the
                // public page may not contain a human-readable end badge.
                candidate.completionStatus?.let { verified ->
                    mutable.update { current ->
                        val d = current.detail
                        if (d == null || d.drama.id != seriesId) current
                        else current.copy(detail = d.copy(drama = d.drama.copy(
                            completionStatus = verified
                        )))
                    }
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
                            val (stats, metadata) = nativeSource.appEnrichment(seriesId)
                            applyStats(stats, "APP")
                            if (metadata != null && generation == socialGeneration &&
                                state.value.detail?.drama?.id == seriesId) {
                                mutable.update { current ->
                                    val d = current.detail
                                    if (d?.drama?.id != seriesId) current
                                    else current.copy(
                                        openHongguoMetadata =
                                            OpenHongguoMetadataParser.merge(
                                                current.openHongguoMetadata, metadata),
                                        detail = d.copy(drama = d.drama.copy(
                                            categories = (d.drama.categories + metadata.tags)
                                                .distinct().take(16),
                                            actors = (d.drama.actors + metadata.actors)
                                                .distinct().take(20),
                                            completionStatus = d.drama.completionStatus
                                                ?: metadata.statusText,
                                        )),
                                    )
                                }
                                diagnostics.record("METADATA", "OPENHONGGUO_DETAIL_READY")
                                diagnostics.record("METADATA",
                                    if (metadata.authorAiStatement != null)
                                        "AI_AUTHOR_DECLARATION_PRESENT"
                                    else "AI_AUTHOR_DECLARATION_ABSENT")
                            }
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

    private var accountReturnModal = Modal.NONE
    fun show(modal: Modal) {
        if (modal == Modal.ACCOUNT && state.value.modal != Modal.ACCOUNT)
            accountReturnModal = state.value.modal
        mutable.update { it.copy(modal = modal) }
        if (modal == Modal.SETTINGS) refreshDiagnostics()
    }
    fun closeModal() = show(Modal.NONE)
    fun closeAccount() {
        account.closeScreen()
        show(if (accountReturnModal == Modal.SETTINGS) Modal.SETTINGS else Modal.NONE)
        if (state.value.detail == null && !state.value.preparingDetail &&
            state.value.section == Section.RECOMMEND) refresh()
    }

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

    /** Separate recommendation plan for 猜你喜欢; never overwrites related-work cell 547. */
    fun loadGuesses(seriesId: String) {
        if (!seriesId.matches(Regex("[0-9]{1,30}"))) return
        guessesJob?.cancel()
        val generation = ++guessesGeneration
        mutable.update { it.copy(guessWorks = emptyList(), guessesLoading = true,
            guessesError = null) }
        guessesJob = viewModelScope.launch {
            try {
                val candidates = withContext(Dispatchers.IO) {
                    openHongguo.related(seriesId)
                }
                if (generation == guessesGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    val relatedIds = state.value.relatedWorks.mapTo(hashSetOf()) { it.drama.id }
                    val seasonIds = state.value.openHongguoSeasons.mapTo(hashSetOf()) { it.id }
                    mutable.update { it.copy(
                        guessWorks = candidates.filter { row ->
                            row.id != seriesId && row.id !in relatedIds &&
                                row.id !in seasonIds
                        }.distinctBy(Drama::id).take(24),
                        guessesLoading = false,
                    ) }
                    diagnostics.record("RECOMMEND", "GUESS_READY")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == guessesGeneration &&
                    state.value.detail?.drama?.id == seriesId) {
                    mutable.update { it.copy(
                        guessesLoading = false, guessesError = "暂时无法加载猜你喜欢"
                    ) }
                    diagnostics.record("RECOMMEND", "GUESS_UNAVAILABLE")
                }
            }
        }
    }

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
                        guessWorks = it.guessWorks.filterNot { item ->
                            rows.any { related -> related.drama.id == item.id }
                        },
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

    /**
     * The "刷短剧" button starts with the official feed's first item.
     * An explicitly selected card can still be used as a one-time seed.
     */
    fun startFeed(drama: Drama? = null) {
        val current = state.value
        if (current.section != Section.RECOMMEND) return
        if (drama != null && drama.id in current.hiddenRecommendationIds) return
        officialFeedJob?.cancel()
        officialFeedGeneration++
        pendingFeedAdvance = false
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
        prefetchedFeedDetails.clear()
        visitedFeedDetails.clear()
        val feedAccount = account.recommendationAccount()
        appliedRecommendationSession = feedAccount
        nativeSource.setAccountRecommendations(feedAccount)
        // Official App feed is separate from "为你推荐". Never show its already-loaded
        // home cards as the first swipe queue, even when the server repeats them.
        homeFeedExcludedIds = current.items.mapTo(linkedSetOf()) { it.id }
        mutable.update { it.copy(
            feedMode = true,
            feedItems = drama?.let(::listOf).orEmpty(),
            feedIndex = 0,
            feedLoading = false,
            feedHasMore = true,
            feedError = null,
            preparingDetail = drama == null,
        ) }
        if (drama != null) {
            rememberSwiped(drama.id)
            open(drama, resumeEp = 1, feed = true)
        }
        loadOfficialReels(restart = true)
    }

    /** Both manual vertical swipe and natural episode end use the live official queue. */
    fun navigateFeed(direction: Int) {
        val current = state.value
        if (!current.feedMode || direction !in setOf(-1, 1)) return
        val index = OfficialReelsPolicy.nextIndex(
            current.feedItems, current.feedIndex, direction,
            current.hiddenRecommendationIds)
        val selected = index?.let(current.feedItems::get)
        if (selected != null && selected.id != current.detail?.drama?.id) {
            pendingFeedAdvance = false
            mutable.update { it.copy(feedIndex = index) }
            updateNotificationNavigation()
            rememberSwiped(selected.id)
            open(selected, resumeEp = 1, feed = true)
            prefetchFollowingFeedItem(index)
            if (direction > 0 && OfficialReelsPolicy.needsMore(
                    current.feedItems, index, current.hiddenRecommendationIds,
                    current.feedHasMore, current.feedLoading)) loadOfficialReels()
        } else if (direction > 0 && current.feedHasMore) {
            pendingFeedAdvance = true
            if (!current.feedLoading) loadOfficialReels()
        }
    }

    fun exitFeedToEpisode(index: Int) {
        officialFeedJob?.cancel()
        officialFeedGeneration++
        homeFeedExcludedIds = emptySet()
        pendingFeedAdvance = false
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
        mutable.update { it.copy(feedMode = false, feedLoading = false) }
        play(index)
    }

    /** Cloud rows are opened only by user action; local library remains local.
     * The selected cloud episode is matched by vid before seek checkpoint use.
     */
    fun openCloudProgress(progress: CloudProgress) {
        val id = progress.series.id
        if (!id.matches(Regex("[0-9]{1,30}"))) return
        if (progress.episodeId.matches(Regex("[0-9]{1,30}"))) {
            library.saveCheckpoint(
                id, progress.episodeId, progress.episodeIndex + 1,
                progress.position, progress.duration,
            )
        }
        closeAccount()
        open(Drama(id, progress.series.title, progress.series.cover),
            resumeEp = progress.episodeIndex + 1, showDetails = false)
    }

    fun openCloudDrama(series: Series) {
        if (!series.id.matches(Regex("[0-9]{1,30}"))) return
        closeAccount()
        open(Drama(series.id, series.title, series.cover))
    }

    fun open(drama: Drama, resumeEp: Int? = null, feed: Boolean = false,
        showDetails: Boolean = false) {
        detailPlaybackRetained = false
        detailPlaybackShouldResume = false
        BackgroundPlaybackService.active?.pauseForTransition()
        sdkDetailGeneration++
        sdkDetailJob?.cancel()
        ++relatedGeneration
        relatedJob?.cancel()
        ++guessesGeneration
        guessesJob?.cancel()
        clearSocial()
        clearEngagement()
        detailJob?.cancel()
        val version = ++detailId
        firstFrameFor = null
        clearPreloadedNext()
        startEpisodeHint = resumeEp
        mutable.update { it.copy(offlinePlaybackUri = null, detail = null, preparingDetail = true,
            feedMode = feed, detailPageVisible = showDetails,
            detailEntryEnabled = false, relatedWorks = emptyList(),
            relatedSeries = null,
            relatedLoading = false, relatedError = null,
            guessWorks = emptyList(), guessesLoading = false, guessesError = null,
            openHongguoLoading = false, openHongguoSeasons = emptyList(),
            playbackError = null, playbackUrl = null, playing = false) }
        // Official swipe recommendations must use their matching native detail API.
        val api = if (feed) null else source
        diagnostics.record("DETAIL", "START")
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val cachedDetail = if (feed) visitedFeedDetails[drama.id] else null
                val prefetchedDetail = prefetchedFeedDetails.remove(drama.id)
                val result = api?.detail(drama.id)
                    ?: prefetchedDetail
                    ?: cachedDetail
                    ?: nativeSource.detail(drama.id)
                if (feed && (prefetchedDetail != null || cachedDetail != null))
                    diagnostics.record("FEED", if (prefetchedDetail != null)
                        "DETAIL_PREFETCH_HIT" else "DETAIL_RECENT_HIT")
                if (version != detailId) return@launch
                val resolved = result.copy(drama = result.drama.copy(
                    title = result.drama.title.ifBlank { drama.title },
                    cover = result.drama.cover.ifBlank { drama.cover },
                    total = result.episodes.size,
                    synopsis = result.drama.synopsis.ifBlank { drama.synopsis },
                    completionStatus = result.drama.completionStatus ?: drama.completionStatus,
                    actors = result.drama.actors.ifEmpty { drama.actors },
                    categories = result.drama.categories.ifEmpty { drama.categories },
                ))
                val remembered = startEpisodeHint ?: state.value.library
                    .firstOrNull { it.drama.id == drama.id }?.episode
                val index = resolved.episodes.indexOfFirst { it.index == remembered }
                    .takeIf { it >= 0 } ?: 0
                if (feed) rememberFeedDetail(resolved)
                mutable.update { it.copy(detail = resolved, episodeIndex = index,
                    preparingDetail = false) }
                diagnostics.record("DETAIL", "SUCCESS", elapsed(started))
                loadSocialStats(resolved.drama.id)
                if (api == null) loadLabels(resolved.drama.id)
                loadRelated(resolved.drama.id)
                if (showDetails) {
                    loadSdkDetails(resolved.drama.id)
                    loadGuesses(resolved.drama.id)
                }
                else play(index)
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

    /**
     * The service, not the lifecycle-gated Compose UI, starts each media item.
     * This is essential for notification next/previous while the phone is locked.
     */
    private suspend fun launchBackgroundMedia(
        detail: DramaDetail,
        index: Int,
        url: String,
        remote: RemoteVideo?,
        resumeMs: Long,
        expectedGeneration: Long,
    ) {
        withTimeout(8_000) {
            BackgroundPlaybackService.playerFlow.filterNotNull().first()
        }
        if (expectedGeneration != detailId ||
            state.value.detail?.drama?.id != detail.drama.id) return
        val current = detail.episodes[index]
        val currentState = state.value
        BackgroundPlaybackService.active?.configure(
            key = "${current.vid}|$url|${remote?.hashCode() ?: 0}",
            vid = current.vid,
            url = url,
            remote = remote,
            qualityLimit = currentState.qualityLimit,
            drama = detail.drama.title,
            episode = current.index,
            totalEpisodes = detail.episodes.size,
            cover = cover(detail.drama.cover),
            positionMs = resumeMs,
            introMs = currentState.skipIntroSeconds * 1000L,
            endingMs = currentState.skipOutroSeconds * 1000L,
            autoRetry = currentState.retryPlaybackAutomatically,
            firstFrame = { firstFrame(current.vid) },
            backgroundProgress = { confirmedBackgroundPlayback(current.vid) },
            finished = {
                finishEpisode(current.vid)
                nextEpisode(current.vid)
            },
            failed = { mediaError(current.vid, it) },
            checkpoint = { position, duration ->
                savePlaybackPosition(current.vid, position, duration)
            },
            timerExpired = ::shouldPauseForTimer,
        )
        updateNotificationNavigation()
    }

    /** Keep notification skip decisions independent of Activity/Compose lifecycle. */
    private fun updateNotificationNavigation() {
        val s = state.value
        val detail = s.detail ?: return
        val earlier = s.feedItems.take(s.feedIndex)
            .any { it.id !in s.hiddenRecommendationIds }
        val later = s.feedItems.drop(s.feedIndex + 1)
            .any { it.id !in s.hiddenRecommendationIds }
        val policy = BackgroundNavigationPolicy.availability(
            feedMode = s.feedMode,
            selectedIndex = s.episodeIndex,
            episodeCount = detail.episodes.size,
            previousFeedExists = earlier,
            nextFeedAvailableOrPending = later || s.feedHasMore || s.feedLoading,
        )
        BackgroundPlaybackService.active?.configureNavigation(
            policy.previous, policy.next,
        ) { direction ->
            if (state.value.feedMode) navigateFeed(direction)
            else playIfDifferent(state.value.episodeIndex + direction)
        }
    }

    private var serviceRecoveryRequested = false

    /**
     * Foreground-only repair for a service that the OS has already destroyed.
     * Never restart playback when the user intentionally paused or closed it.
     * A stopped service cannot reuse its old encrypted RemoteVideo; play()
     * fetches a fresh stream and restores the persisted watch checkpoint.
     */
    fun recoverPlaybackAfterServiceLoss() {
        if (serviceRecoveryRequested || BackgroundPlaybackService.active != null ||
            BackgroundPlaybackService.playerFlow.value != null) return
        val snapshot = state.value
        if (snapshot.detail == null || snapshot.detailPageVisible ||
            snapshot.playbackUrl == null || !snapshot.playing ||
            snapshot.playbackError != null) return
        serviceRecoveryRequested = true
        diagnostics.record("BACKGROUND", "FOREGROUND_SERVICE_MISSING")
        viewModelScope.launch {
            delay(600)
            try {
                val current = state.value
                if (BackgroundPlaybackService.active == null &&
                    BackgroundPlaybackService.playerFlow.value == null &&
                    current.detail != null && !current.detailPageVisible &&
                    current.playbackUrl != null && current.playing &&
                    current.playbackError == null) {
                    // Discard any cached decoder/source from the dead session.
                    // The next media load performs the existing authenticated
                    // stream request rather than reopening a stale data source.
                    clearPreloadedNext()
                    diagnostics.record("BACKGROUND", "FOREGROUND_RECONNECT")
                    play(current.episodeIndex)
                }
            } finally {
                serviceRecoveryRequested = false
            }
        }
    }

    fun play(index: Int) {
        detailPlaybackRetained = false
        detailPlaybackShouldResume = false
        val detail = state.value.detail ?: return
        if (index !in detail.episodes.indices) return
        // Notification commands run while the Activity is STOPPED. Reuse the
        // running foreground service rather than making a forbidden background
        // service start request on Android 12+.
        val serviceStarted = BackgroundPlaybackService.active != null || runCatching {
            getApplication<Application>().startService(
                Intent(getApplication<Application>(), BackgroundPlaybackService::class.java))
        }.isSuccess
        if (!serviceStarted) diagnostics.record("PLAYBACK", "SERVICE_START_DENIED")
        BackgroundPlaybackService.active?.pauseForTransition()
        mutable.update { it.copy(detailPageVisible = false) }
        if (state.value.offlinePlaybackUri != null) {
            val uri = state.value.offlinePlaybackUri ?: return
            val resume = state.value.resumePositionMs
            val currentGeneration = detailId
            mutable.update { it.copy(episodeIndex = index,
                playbackUrl = uri, playing = true) }
            if (serviceStarted) viewModelScope.launch {
                try {
                    launchBackgroundMedia(
                        detail, index, uri, null, resume, currentGeneration)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutable.update { it.copy(playbackError = errorText("离线播放", e),
                        playing = false) }
                }
            }
            return
        }
        clearEngagement()
        val api = if (state.value.feedMode) null else source
        val version = ++detailId
        val vid = detail.episodes[index].vid
        detailJob?.cancel()
        closeNativeVideo()
        val reelCached = state.value.feedMode &&
            reelWarmSeriesId == detail.drama.id &&
            reelWarmVid == vid && reelWarmQuality == state.value.qualityLimit
        val cachedNative = if (reelCached) reelWarmVideo
            else if (preloadedNextVid == vid &&
                preloadedNextQuality == state.value.qualityLimit) preloadedNextVideo
            else BackgroundPlaybackService.active?.takeRecentVideo(
                vid, state.value.qualityLimit)
        if (cachedNative != null)
            diagnostics.record("PLAYBACK", "PREPARED_STREAM_REUSED")
        // Transfer the warmed reel's ownership to the active playback request.
        if (reelCached) {
            reelWarmVideo = null
            reelWarmVid = null
            reelWarmSeriesId = null
        }
        preloadEpisodeJob?.cancel()
        preloadEpisodeJob = null
        if (cachedNative == null) preloadedNextVideo?.close()
        preloadedNextVideo = null
        preloadedNextVid = null
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
                    if (serviceStarted) launchBackgroundMedia(
                        detail, index, url, null, checkpoint, version)
                } else {
                    val ahead = detail.episodes.getOrNull(index + 1)?.vid
                    val quality = state.value.qualityLimit
                    val video = cachedNative ?: try {
                        nativeSource.openVideo(vid, ahead, quality)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (first: Exception) {
                        if (!PlaybackProblem.isEmptyPlaybackModel(first)) throw first
                        // A transient empty playback-model response should not
                        // immediately strand the reel on its error screen.
                        // Retry once without metadata-ahead, to reduce work
                        // on the upstream request. Never retry codecs, 403s or
                        // other unrelated failures automatically.
                        diagnostics.record("PLAYBACK", "PRIMARY_EMPTY_RETRY")
                        delay(1_500L)
                        val current = state.value
                        if (version != detailId ||
                            current.detail?.episodes
                                ?.getOrNull(current.episodeIndex)?.vid != vid ||
                            current.qualityLimit != quality) return@launch
                        try {
                            nativeSource.openVideo(vid, null, quality)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (again: Exception) {
                            if (PlaybackProblem.isEmptyPlaybackModel(again)) {
                                diagnostics.record("PLAYBACK", "PRIMARY_EMPTY_RETRY_EXHAUSTED")
                            }
                            throw again
                        }
                    }
                    if (version != detailId) {
                        video.close()
                        return@launch
                    }
                    activeNativeVideo = video
                    activeNativeVid = vid
                    val nativeUri = "native://episode/" + vid
                    mutable.update { it.copy(playbackUrl = nativeUri,
                        playing = true) }
                    if (serviceStarted) launchBackgroundMedia(
                        detail, index, nativeUri, video, checkpoint, version)
                }
                diagnostics.record("PLAYBACK", "SOURCE_READY", elapsed(started))
            } catch (e: CancellationException) {
                if (cachedNative != null && activeNativeVideo !== cachedNative) cachedNative.close()
                throw e
            } catch (e: Exception) {
                if (cachedNative != null && activeNativeVideo !== cachedNative) cachedNative.close()
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
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
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
        // The next episode is more important than the next drama while the
        // current series has remaining episodes; avoid competing range requests.
        if (s.feedMode &&
            s.feedItems.drop(s.feedIndex + 1).any {
                it.id !in s.hiddenRecommendationIds
            }) {
            // In swipe mode the immediately adjacent drama has priority.
            // The next episode is prefetched after this neighboring video,
            // rather than consuming the same network/identity budget at once.
            prefetchFollowingFeedItem(s.feedIndex)
        } else preloadFollowingEpisode()
    }


    /** Background video has no attached surface, so onRenderedFirstFrame may
     * never fire. Three seconds of actual progressing ExoPlayer playback is
     * enough to validate the session for checkpoints, without claiming a frame
     * was rendered.
     */
    fun confirmedBackgroundPlayback(expectedVid: String) {
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (ep.vid != expectedVid || firstFrameFor == ep.vid) return
        firstFrameFor = ep.vid
        diagnostics.record("MEDIA", "BACKGROUND_PROGRESS_CONFIRMED")
        library.record(s.detail.drama, ep.index)
        updateLibrary()
        if (s.feedMode &&
            s.feedItems.drop(s.feedIndex + 1).any {
                it.id !in s.hiddenRecommendationIds
            }) {
            // In swipe mode the immediately adjacent drama has priority.
            // The next episode is prefetched after this neighboring video,
            // rather than consuming the same network/identity budget at once.
            prefetchFollowingFeedItem(s.feedIndex)
        } else preloadFollowingEpisode()
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
        if (source == null && s.offlinePlaybackUri == null) account.uploadProgress(
            Series(s.detail.drama.id, s.detail.drama.title, s.detail.drama.cover),
            expectedVid, s.episodeIndex, positionMs, durationMs,
            s.detail.episodes.size,
        )
    }

    fun finishEpisode(expectedVid: String) {
        val s = state.value
        val ep = s.detail?.episodes?.getOrNull(s.episodeIndex) ?: return
        if (ep.vid == expectedVid) {
            library.clearCheckpoint(s.detail.drama.id, expectedVid)
            if (s.offlinePlaybackUri == null &&
                s.episodeIndex == s.detail.episodes.lastIndex) {
                val finished = state.value.completedDramaIds + s.detail.drama.id
                prefs.edit().putStringSet("completed_drama_ids_v1", finished).apply()
                mutable.update { it.copy(completedDramaIds = finished) }
            }
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
        when (ReelAutoplayPolicy.onEnded(
            s.episodeIndex, s.detail?.episodes?.size ?: 0,
            s.autoPlayNext, s.feedMode,
        )) {
            ReelAutoplayPolicy.Action.STOP ->
                diagnostics.record("PLAYBACK", "AUTO_NEXT_STOPPED")
            ReelAutoplayPolicy.Action.NEXT_EPISODE -> {
                diagnostics.record("PLAYBACK",
                    if (s.feedMode) "REEL_NEXT_EPISODE" else "NEXT_EPISODE")
                play(s.episodeIndex + 1)
            }
            ReelAutoplayPolicy.Action.NEXT_DRAMA -> {
                diagnostics.record("REELS", "SERIES_FINISHED_NEXT_DRAMA")
                navigateFeed(1)
            }
        }
    }

    private var detailPlaybackRetained = false
    private var detailPlaybackShouldResume = false

    /** Keep the Media3 session and encrypted source while showing details. */
    fun showDramaDetails() {
        val s = state.value
        val detail = s.detail ?: return
        if (s.detailPageVisible) return
        val vid = detail.episodes.getOrNull(s.episodeIndex)?.vid
        val wasPlaying = vid?.let { BackgroundPlaybackService.active?.pauseForDetails(it) }
        detailPlaybackRetained = wasPlaying != null && s.playbackUrl != null
        detailPlaybackShouldResume = wasPlaying == true
        if (!detailPlaybackRetained) {
            // No reusable live media item; keep the existing reload fallback.
            BackgroundPlaybackService.stopActive("OPEN_DETAIL_NO_SESSION")
            closeNativeVideo()
            clearPreloadedNext()
        }
        loadSdkDetails(detail.drama.id)
        loadGuesses(detail.drama.id)
        ++detailId
        detailJob?.cancel()
        mutable.update { it.copy(
            detailPageVisible = true,
            detailEntryEnabled = true,
            playbackUrl = if (detailPlaybackRetained) it.playbackUrl else null,
            playing = if (detailPlaybackRetained) detailPlaybackShouldResume else false,
            playbackError = null,
        ) }
        diagnostics.record("DETAIL",
            if (detailPlaybackRetained) "SESSION_RETAINED" else "SESSION_UNAVAILABLE")
    }

    /** Reattach to the same media item; reload only if its service was lost. */
    fun backFromDramaDetails() {
        sdkDetailGeneration++
        sdkDetailJob?.cancel()
        val s = state.value
        if (!s.detailPageVisible) return
        val vid = s.detail?.episodes?.getOrNull(s.episodeIndex)?.vid
        val resumed = detailPlaybackRetained && s.playbackUrl != null &&
            vid != null && BackgroundPlaybackService.active
                ?.resumeFromDetails(vid, detailPlaybackShouldResume) == true
        val shouldPlay = detailPlaybackShouldResume
        detailPlaybackRetained = false
        detailPlaybackShouldResume = false
        if (resumed) {
            mutable.update { it.copy(
                detailPageVisible = false,
                detailEntryEnabled = false,
                playing = shouldPlay,
            ) }
            diagnostics.record("DETAIL", "RETURN_REUSED_PLAYER")
        } else if (s.detailEntryEnabled && s.detail != null) {
            diagnostics.record("DETAIL", "RETURN_RELOAD_FALLBACK")
            play(s.episodeIndex)
        } else closePlayer()
    }

    /** Normal player back returns to the catalog, not an automatic detail step. */
    fun backFromPlayer() = closePlayer()

    fun closePlayer() {
        detailPlaybackRetained = false
        detailPlaybackShouldResume = false
        BackgroundPlaybackService.stopActive("CLOSE_PLAYER")
        sdkDetailGeneration++
        sdkDetailJob?.cancel()
        homeFeedExcludedIds = emptySet()
        pendingFeedAdvance = false
        officialFeedJob?.cancel()
        officialFeedGeneration++
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
        prefetchedFeedDetails.clear()
        visitedFeedDetails.clear()
        ++relatedGeneration
        relatedJob?.cancel()
        ++guessesGeneration
        guessesJob?.cancel()
        clearSocial()
        clearEngagement()
        ++detailId
        detailJob?.cancel()
        closeNativeVideo()
        clearPreloadedNext()
        firstFrameFor = null
        mutable.update { it.copy(detail = null, playbackUrl = null,
            offlinePlaybackUri = null,
            feedMode = false, detailPageVisible = false,
            detailEntryEnabled = false,
            feedItems = emptyList(), feedLoading = false,
            feedHasMore = false, feedError = null,
            relatedSeries = null, relatedWorks = emptyList(),
            relatedLoading = false, relatedError = null,
            guessWorks = emptyList(), guessesLoading = false, guessesError = null,
            openHongguoMetadata = null, openHongguoSeasons = emptyList(),
            openHongguoLoading = false,
            resumePositionMs = 0L, playbackError = null,
            preparingDetail = false, playing = false) }
    }

    fun setFavoriteGroup(seriesId: String, group: String) {
        val exists = state.value.favorites.any { it.drama.id == seriesId }
        if (!exists) return
        runCatching { library.setFavoriteGroup(seriesId, group) }
            .onSuccess {
                mutable.update { it.copy(favoriteGroups = library.favoriteGroups()) }
            }
    }

    fun setDramaCompleted(seriesId: String, completed: Boolean) {
        if (!seriesId.matches(Regex("[0-9]{1,30}"))) return
        val updated = if (completed) state.value.completedDramaIds + seriesId
            else state.value.completedDramaIds - seriesId
        prefs.edit().putStringSet("completed_drama_ids_v1", updated).apply()
        mutable.update { it.copy(completedDramaIds = updated) }
    }

    fun toggleFavoriteForDrama(drama: Drama) {
        if (!drama.id.matches(Regex("[0-9]{1,30}"))) return
        val episode = state.value.library.firstOrNull { it.drama.id == drama.id }?.episode ?: 1
        library.toggle(drama, episode)
        updateLibrary()
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
        toggleLocalLikeForDrama(id)
    }

    /** Local reaction from a cloud gallery card; never uploads a server like. */
    fun toggleLocalLikeForDrama(id: String) {
        if (!id.matches(Regex("[0-9]{1,30}"))) return
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
        it.copy(library = library.list(false), favorites = library.list(true),
            favoriteGroups = library.favoriteGroups())
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
    fun downloadNative(vid: String, uri: Uri, previousTaskId: String? = null) {
        if (!vid.matches(Regex("[0-9]{1,30}"))) return
        val existing = previousTaskId?.let { retryableDownloads[it] }
        if (previousTaskId != null && (existing == null || existing.vid != vid ||
            existing.uri != uri)) return
        val currentDetail = state.value.detail
        val savedDrama = existing?.drama ?: currentDetail?.drama
        val savedEpisode = existing?.episode ?: currentDetail?.episodes?.firstOrNull { it.vid == vid }
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val taskId = previousTaskId ?: ("local-" + java.util.UUID.randomUUID().toString())
        synchronized(retryableDownloads) {
            retryableDownloads[taskId] = LocalDownloadTarget(vid, uri, savedDrama, savedEpisode)
        }
        mutable.update { s ->
            s.copy(downloadTasks = (
                listOf(DownloadTask(taskId, "等待下载", 0, 1, emptyList())) +
                    s.downloadTasks.filterNot { it.id == taskId }
            ).take(40), modal = Modal.DOWNLOAD)
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var acquired = false
            var video: RemoteVideo? = null
            val started = System.nanoTime()
            diagnostics.record("DOWNLOAD", "START")
            try {
                localDownloadSlots.acquire()
                acquired = true
                video = nativeSource.openVideo(vid)
                val total = video.total
                if (total <= 0) throw IOException("下载资源长度无效")
                val output = getApplication<Application>()
                    .contentResolver.openOutputStream(uri, "wt")
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
                synchronized(retryableDownloads) { retryableDownloads.remove(taskId) }
                if (savedDrama != null && savedEpisode != null) {
                    val entry = OfflineEpisode(savedDrama.id, savedDrama.title, savedDrama.cover,
                        savedEpisode.index, vid, uri.toString(), System.currentTimeMillis())
                    saveOfflineEpisodes(listOf(entry) + state.value.offlineEpisodes.filterNot {
                        it.documentUri == uri.toString()
                    })
                }
                mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                    if (it.id == taskId) it.copy(status = "已保存到所选文件", done = 1) else it
                }) }
            } catch (e: CancellationException) {
                diagnostics.record("DOWNLOAD", "CANCELLED")
                mutable.update { s -> s.copy(downloadTasks = s.downloadTasks.map {
                    if (it.id == taskId) it.copy(status = "已取消 · 可重试（文件未完成）") else it
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
                if (acquired) localDownloadSlots.release()
                val finishedJob = currentCoroutineContext()[Job]
                synchronized(localDownloadJobs) {
                    if (localDownloadJobs[taskId] === finishedJob) {
                        localDownloadJobs.remove(taskId)
                    }
                }
            }
        }
        synchronized(localDownloadJobs) { localDownloadJobs[taskId] = job }
    }

    fun cancelDownload(taskId: String) {
        synchronized(localDownloadJobs) { localDownloadJobs[taskId] }?.cancel()
    }

    fun retryLocalDownload(taskId: String) {
        val active = synchronized(localDownloadJobs) {
            localDownloadJobs[taskId]?.isActive == true
        }
        if (active) return
        val request = synchronized(retryableDownloads) { retryableDownloads[taskId] } ?: return
        downloadNative(request.vid, request.uri, taskId)
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
