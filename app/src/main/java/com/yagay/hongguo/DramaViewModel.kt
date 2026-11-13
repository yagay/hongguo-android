package com.yagay.hongguo

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope

class DramaViewModel(application: Application) : AndroidViewModel(application) {
    private val library = LibraryStore(application)
    internal val account = AccountManager(application, viewModelScope)
    private val prefs = application.getSharedPreferences("web_rewrite_settings", 0)
    private var source: WebContractClient? = null
    private val localContent = LocalContentStore(prefs)

    private fun saveOfflineEpisodes(rows: List<OfflineEpisode>) {
        val saved = localContent.saveOfflineEpisodes(rows)
        mutable.update { it.copy(offlineEpisodes = saved) }
    }

    fun removeOfflineEpisode(documentUri: String) {
        saveOfflineEpisodes(state.value.offlineEpisodes.filterNot { it.documentUri == documentUri })
    }

    fun openOfflineEpisode(episode: OfflineEpisode) {
        if (episode !in state.value.offlineEpisodes) return
        val launchedFromFeed = state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED
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
                feedMode = false,
                navigation = it.navigation.toSeries(if (launchedFromFeed)
                    PlaybackOrigin.SWIPE_FEED else PlaybackOrigin.CATALOG),
                preparingDetail = false, playbackError = null,
                resumePositionMs = library.checkpoint(episode.seriesId, episode.vid),
                relatedSeries = null, relatedWorks = emptyList(),
                guessWorks = emptyList(), guessesLoading = false, guessesError = null)
            }
            // Offline items also require service-owned playback; the UI must
            // never be the only place that installs media into ExoPlayer.
            play(0)
        }
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
                    val detail = if (source == null) kxgSource.detail(drama.id)
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
        val updated = if (old.any { it.id == drama.id })
            old.filterNot { it.id == drama.id }
        else listOf(drama) + old
        val saved = localContent.saveWatchLater(updated)
        mutable.update { it.copy(watchLater = saved) }
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

    fun setAutoPlayNext(enabled: Boolean) {
        prefs.edit().putBoolean("auto_play_next", enabled).apply()
        mutable.update { it.copy(autoPlayNext = enabled) }
        if (!enabled) {
            // Even a retry that was already queued by the series finale
            // must stop immediately when the user disables autoplay.
            pendingFeedAdvance = false
            autoNextTargetDramaId = null
            clearFinaleAdvance()
            mutable.update { it.copy(nextDramaTransition = NextDramaTransition.NONE) }
            // Manual episode selection remains supported, but no longer
            // issue background official requests for automatic finale play.
            cancelFinaleRecommendationWarmup()
        }
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

    /** Opt-in display brightness override; never changes Android global settings. */
    fun setVideoBrightnessPercent(percent: Int) {
        if (percent != -1 && percent !in 40..100) return
        prefs.edit().putInt("video_brightness_percent", percent).apply()
        mutable.update { it.copy(videoBrightnessPercent = percent) }
        diagnostics.record("VIDEO_DISPLAY",
            if (percent < 0) "BRIGHTNESS_SYSTEM" else "BRIGHTNESS_OVERRIDE")
    }

    /** Adjusts only the Media3 AudioSession effect; phone media volume stays intact. */
    fun setPlaybackAudioBoostDb(db: Int) {
        if (!AudioBoostPolicy.valid(db)) return
        prefs.edit().putInt("playback_audio_boost_db", db).apply()
        mutable.update { it.copy(playbackAudioBoostDb = db) }
        BackgroundPlaybackService.active?.setAudioBoostDb(db)
        diagnostics.record("AUDIO_BOOST", "USER_${db}DB")
    }

    /** Coarse renderer info, safe for diagnostic exports. */
    fun recordVideoSurface(type: String) {
        if (type !in setOf("SURFACE_VIEW", "TEXTURE_VIEW", "OTHER_SURFACE"))
            return
        diagnostics.record("VIDEO_RENDER", type)
    }

    fun recordDisplayState(state: String) {
        if (state.matches(Regex("(SYSTEM|OVERRIDE_[0-9]{2,3}PCT|RESTORED)"))) {
            diagnostics.record("VIDEO_DISPLAY", state)
        }
    }

    fun recordDisplayHdrSupport(hdr10: Boolean, hlg: Boolean, dolbyVision: Boolean) {
        diagnostics.record("VIDEO_DISPLAY",
            "HDR10_${if (hdr10) 1 else 0}_HLG_${if (hlg) 1 else 0}_DV_${if (dolbyVision) 1 else 0}")
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
    /** Kaixinguo SDK is now the catalog and MP4 playback-model source. */
    private val openHongguo = OpenHongguoSource(application)
    private val kxgSource = com.yagay.hongguo.kxg.KxgContentSource(openHongguo)
    private val catalogRepository = CatalogRepository(kxgSource) { source }
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
    private fun rememberQuery(value: String) {
        val updated = localContent.rememberQuery(value)
        mutable.update { it.copy(recentSearches = updated) }
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
                kxgSource.list(Section.RANK, 0, "short_play", "all", "", emptyMap())
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
    // One worker coordinator owns per-download jobs and decrypted file streams.
    private val localDownloader by lazy {
        LocalDownloadManager(
            resolver = getApplication<Application>().contentResolver,
            scope = viewModelScope,
            source = nativeSource,
            diagnostics = diagnostics,
            onTask = { task ->
                mutable.update { current ->
                    current.copy(downloadTasks = (
                        listOf(task) + current.downloadTasks.filterNot { it.id == task.id }
                    ).take(40))
                }
            },
            onSaved = { entry ->
                saveOfflineEpisodes(listOf(entry) + state.value.offlineEpisodes.filterNot {
                    it.documentUri == entry.documentUri
                })
            },
        )
    }
    private var requestId = 0L
    private var detailId = 0L
    private var pendingFeedAdvance = false
    /** Final-episode auto-next survives empty/duplicate official responses. */
    private var finaleAdvanceFor: String? = null
    /** Only this automatically selected drama keeps the loading indicator. */
    private var autoNextTargetDramaId: String? = null
    private var finaleAdvanceAttempts = 0
    private var finaleAdvanceRetryJob: Job? = null

    private fun clearFinaleAdvance() {
        finaleAdvanceRetryJob?.cancel()
        finaleAdvanceRetryJob = null
        finaleAdvanceFor = null
        finaleAdvanceAttempts = 0
    }

    private fun markFinaleAdvance(seriesId: String) {
        if (finaleAdvanceFor != seriesId) {
            clearFinaleAdvance()
            finaleAdvanceFor = seriesId
        }
        mutable.update {
            it.copy(nextDramaTransition = NextDramaTransition.FINDING_RECOMMENDATION)
        }
        diagnostics.record("REELS", "NEXT_DRAMA_LOADING_VISIBLE")
    }

    /**
     * One official page can contain only titles that were already shown.
     * Keep the automatic transition pending and retry at most twice.
     * Run the retry in a NEW coroutine: restarting from within the delivering
     * officialFeedJob would cancel that same coroutine.
     */
    private fun retryFinaleAdvance(reason: String) {
        val s = state.value
        val seriesId = finaleAdvanceFor ?: return
        if (!pendingFeedAdvance || !s.feedMode ||
            s.detail?.drama?.id != seriesId) return
        if (!FinaleRecommendationPolicy.canRetry(finaleAdvanceAttempts)) {
            diagnostics.record("REELS", "FINALE_ADVANCE_EXHAUSTED")
            pendingFeedAdvance = false
            clearFinaleAdvance()
            mutable.update {
                it.copy(feedLoading = false,
                    nextDramaTransition = NextDramaTransition.UNAVAILABLE,
                    feedError = "暂时无法获取下一部推荐，点击重试或上滑继续")
            }
            return
        }
        finaleAdvanceAttempts++
        val attempt = finaleAdvanceAttempts
        diagnostics.record("REELS", "FINALE_RETRY_${attempt}_${reason}")
        finaleAdvanceRetryJob?.cancel()
        finaleAdvanceRetryJob = viewModelScope.launch {
            delay(600L * attempt)
            val current = state.value
            if (finaleAdvanceFor != seriesId || !pendingFeedAdvance ||
                !current.feedMode || current.detail?.drama?.id != seriesId ||
                current.feedLoading) return@launch
            loadOfficialReels(
                restart = true,
                relaxRecentExclusions =
                    FinaleRecommendationPolicy.relaxRecentExclusions(attempt),
            )
        }
    }

    private var officialFeedJob: Job? = null
    private var officialFeedGeneration = 0L
    /** Background official-page request while the full-series finale plays. */
    private var finaleRecommendationRequestFor: String? = null

    private fun cancelFinaleRecommendationWarmup() {
        if (finaleRecommendationRequestFor == null) return
        finaleRecommendationRequestFor = null
        officialFeedJob?.cancel()
        officialFeedGeneration++
        pendingFeedAdvance = false
        mutable.update { it.copy(feedLoading = false) }
    }

    private fun activeFinaleRecommendation(seriesId: String): Boolean {
        val s = state.value
        val detail = s.detail ?: return false
        return detail.drama.id == seriesId && s.offlinePlaybackUri == null &&
            (s.feedMode || s.episodeIndex == detail.episodes.lastIndex)
    }

    /** A full-series finale is allowed to warm its saved official queue. */
    private fun mayPreloadFollowingDrama(s: AppState): Boolean {
        if (s.feedMode) return true
        val detail = s.detail ?: return false
        return s.autoPlayNext && s.offlinePlaybackUri == null &&
            s.episodeIndex == detail.episodes.lastIndex &&
            s.feedItems.getOrNull(s.feedIndex)?.id == detail.drama.id
    }
    /** Titles shown by the home recommendation grid are excluded from this swipe session. */
    private var homeFeedExcludedIds: Set<String> = emptySet()
    /** Startup restores the last confirmed video, not a fresh official reel. */
    private var deferRestoredFeedRecommendations = false
    private var startupRestoreSeriesId: String? = null
    private val lastPlaybackModeKey = "last_playback_feed_mode_v1"

    private fun rememberLastPlaybackMode(feed: Boolean) {
        prefs.edit().putBoolean(lastPlaybackModeKey, feed).apply()
    }

    private fun restoreLastPlaybackAtStartup(): Boolean {
        // The library is ordered by CONFIRMED first frames, and per-episode
        // checkpoints already preserve the actual position independently.
        val last = StartupPlaybackPolicy.lastConfirmed(
            state.value.library, state.value.hiddenRecommendationIds
        ) ?: return false
        startupRestoreSeriesId = last.drama.id
        diagnostics.record("APP", "RESTORE_LAST_PLAYBACK")
        if (prefs.getBoolean(lastPlaybackModeKey, false)) {
            startFeed(last.drama, resumeEp = last.episode, deferRecommendations = true)
        } else {
            open(last.drama, resumeEp = last.episode)
        }
        return true
    }

    private fun fallbackFromUnavailableStartupDrama(seriesId: String) {
        if (startupRestoreSeriesId != seriesId) return
        startupRestoreSeriesId = null
        diagnostics.record("APP", "RESTORE_UNAVAILABLE_FALLBACK")
        startFeed()
    }

    /** Failed feed batches are excluded from the next fresh cursor only.
     * A successful new batch clears this temporary quarantine. */
    private val staleFeedExcludedIds = linkedSetOf<String>()
    private var failedFeedRefreshPending = false

    private fun failedFeedRefreshUnavailable(message: String) {
        if (!failedFeedRefreshPending) return
        failedFeedRefreshPending = false
        mutable.update { it.copy(
            playbackError = message,
            nextDramaTransition = NextDramaTransition.NONE,
            feedLoading = false,
        ) }
        diagnostics.record("REELS", "FAILED_QUEUE_REFRESH_UNAVAILABLE")
    }

    private fun loadOfficialReels(
        restart: Boolean = false,
        warmingFinaleId: String? = null,
        relaxRecentExclusions: Boolean = false,
    ) {
        val snapshot = state.value
        if ((!snapshot.feedMode &&
                (warmingFinaleId == null || !activeFinaleRecommendation(warmingFinaleId))) ||
            snapshot.feedLoading) return
        val needsFirstDrama = snapshot.feedItems.isEmpty()
        if (warmingFinaleId != null) finaleRecommendationRequestFor = warmingFinaleId
        val generation = if (restart) ++officialFeedGeneration else officialFeedGeneration
        // Non-restart continuation may be queued by the just-completed batch
        // coroutine itself. Cancelling it here would cancel the caller.
        if (restart) officialFeedJob?.cancel()
        // After the finale, a heavily filtered first-party feed can contain
        // only recently viewed items. One bounded retry admits those older
        // suggestions, but never repeats the just-ended drama or hidden IDs.
        val excluded = snapshot.feedItems.mapTo(linkedSetOf()) { it.id }
            .apply {
                addAll(staleFeedExcludedIds)
                addAll(snapshot.hiddenRecommendationIds)
                if (!relaxRecentExclusions) {
                    addAll(homeFeedExcludedIds)
                    addAll(localContent.recentlySwiped())
                }
            }
        // Capture which source THIS request uses, not the account state
        // when the asynchronous response eventually arrives.
        val usesAccountRecommendation = appliedRecommendationSession != null
        mutable.update { it.copy(feedLoading = true, feedError = null,
            feedRecommendationStatus =
                ReelRecommendationStatusPolicy.loading(usesAccountRecommendation)) }
        officialFeedJob = viewModelScope.launch {
            try {
                // The recommendation endpoint may retry several official
                // categories in sequence. Bound the entire request so the
                // startup screen cannot spin indefinitely on a stalled host.
                val result = withTimeout(if (needsFirstDrama) 22_000L else 18_000L) {
                    if (usesAccountRecommendation) {
                        // Account-aware recommendations must stay on the
                        // authenticated feed; Kaixinguo SDK is anonymous only.
                        nativeSource.officialReelsPage(restart, excluded,
                            realOnly = snapshot.reelsMode == "real",
                            comicOnly = snapshot.reelsMode == "comic",
                            firstItemOnly = needsFirstDrama,
                            forceFresh = restart && staleFeedExcludedIds.isNotEmpty())
                    } else {
                        kxgSource.reelsPage(restart, excluded,
                            realOnly = snapshot.reelsMode == "real",
                            comicOnly = snapshot.reelsMode == "comic",
                            firstItemOnly = needsFirstDrama)
                    }
                }
                if (generation != officialFeedGeneration ||
                    (warmingFinaleId == null && !state.value.feedMode) ||
                    (warmingFinaleId != null &&
                        !activeFinaleRecommendation(warmingFinaleId))) return@launch
                mutable.update { current ->
                    val exclusions = if (relaxRecentExclusions)
                        current.hiddenRecommendationIds
                    else current.hiddenRecommendationIds + homeFeedExcludedIds +
                        localContent.recentlySwiped().toSet()
                    val merged = OfficialReelsPolicy.append(
                        current.feedItems, result.items, exclusions)
                    current.copy(feedItems = merged,
                        feedHasMore = result.hasMore,
                        feedLoading = false,
                        feedRecommendationStatus = ReelRecommendationStatusPolicy.received(
                            usesAccountRecommendation, merged.size > current.feedItems.size),
                        feedError = if (merged.size == current.feedItems.size)
                            "官方暂未推荐新作品，可上滑重试" else null)
                }
                updateNotificationNavigation()
                // Do not reopen the stale batch if the provider returned
                // only duplicates that could not enter the replacement queue.
                if (state.value.feedItems.isNotEmpty() &&
                    result.items.any { offered ->
                        state.value.feedItems.any { it.id == offered.id }
                    }) staleFeedExcludedIds.clear()
                diagnostics.record("REELS", "OFFICIAL_BATCH_READY")
                if (needsFirstDrama && state.value.feedMode) {
                    val first = state.value.feedItems.firstOrNull()
                    if (first != null) {
                        localContent.rememberSwiped(first.id)
                        open(first, resumeEp = 1, feed = true)
                    } else {
                        mutable.update { it.copy(preparingDetail = false,
                            feedError = "官方暂时没有新推荐，请点击重试") }
                    }
                }
                if (pendingFeedAdvance) {
                    // Check the actual next playable queue entry, not merely
                    // the total count of returned titles.
                    val after = state.value
                    val next = OfficialReelsPolicy.nextIndex(
                        after.feedItems, after.feedIndex, 1,
                        after.hiddenRecommendationIds,
                    )
                    if (next != null) {
                        pendingFeedAdvance = false
                        if (failedFeedRefreshPending) {
                            failedFeedRefreshPending = false
                            diagnostics.record("REELS", "FAILED_QUEUE_REPLACEMENT_READY")
                        }
                        // Do not cancel the coroutine that delivered this batch.
                        if (warmingFinaleId != null &&
                            finaleRecommendationRequestFor == warmingFinaleId)
                            finaleRecommendationRequestFor = null
                        diagnostics.record("REELS", "FINALE_ADVANCE_READY")
                        navigateFeed(1)
                    } else if (failedFeedRefreshPending) {
                        pendingFeedAdvance = false
                        failedFeedRefreshUnavailable(
                            "官方暂时没有新推荐，请点击刷新推荐列表")
                    } else if (finaleAdvanceFor != null) {
                        // Keep pending through an empty or duplicate-only batch.
                        retryFinaleAdvance("EMPTY")
                    } else {
                        pendingFeedAdvance = false
                    }
                }
                val latest = state.value
                if (!needsFirstDrama && OfficialReelsPolicy.needsMore(
                        latest.feedItems, latest.feedIndex, latest.hiddenRecommendationIds,
                        latest.feedHasMore, latest.feedLoading) &&
                    latest.feedItems.size > snapshot.feedItems.size) {
                    // One request ahead, bounded by the page's new visible items.
                    loadOfficialReels()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (generation == officialFeedGeneration &&
                    (state.value.feedMode ||
                        (warmingFinaleId != null &&
                            activeFinaleRecommendation(warmingFinaleId)))) {
                    mutable.update { it.copy(feedLoading = false,
                        preparingDetail = if (needsFirstDrama) false else it.preparingDetail,
                        feedRecommendationStatus =
                            ReelRecommendationStatusPolicy.failed(usesAccountRecommendation),
                        feedError = "获取官方推荐超时，请点击重新获取") }
                    diagnostics.record("REELS", "OFFICIAL_TIMEOUT")
                    failedFeedRefreshUnavailable(
                        "获取官方推荐超时，请点击刷新推荐列表")
                    if (finaleAdvanceFor != null) retryFinaleAdvance("TIMEOUT")
                    else pendingFeedAdvance = false
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == officialFeedGeneration &&
                    (state.value.feedMode ||
                        (warmingFinaleId != null &&
                            activeFinaleRecommendation(warmingFinaleId)))) {
                    mutable.update { it.copy(feedLoading = false,
                        preparingDetail = if (needsFirstDrama) false else it.preparingDetail,
                        feedRecommendationStatus =
                            ReelRecommendationStatusPolicy.failed(usesAccountRecommendation),
                        feedError = OfficialReelsFailure.userMessage(error)) }
                    diagnostics.record("REELS",
                        "OFFICIAL_FAIL_" + OfficialReelsFailure.code(error))
                    failedFeedRefreshUnavailable(
                        "推荐列表刷新失败，请点击刷新推荐列表")
                    if (finaleAdvanceFor != null) retryFinaleAdvance("ERROR")
                    else pendingFeedAdvance = false
                }
            } finally {
                if (generation == officialFeedGeneration &&
                    finaleRecommendationRequestFor == warmingFinaleId)
                    finaleRecommendationRequestFor = null
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
            val snapshot = state.value
            // Do not stop/recreate Media3 playback solely for a settings
            // change. Preserve the visible drama while rebuilding the queue.
            val retained = OfficialReelsPolicy.retainCurrentOnAccountChange(
                snapshot.feedItems, snapshot.feedIndex, snapshot.detail?.drama)
            mutable.update { it.copy(
                feedItems = retained,
                feedIndex = 0,
                feedLoading = false,
                feedRecommendationStatus = ReelRecommendationStatus.NOT_REQUESTED,
                feedHasMore = true,
                feedError = null,
            ) }
            updateNotificationNavigation()
            if (!deferRestoredFeedRecommendations) loadOfficialReels(restart = true)
        } else if (state.value.section == Section.RECOMMEND &&
            state.value.detail == null) {
            refresh()
        }
    }

    /**
     * A feed-mode switch creates a fresh official cursor and resets the current
     * queue and playback; it does not touch the home recommendation catalog.
     * Comic uses a dedicated official category cursor, not homepage cards.
     */
    fun setReelsMode(mode: String) {
        if (mode !in setOf("all", "real", "comic") || mode == state.value.reelsMode) return
        prefs.edit().putString("reels_mode", mode).apply()
        val wasInFeed = state.value.feedMode
        if (wasInFeed) closePlayer()
        mutable.update { it.copy(reelsMode = mode) }
        if (wasInFeed) startFeed()
    }

    /** Explicit retry after a temporary upstream failure; never silently change to a fixed list. */
    fun retryOfficialReels() {
        if (!state.value.feedMode) return
        // A retry from an already-ended video must actually advance after
        // discovery. Previously it fetched cards but kept the old final frame.
        val ended = BackgroundPlaybackService.playerFlow.value
            ?.playbackState == androidx.media3.common.Player.STATE_ENDED
        if (ended) {
            state.value.detail?.drama?.id?.let(::markFinaleAdvance)
            pendingFeedAdvance = true
            diagnostics.record("REELS", "FINALE_MANUAL_RETRY")
        }
        // If the current upstream call is stuck, the visible retry must be
        // actionable. Invalidate its generation first so its completion
        // cannot overwrite the replacement request.
        if (state.value.feedLoading) {
            officialFeedGeneration++
            officialFeedJob?.cancel()
            mutable.update { it.copy(feedLoading = false) }
            diagnostics.record("REELS", "MANUAL_CANCEL_AND_RETRY")
        }
        if (state.value.feedItems.isEmpty()) {
            mutable.update { it.copy(preparingDetail = true) }
        }
        // A manual retry starts new upstream cursors but does not discard
        // any already playing item or valid part of the swipe queue.
        loadOfficialReels(
            restart = true,
            relaxRecentExclusions = ended,
        )
    }
    /** A failed model may belong to an expired recommendation list.
     * Retain the current screen while fetching a new official page.
     * This is deliberately user-initiated (button or next swipe after error),
     * not an infinite automatic retry loop. */
    fun refreshFailedReels() {
        val snapshot = state.value
        if (!snapshot.feedMode) {
            retryPlayback()
            return
        }
        val failedId = snapshot.detail?.drama?.id
            ?: snapshot.feedItems.getOrNull(snapshot.feedIndex)?.id
        if (failedId != null) staleFeedExcludedIds.add(failedId)
        // An unplayable VID is not evidence that *all* recommendations are
        // expired. Exclude only the failed drama, keep its screen visible
        // while requesting a fresh cursor, and retain the Media3 instance.
        while (staleFeedExcludedIds.size > 24)
            staleFeedExcludedIds.remove(staleFeedExcludedIds.first())
        val anchor = FeedRefreshPolicy.anchor(
            snapshot.feedItems, snapshot.feedIndex, snapshot.detail?.drama)
        if (anchor == null) {
            closePlayer()
            startFeed()
            return
        }
        officialFeedJob?.cancel()
        officialFeedGeneration++

        clearFinaleAdvance()
        failedFeedRefreshPending = true
        pendingFeedAdvance = true
        mutable.update { it.copy(
            feedItems = listOf(anchor), feedIndex = 0,
            feedLoading = false, feedHasMore = true, feedError = null,
            playbackError = null,
            nextDramaTransition = NextDramaTransition.FINDING_RECOMMENDATION,
        ) }
        diagnostics.record("REELS", "FAILED_QUEUE_REFRESH_WITHOUT_PLAYER_RESET")
        loadOfficialReels(restart = true)
    }

    /** Reuse the last few series details on reverse swipes, without refetching. */
    private val visitedFeedDetails = linkedMapOf<String, DramaDetail>()
    private fun rememberFeedDetail(detail: DramaDetail) {
        visitedFeedDetails.remove(detail.drama.id)
        visitedFeedDetails[detail.drama.id] = detail
        while (visitedFeedDetails.size > 4)
            visitedFeedDetails.remove(visitedFeedDetails.keys.first())
    }

    private var firstFrameFor: String? = null
    private var playbackStartAtMs = 0L
    private var startEpisodeHint: Int? = null

    private val mutable = MutableStateFlow(
        AppState(backend = prefs.getString("backend", "").orEmpty(),
            library = library.list(false), favorites = library.list(true),
            likedDramaIds = prefs.getStringSet("local_liked_dramas", emptySet())?.toSet().orEmpty(),
            watchLater = localContent.watchLater(),
            offlineEpisodes = localContent.offlineEpisodes(),
            favoriteGroups = library.favoriteGroups(),
            completedDramaIds = prefs.getStringSet("completed_drama_ids_v1", emptySet())?.toSet().orEmpty(),
            hiddenRecommendationIds = prefs.getStringSet("hidden_recommendations_v1", emptySet())?.toSet().orEmpty(),
            themeStyle = prefs.getString("theme_style", "warm")?.takeIf { it in setOf("warm", "immersive", "cards") } ?: "warm",
            themeMode = prefs.getString("theme_mode", "light")?.takeIf { it in setOf("system", "light", "dark") } ?: "light",
            reelsMode = prefs.getString("reels_mode", "all")
                ?.takeIf { it == "all" || it == "real" } ?: "all",
            autoPlayNext = prefs.getBoolean("auto_play_next", true),

            episodeUpdateNotificationsEnabled = prefs.getBoolean("episode_update_notifications", false),
            skipIntroSeconds = prefs.getInt("skip_intro_seconds", 0).coerceIn(0, 120),
            skipOutroSeconds = prefs.getInt("skip_outro_seconds", 0).coerceIn(0, 120),
            retryPlaybackAutomatically = prefs.getBoolean("playback_auto_retry", true),
            recentSearches = localContent.recentQueries(),
            defaultPlaybackSpeed = prefs.getFloat("default_playback_speed", 1f).coerceIn(0.75f, 3f),
            videoBrightnessPercent = prefs.getInt("video_brightness_percent", -1)
                .takeIf { it == -1 || it in 40..100 } ?: -1,
            playbackAudioBoostDb = prefs.getInt("playback_audio_boost_db", 0)
                .takeIf(AudioBoostPolicy::valid) ?: 0,
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
        // Upstream Kaixinguo does not persist decrypted video bytes.
        // Reclaim both old cache folders once at startup; never touch
        // downloads, cover images, account data or offline episode files.
        viewModelScope.launch(Dispatchers.IO) {
            val removed = listOf("kaixinguo_cenc_cache_v1", "native_stream_cache_v1")
                .map { java.io.File(application.cacheDir, it) }
                .filter { it.exists() }
                .count { it.deleteRecursively() }
            diagnostics.record("PLAYBACK", "UPSTREAM_KXG_DIRECT_SOURCE")
            if (removed > 0) diagnostics.record("DISK_CACHE", "LEGACY_VIDEO_CACHE_REMOVED")
        }
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
        // Start on the last actually played drama/episode. The normal
        // recommendation feed is only the cold-start fallback.
        if (!restoreLastPlaybackAtStartup()) startFeed()
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
                val rows = kxgSource.suggestions(keyword)
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
        if (state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED) {
            setSearch(term)
            searchBrowseQuery(term)
            backFromDramaDetails()
            if (state.value.navigation.destination == PlaybackDestination.SERIES)
                restoreReelFeed()
            mutable.update { it.copy(reelSearchRequest = it.reelSearchRequest + 1L) }
            diagnostics.record("DETAIL", "TAG_SEARCH_REEL_OVERLAY")
        } else {
            closePlayer()
            setSearch(term)
            search()
            diagnostics.record("DETAIL", "TAG_SEARCH")
        }
    }

    /** Consume overlay navigation only once, even after player recomposition. */
    fun consumeReelSearchRequest(request: Long) {
        if (request <= 0L) return
        mutable.update {
            if (it.reelSearchRequest == request) it.copy(reelSearchRequest = 0L)
            else it
        }
    }

    // Search landing browsing uses the same catalog service, but has its own
    // request generation and pagination so it cannot reset the homepage or
    // mutate an actively playing recommendation feed.
    private val mutableSearchBrowse = MutableStateFlow(SearchBrowseState())
    internal val searchBrowse = mutableSearchBrowse.asStateFlow()
    private var searchBrowseJob: Job? = null
    private var searchBrowseFiltersJob: Job? = null
    private var searchBrowseGeneration = 0L

    fun searchBrowseSection(section: Section?) {
        if (section != null && section !in setOf(
                Section.SEARCH, Section.RECOMMEND, Section.RANK, Section.LATEST, Section.BROWSE)) return
        val previous = mutableSearchBrowse.value
        if (section == previous.section) return
        searchBrowseJob?.cancel()
        searchBrowseFiltersJob?.cancel()
        ++searchBrowseGeneration
        mutableSearchBrowse.value = previous.copy(section = section,
            items = emptyList(), loading = false, moreLoading = false,
            hasMore = false, nextOffset = 0, error = null)
        if (section != null) refreshSearchBrowse()
    }

    /** Search results stay inside the search page rather than navigating to
     * the homepage's Section.SEARCH screen.
     */
    fun searchBrowseQuery(value: String) {
        val query = value.trim().take(80)
        if (query.isBlank()) return
        setSearch(query)
        rememberQuery(query)
        searchBrowseJob?.cancel()
        searchBrowseFiltersJob?.cancel()
        ++searchBrowseGeneration
        mutableSearchBrowse.value = mutableSearchBrowse.value.copy(
            // Search entry is keyword-only; discovery/ranking/filter tabs
            // belong to the Theater screen, even after restoring old state.
            section = Section.SEARCH,
            query = query, items = emptyList(), loading = false,
            hasMore = false, nextOffset = 0, error = null,
        )
        refreshSearchBrowse()
    }

    fun searchBrowseBoard(value: String) {
        val choices = if (state.value.backend.isBlank())
            setOf("all", "real", "comic", "ai")
        else setOf("hot", "new", "recommend")
        if (value !in choices) return
        mutableSearchBrowse.update { it.copy(board = value) }
        refreshSearchBrowse()
    }

    fun searchBrowseGenre(value: String) {
        if (value !in setOf("short_play", "comic_series", "ai_series")) return
        mutableSearchBrowse.update {
            it.copy(genre = value, activeFilters = emptyMap(), filters = emptyList())
        }
        refreshSearchBrowse()
    }

    fun searchBrowseApplyFilters(filters: Map<String, String>) {
        mutableSearchBrowse.update { it.copy(activeFilters = filters.filterValues(String::isNotBlank)) }
        refreshSearchBrowse()
    }

    // Scoped sections have no server-side keyword API. Match each fetched
    // page without claiming unvisited pages have been searched.
    private fun matchesScope(drama: Drama, keyword: String): Boolean =
        keyword.isBlank() ||
            drama.title.contains(keyword, ignoreCase = true) ||
            drama.actors.any { it.contains(keyword, ignoreCase = true) } ||
            drama.categories.any { it.contains(keyword, ignoreCase = true) } ||
            drama.synopsis.contains(keyword, ignoreCase = true)

    fun refreshSearchBrowse() {
        val snapshot = mutableSearchBrowse.value
        val section = snapshot.section ?: return
        searchBrowseJob?.cancel()
        searchBrowseFiltersJob?.cancel()
        val generation = ++searchBrowseGeneration
        mutableSearchBrowse.update { it.copy(items = emptyList(), loading = true,
            moreLoading = false, error = null, hasMore = false, nextOffset = 0) }
        searchBrowseJob = viewModelScope.launch {
            try {
                val result = source?.list(section, 0, snapshot.genre, snapshot.board,
                    snapshot.query, snapshot.activeFilters, emptyList())
                    ?: kxgSource.list(section, 0, snapshot.genre, snapshot.board,
                        snapshot.query, snapshot.activeFilters)
                if (generation != searchBrowseGeneration) return@launch
                val matched = if (section == Section.SEARCH) result.items
                else result.items.filter { matchesScope(it, snapshot.query) }
                mutableSearchBrowse.update { it.copy(items = matched,
                    loading = false, hasMore = result.hasMore,
                    nextOffset = result.nextOffset) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchBrowseGeneration)
                    mutableSearchBrowse.update {
                        it.copy(loading = false, error = errorText("浏览短剧", e))
                    }
            }
        }
        if (section == Section.BROWSE && snapshot.filters.isEmpty()) {
            searchBrowseFiltersJob = viewModelScope.launch {
                try {
                    val groups = source?.filters(snapshot.genre)
                        ?: kxgSource.filters(snapshot.genre)
                    if (generation == searchBrowseGeneration)
                        mutableSearchBrowse.update { it.copy(filters = groups) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    diagnostics.record("SEARCH_BROWSE", "FILTERS_UNAVAILABLE")
                }
            }
        }
    }

    fun moreSearchBrowse() {
        val snapshot = mutableSearchBrowse.value
        val section = snapshot.section ?: return
        if (snapshot.loading || snapshot.moreLoading || !snapshot.hasMore) return
        val generation = searchBrowseGeneration
        mutableSearchBrowse.update { it.copy(moreLoading = true) }
        searchBrowseJob = viewModelScope.launch {
            try {
                val result = source?.list(section, snapshot.nextOffset,
                    snapshot.genre, snapshot.board, snapshot.query, snapshot.activeFilters,
                    snapshot.items.map { it.id })
                    ?: kxgSource.list(section, snapshot.nextOffset,
                        snapshot.genre, snapshot.board, snapshot.query, snapshot.activeFilters)
                if (generation != searchBrowseGeneration) return@launch
                val matched = if (section == Section.SEARCH) result.items
                else result.items.filter { matchesScope(it, snapshot.query) }
                val merged = (snapshot.items + matched).distinctBy(Drama::id)
                mutableSearchBrowse.update {
                    it.copy(items = merged, moreLoading = false,
                        nextOffset = result.nextOffset,
                        hasMore = result.hasMore &&
                            result.nextOffset > snapshot.nextOffset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == searchBrowseGeneration)
                    mutableSearchBrowse.update {
                        it.copy(moreLoading = false, error = errorText("加载更多", e))
                    }
            }
        }
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

    /** Apply the entire filter draft in a single state update, then refresh once. */
    fun applyCatalogFilters(filters: Map<String, String>) {
        val clean = filters.filterValues(String::isNotBlank)
        mutable.update { it.copy(activeFilters = clean) }
        refresh()
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
                val result = catalogRepository.page(
                    s.section, 0, s.genre, s.board, s.search, s.activeFilters)
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
                    val groups = catalogRepository.filters(s.genre)
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
                val result = catalogRepository.page(
                    s.section, s.nextOffset, s.genre, s.board, s.search,
                    s.activeFilters, s.items.map { it.id })
                if (version != requestId) return@launch
                val mergedPage = CatalogRepository.mergePage(
                    s.items, result, s.nextOffset)
                mutable.update {
                    it.copy(items = mergedPage.items, nextOffset = mergedPage.nextOffset,
                        hasMore = mergedPage.hasMore, moreLoading = false)
                }
                if (!mergedPage.hasMore && result.hasMore) {
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
                danmakuWindowStartMs = 0L, danmakuLoadedUntilMs = 0L,
                danmakuLoading = false, danmakuError = null)
        }
    }

    fun playIfDifferent(index: Int) {
        if (index in (state.value.detail?.episodes?.indices ?: IntRange.EMPTY) &&
            state.value.episodeIndex != index) play(index)
    }

    fun setDanmaku(enabled: Boolean) {
        if (state.value.danmakuEnabled == enabled) return
        prefs.edit().putBoolean("danmaku_enabled", enabled).apply()
        // Cancellation must clear the loading flag as well. Previously a
        // cancelled fetch could leave danmakuLoading=true indefinitely, so
        // switching the toggle back on never requested any comments again.
        danmakuGeneration++
        danmakuJob?.cancel()
        danmakuDurationWaitForVid = null
        mutable.update { it.copy(
            danmakuEnabled = enabled,
            danmakuForVid = null,
            danmakuItems = emptyList(),
            danmakuWindowStartMs = 0L,
            danmakuLoadedUntilMs = 0L,
            danmakuLoading = false,
            danmakuError = null,
        ) }
        diagnostics.record("DANMAKU", if (enabled) "ENABLED" else "DISABLED")
    }

    /** Explicit retry after a failed or empty provider response. Do not invent
     * bullet comments or retry the upstream endpoint in an unbounded loop.
     */
    fun retryDanmaku(positionMs: Long, durationMs: Long) {
        if (!state.value.danmakuEnabled) return
        danmakuGeneration++
        danmakuJob?.cancel()
        mutable.update { it.copy(
            danmakuForVid = null, danmakuItems = emptyList(),
            danmakuWindowStartMs = 0L, danmakuLoadedUntilMs = 0L,
            danmakuLoading = false, danmakuError = null,
        ) }
        diagnostics.record("DANMAKU", "MANUAL_RETRY")
        ensureDanmaku(positionMs, durationMs)
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

    /** Fetch the actual official timed-comment window near the current Media3
     * clock. An explicit seek or autoplay may jump across many pages; always
     * reset the window instead of crawling from 00:00 for minutes.
     */
    private var danmakuDurationWaitForVid: String? = null

    fun ensureDanmaku(positionMs: Long, durationMs: Long) {
        val s = state.value
        // Preserve the one-shot error state. A visible retry action can re-arm
        // it, without making repeated rejected requests every 3.5 seconds.
        if (!s.danmakuEnabled || s.danmakuLoading || s.danmakuError != null) return
        val detail = s.detail ?: return
        val vid = detail.episodes.getOrNull(s.episodeIndex)?.vid ?: return
        if (durationMs !in 1_000L..86_400_000L) {
            if (danmakuDurationWaitForVid != vid) {
                danmakuDurationWaitForVid = vid
                diagnostics.record("DANMAKU", "WAIT_MEDIA_DURATION")
            }
            return
        }
        danmakuDurationWaitForVid = null
        val request = DanmakuWindowPolicy.next(
            positionMs = positionMs,
            durationMs = durationMs,
            sameEpisode = s.danmakuForVid == vid,
            loadedFromMs = s.danmakuWindowStartMs,
            loadedUntilMs = s.danmakuLoadedUntilMs,
        ) ?: return
        val start = request.startMs
        val generation = danmakuGeneration
        val sid = detail.drama.id
        mutable.update { it.copy(danmakuForVid = vid, danmakuLoading = true,
            danmakuError = null) }
        danmakuJob = viewModelScope.launch {
            diagnostics.record("DANMAKU",
                if (request.replaceWindow) "FETCH_SEEK_WINDOW" else "FETCH_NEXT_WINDOW")
            try {
                val page = nativeSource.danmaku(sid, vid, start, durationMs)
                if (generation != danmakuGeneration ||
                    state.value.detail?.episodes?.getOrNull(state.value.episodeIndex)?.vid != vid)
                    return@launch
                mutable.update {
                    val previous = if (request.replaceWindow) emptyList()
                        else it.danmakuItems
                    it.copy(
                        danmakuForVid = vid,
                        danmakuItems = (previous + page.items)
                            .distinctBy(DanmakuEntry::id).takeLast(600),
                        danmakuWindowStartMs = if (request.replaceWindow)
                            start else it.danmakuWindowStartMs,
                        danmakuLoadedUntilMs = page.nextMs.coerceAtMost(durationMs),
                        danmakuLoading = false,
                    )
                }
                diagnostics.record("DANMAKU",
                    if (page.items.isEmpty()) "READ_EMPTY" else "READ_NONEMPTY")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == danmakuGeneration) {
                    val problem = PlaybackProblem.describe("读取弹幕", e)
                    diagnostics.record("DANMAKU",
                        "READ_FAIL_" + problem.substringBefore(" · "))
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
        if (state.value.navigation.destination == PlaybackDestination.CATALOG &&
            state.value.detail == null && !state.value.preparingDetail &&
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
    fun startFeed(
        drama: Drama? = null,
        resumeEp: Int? = null,
        deferRecommendations: Boolean = false,
    ) {
        val current = state.value
        if (drama != null && drama.id in current.hiddenRecommendationIds) return
        deferRestoredFeedRecommendations = deferRecommendations && drama != null
        failedFeedRefreshPending = false
        officialFeedJob?.cancel()
        officialFeedGeneration++
        finaleRecommendationRequestFor = null
        pendingFeedAdvance = false
        clearFinaleAdvance()
        autoNextTargetDramaId = null
        visitedFeedDetails.clear()
        val feedAccount = account.recommendationAccount()
        appliedRecommendationSession = feedAccount
        nativeSource.setAccountRecommendations(feedAccount)
        // Official App feed is separate from "为你推荐". Never show its already-loaded
        // home cards as the first swipe queue, even when the server repeats them.
        homeFeedExcludedIds = if (current.section == Section.RECOMMEND)
            current.items.mapTo(linkedSetOf()) { it.id } else emptySet()
        mutable.update { it.copy(
            miniPlayerVisible = false,
            detailPageVisible = false,
            detail = if (drama == null) null else it.detail,
            feedMode = true,
            navigation = it.navigation.toFeed(),
            feedItems = drama?.let(::listOf).orEmpty(),
            feedIndex = 0,
            feedLoading = false,
            feedRecommendationStatus = ReelRecommendationStatus.NOT_REQUESTED,
            feedHasMore = true,
            feedError = null,
            nextDramaTransition = NextDramaTransition.NONE,
            preparingDetail = drama == null,
        ) }
        if (drama != null) {
            localContent.rememberSwiped(drama.id)
            open(drama, resumeEp = resumeEp ?: 1, feed = true)
        }
        if (!deferRestoredFeedRecommendations) loadOfficialReels(restart = true)
    }

    /**
     * One entry point for every linked series chosen from the reel drawer,
     * search, related works, other seasons or "猜你喜欢". The current
     * route's explicit origin is retained even inside a details page.
     */
    fun openRelatedDrama(drama: Drama) {
        if (state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED &&
            !state.value.navigation.isExplicitCatalog)
            openFromFeedSelection(drama)
        else open(drama)
    }

    /** A selected work becomes the current reel without opening the old home
     * catalog. Series mode has the original header; Back returns to its feed.
     */
    fun openFromFeedSelection(drama: Drama, resumeEp: Int? = null) {
        val snapshot = state.value
        if (snapshot.navigation.origin != PlaybackOrigin.SWIPE_FEED ||
            snapshot.navigation.isExplicitCatalog) {
            open(drama, resumeEp = resumeEp)
            return
        }
        if (snapshot.detail?.drama?.id == drama.id) {
            val index = snapshot.detail.episodes.indexOfFirst { it.index == resumeEp }
                .takeIf { it >= 0 } ?: snapshot.episodeIndex
            if (snapshot.feedMode) exitFeedToEpisode(index)
            else {
                if (snapshot.detailPageVisible) backFromDramaDetails()
                if (index != state.value.episodeIndex) play(index)
            }
            return
        }
        val queue = if (snapshot.feedItems.any { it.id == drama.id })
            snapshot.feedItems else snapshot.feedItems + drama
        val selected = queue.indexOfFirst { it.id == drama.id }
        officialFeedJob?.cancel()
        officialFeedGeneration++
        pendingFeedAdvance = false
        mutable.update { it.copy(feedItems = queue, feedIndex = selected,
            feedLoading = false, feedError = null) }
        open(drama, resumeEp = resumeEp, origin = PlaybackOrigin.SWIPE_FEED)
        diagnostics.record("NAVIGATION", "RELATED_SERIES_FROM_FEED")
    }

    /** Both manual vertical swipe and natural episode end use the live official queue. */
    fun navigateFeed(direction: Int) {
        val current = state.value
        if (!current.feedMode || direction !in setOf(-1, 1)) return
        // Do not advance into the next entry of a queue whose video models
        // have already expired or failed. Fetch fresh recommendations.
        if (direction > 0 && current.playbackError != null) {
            refreshFailedReels()
            return
        }
        // Request the next recommendation on the user's first deliberate swipe.
        deferRestoredFeedRecommendations = false
        val index = OfficialReelsPolicy.nextIndex(
            current.feedItems, current.feedIndex, direction,
            current.hiddenRecommendationIds)
        val selected = index?.let(current.feedItems::get)
        if (selected != null && selected.id != current.detail?.drama?.id) {
            val automaticallyAdvancing = direction > 0 && finaleAdvanceFor != null &&
                current.nextDramaTransition == NextDramaTransition.FINDING_RECOMMENDATION
            pendingFeedAdvance = false
            clearFinaleAdvance()
            autoNextTargetDramaId = if (automaticallyAdvancing) selected.id else null
            mutable.update {
                it.copy(feedIndex = index,
                    nextDramaTransition = if (automaticallyAdvancing)
                        NextDramaTransition.PREPARING_VIDEO
                    else NextDramaTransition.NONE)
            }
            if (automaticallyAdvancing)
                diagnostics.record("REELS", "NEXT_DRAMA_VIDEO_PREPARING")
            updateNotificationNavigation()
            localContent.rememberSwiped(selected.id)
            open(selected, resumeEp = 1, feed = true)
            // Do not cancel an in-flight prefetch for the newly selected reel
            // by scheduling the following reel immediately. SOURCE_READY and
            // FIRST_FRAME will re-arm the new recommendation window.
            if (direction > 0 && OfficialReelsPolicy.needsMore(
                    current.feedItems, index, current.hiddenRecommendationIds,
                    current.feedHasMore, current.feedLoading)) loadOfficialReels()
        } else if (direction > 0) {
            // No ready video: accept the gesture immediately, then keep
            // playing the current media while obtaining a real next drama.
            // This also works after a temporary timeout or exhausted cursor.
            pendingFeedAdvance = true
            diagnostics.record("REELS",
                if (current.feedLoading) "SWIPE_WAITING_FOR_NEXT_BATCH"
                else "SWIPE_FETCH_NEXT_BATCH")
            if (!current.feedLoading) {
                val resetCursor = current.feedError != null || !current.feedHasMore
                loadOfficialReels(restart = resetCursor)
            }
        }
    }

    /** Enter the current drama's episode pager without replacing its active media.
     * Keep the feed queue/index so Back can return to this same drama and resume swiping.
     */
    fun exitFeedToEpisode(index: Int) {
        failedFeedRefreshPending = false
        val snapshot = state.value
        val target = snapshot.detail?.episodes?.getOrNull(index) ?: return
        if (!snapshot.feedMode) {
            if (index != snapshot.episodeIndex) play(index)
            return
        }
        officialFeedJob?.cancel()
        officialFeedGeneration++
        finaleRecommendationRequestFor = null
        pendingFeedAdvance = false
        val currentMediaMatches = snapshot.playbackUrl != null &&
            index == snapshot.episodeIndex &&
            BackgroundPlaybackService.playerFlow.value?.currentMediaItem?.mediaId == target.vid
        rememberLastPlaybackMode(false)
        mutable.update {
            it.copy(feedMode = false,
                returnSeries = Watched(snapshot.detail!!.drama, target.index,
                    System.currentTimeMillis()),
                returnFeedItem = null,
                navigation = it.navigation.toSeries(PlaybackOrigin.SWIPE_FEED),
                feedLoading = false, detailPageVisible = false,
                miniPlayerVisible = false)
        }
        diagnostics.record("REELS", "ENTER_EPISODES")
        updateNotificationNavigation()
        // Only a different or missing media item needs a new playback request.
        // A mode change alone must not reset the position or pause the player.
        if (!currentMediaMatches) {
            play(index)
        }
    }

    /** Restore the original swipe queue while the current Media3 video keeps playing. */
    private fun restoreReelFeed(): Boolean {
        val snapshot = state.value
        if (!ReelReturnPolicy.canRestore(snapshot)) return false
        rememberLastPlaybackMode(true)
        mutable.update {
            it.copy(feedMode = true, navigation = it.navigation.toFeed(),
                miniPlayerVisible = false, detailPageVisible = false)
        }
        updateNotificationNavigation()
        val player = BackgroundPlaybackService.playerFlow.value
        // A navigation switch is NOT a playback restart. In particular an
        // upstream failure can leave the service holding an already-ended
        // previous item; merely restoring the reel UI used to claim it was
        // "still playing" and leave a permanently frozen video.
        when {
            snapshot.playbackError != null -> {
                diagnostics.record("REELS", "RETURN_TO_FEED_RETRY_FAILED_SOURCE")
                retryPlayback()
            }
            player?.playbackState == androidx.media3.common.Player.STATE_ENDED -> {
                diagnostics.record("REELS", "RETURN_TO_FEED_RESOLVE_ENDED")
                if (snapshot.autoPlayNext) nextEpisode(
                    snapshot.detail?.episodes?.getOrNull(snapshot.episodeIndex)?.vid)
            }
            player == null && snapshot.playbackUrl != null -> {
                diagnostics.record("REELS", "RETURN_TO_FEED_RESTORE_MISSING_PLAYER")
                retryPlayback()
            }
            else -> diagnostics.record("REELS", "RETURN_TO_FEED_KEEP_PLAYING")
        }
        val current = state.value
        if (OfficialReelsPolicy.needsMore(current.feedItems, current.feedIndex,
                current.hiddenRecommendationIds, current.feedHasMore, current.feedLoading)) {
            loadOfficialReels()
        }
        return true
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
        val fromFeed = state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED &&
            !state.value.navigation.isExplicitCatalog
        closeAccount()
        val drama = Drama(id, progress.series.title, progress.series.cover)
        if (fromFeed) openFromFeedSelection(drama, progress.episodeIndex + 1)
        else open(drama, resumeEp = progress.episodeIndex + 1, showDetails = false)
    }

    fun openCloudDrama(series: Series) {
        if (!series.id.matches(Regex("[0-9]{1,30}"))) return
        val fromFeed = state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED &&
            !state.value.navigation.isExplicitCatalog
        closeAccount()
        val drama = Drama(series.id, series.title, series.cover)
        if (fromFeed) openFromFeedSelection(drama) else open(drama)
    }

    fun open(drama: Drama, resumeEp: Int? = null, feed: Boolean = false,
        showDetails: Boolean = false,
        origin: PlaybackOrigin = if (feed) PlaybackOrigin.SWIPE_FEED
            else PlaybackOrigin.CATALOG) {
        val keepNextDramaPreparing = autoNextTargetDramaId == drama.id
        autoNextTargetDramaId = null
        if (finaleAdvanceFor != null && finaleAdvanceFor != drama.id) {
            pendingFeedAdvance = false
            clearFinaleAdvance()
        }
        if (finaleRecommendationRequestFor != null &&
            finaleRecommendationRequestFor != drama.id)
            cancelFinaleRecommendationWarmup()
        // Auto-advancing to the next drama should not unexpectedly expand a
        // minimized reel. Explicit new selections outside the current feed
        // still open fullscreen as usual.
        val retainMini = feed && state.value.feedMode && state.value.miniPlayerVisible
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
        startEpisodeHint = resumeEp

        // Reels frequently revisit a prefetched or recently watched series.
        // Resolve that in this main-thread navigation call, not in a later
        // coroutine. Otherwise we briefly publish detail=null, and Compose
        // displays a full-screen "reading episodes" loading page even though
        // we already have the episodes in memory.
        val api = if (origin == PlaybackOrigin.SWIPE_FEED) null else source
        val immediate = if (origin == PlaybackOrigin.SWIPE_FEED)
            visitedFeedDetails[drama.id] else null
        fun resolvedDetail(result: DramaDetail) = result.copy(drama = result.drama.copy(
            title = result.drama.title.ifBlank { drama.title },
            cover = result.drama.cover.ifBlank { drama.cover },
            total = result.episodes.size,
            synopsis = result.drama.synopsis.ifBlank { drama.synopsis },
            completionStatus = result.drama.completionStatus ?: drama.completionStatus,
            actors = result.drama.actors.ifEmpty { drama.actors },
            categories = result.drama.categories.ifEmpty { drama.categories },
            recommendationLabel = result.drama.recommendationLabel
                ?: drama.recommendationLabel,
        ))
        fun episodeIndexFor(result: DramaDetail): Int {
            val remembered = resumeEp ?: state.value.library
                .firstOrNull { it.drama.id == drama.id }?.episode
            return result.episodes.indexOfFirst { it.index == remembered }
                .takeIf { it >= 0 } ?: 0
        }
        val ready = immediate?.let(::resolvedDetail)
        val readyIndex = ready?.let(::episodeIndexFor)
        mutable.update { it.copy(
            offlinePlaybackUri = null,
            // Retain the previous media metadata until the replacement
            // series is resolved. The mini PlayerView needs an item to stay
            // mounted throughout a cross-drama transition.
            detail = ready ?: if (retainMini) it.detail else null,
            episodeIndex = readyIndex ?: it.episodeIndex,
            preparingDetail = ready == null,
            feedMode = feed,
            navigation = when {
                showDetails -> it.navigation.toSeries(origin).toDetails()
                // Auto-next while the user deliberately browses Home must
                // remain a mini player and never interrupt the catalog page.
                feed && retainMini -> it.navigation.toCatalog()
                feed -> it.navigation.toFeed()
                else -> it.navigation.toSeries(origin)
            },
            detailPageVisible = showDetails,
            miniPlayerVisible = retainMini,
            // A deliberate series selection dismisses account/settings/share
            // overlays. Automatic feed advance never closes the user's sheet.
            modal = if (feed) it.modal else Modal.NONE,
            relatedWorks = emptyList(),
            relatedSeries = null,
            relatedLoading = false,
            relatedError = null,
            guessWorks = emptyList(),
            guessesLoading = false,
            guessesError = null,
            openHongguoLoading = false,
            openHongguoSeasons = emptyList(),
            playbackError = null,
            playbackUrl = null,
            playing = false,
            nextDramaTransition = if (keepNextDramaPreparing)
                NextDramaTransition.PREPARING_VIDEO
            else NextDramaTransition.NONE,
        ) }
        diagnostics.record("DETAIL", "START")

        fun displayAndPlay(resolved: DramaDetail, index: Int) {
            if (version != detailId) return
            if (resolved.episodes.isEmpty() && startupRestoreSeriesId == drama.id) {
                fallbackFromUnavailableStartupDrama(drama.id)
                return
            }
            if (startupRestoreSeriesId == drama.id) startupRestoreSeriesId = null
            if (origin == PlaybackOrigin.SWIPE_FEED) rememberFeedDetail(resolved)
            mutable.update { it.copy(detail = resolved, episodeIndex = index,
                preparingDetail = false) }
            loadSocialStats(resolved.drama.id)
            if (api == null) loadLabels(resolved.drama.id)
            loadRelated(resolved.drama.id)
            if (showDetails) {
                loadSdkDetails(resolved.drama.id)
                loadGuesses(resolved.drama.id)
            } else play(index)
        }

        if (ready != null && readyIndex != null) {
            diagnostics.record("FEED", "DETAIL_RECENT_INSTANT")
            displayAndPlay(ready, readyIndex)
            return
        }
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = api?.detail(drama.id) ?: kxgSource.detail(drama.id)
                if (version != detailId) return@launch
                displayAndPlay(resolvedDetail(result), episodeIndexFor(result))
                diagnostics.record("DETAIL", "SUCCESS", elapsed(started))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == detailId) {
                    val message = errorText("读取剧集列表", e)
                    mutable.update { it.copy(preparingDetail = false,
                        playbackError = message, error = message) }
                    fallbackFromUnavailableStartupDrama(drama.id)
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
        val serviceWaitStarted = System.nanoTime()
        if (BackgroundPlaybackService.active == null) {
            withTimeout(8_000) {
                BackgroundPlaybackService.playerFlow.filterNotNull().first()
            }
        }
        diagnostics.record("PLAYBACK", "SERVICE_WAIT",
            elapsed(serviceWaitStarted))
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
            networkRecovered = { position ->
                recoverNetworkPlayback(current.vid, position)
            },
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
            allowFollowingDramaAtEnd = s.offlinePlaybackUri == null,
        )
        BackgroundPlaybackService.active?.configureNavigation(
            policy.previous, policy.next,
        ) { direction ->
            val snapshot = state.value
            if (snapshot.feedMode) {
                navigateFeed(direction)
            } else if (direction > 0 && snapshot.offlinePlaybackUri == null &&
                snapshot.episodeIndex == (snapshot.detail?.episodes?.lastIndex ?: -1)) {
                // Manual next in PiP is allowed at the finale regardless of
                // the autoplay toggle; continue the same recommendation feed.
                continueWithOfficialRecommendation()
            } else {
                playIfDifferent(snapshot.episodeIndex + direction)
            }
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
        if (snapshot.detail == null ||
            (snapshot.detailPageVisible && !snapshot.miniPlayerVisible) ||
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
                    current.detail != null &&
                    (!current.detailPageVisible || current.miniPlayerVisible) &&
                    current.playbackUrl != null && current.playing &&
                    current.playbackError == null) {
                    // Discard any cached decoder/source from the dead session.
                    // The next media load performs the existing authenticated
                    // stream request rather than reopening a stale data source.
                    diagnostics.record("BACKGROUND", "FOREGROUND_RECONNECT")
                    play(current.episodeIndex)
                }
            } finally {
                serviceRecoveryRequested = false
            }
        }
    }


    /** Manual transitions keep their original semantics. Network reconnection
     * uses the same pipeline but supplies the exact Media3 resume position. */
    fun play(index: Int) = playAt(index, null)

    private fun recoverNetworkPlayback(expectedVid: String, positionMs: Long) {
        val snapshot = state.value
        if (!snapshot.retryPlaybackAutomatically ||
            snapshot.offlinePlaybackUri != null ||
            snapshot.detail?.episodes?.getOrNull(snapshot.episodeIndex)?.vid != expectedVid ||
            BackgroundPlaybackService.active == null) return
        diagnostics.record("NETWORK_RESUME", "REFETCH_PLAYBACK_MODEL")
        playAt(snapshot.episodeIndex, positionMs.coerceAtLeast(0L))
    }

    private fun playAt(index: Int, networkResumeMs: Long?) {
        val detail = state.value.detail ?: return
        if (index !in detail.episodes.indices) return
        if (finaleRecommendationRequestFor != null &&
            index != detail.episodes.lastIndex)
            cancelFinaleRecommendationWarmup()
        // Notification commands run while the Activity is STOPPED. Reuse the
        // running foreground service rather than making a forbidden background
        // service start request on Android 12+.
        val serviceStarted = BackgroundPlaybackService.active != null || runCatching {
            getApplication<Application>().startService(
                Intent(getApplication<Application>(), BackgroundPlaybackService::class.java))
        }.isSuccess
        if (!serviceStarted) diagnostics.record("PLAYBACK", "SERVICE_START_DENIED")
        // A reconnect retains the previous service-owned media item and
        // notification while the signed playback model is fetched again.
        // Ordinary user swipes/selections still pause for transitions.
        if (networkResumeMs == null)
            BackgroundPlaybackService.active?.pauseForTransition()
        // The mini player must survive autoplay while another page is open.
        if (!state.value.miniPlayerVisible)
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
        val api = if (state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED)
            null else source
        val version = ++detailId
        val vid = detail.episodes[index].vid
        if (!state.value.feedMode && state.value.offlinePlaybackUri == null) {
            mutable.update { it.copy(returnSeries = Watched(
                detail.drama, detail.episodes[index].index, System.currentTimeMillis())) }
        }
        detailJob?.cancel()
        firstFrameFor = null
        val checkpoint = networkResumeMs ?: library.checkpoint(detail.drama.id, vid)
        mutable.update { it.copy(episodeIndex = index, playbackUrl = null,
            renderedVideoVid = null,
            resumePositionMs = checkpoint,
            playing = false, playbackError = null) }
        // Independent IO request: comment totals are available before tapping
        // the comment action, without delaying playback address preparation.
        loadComments(reset = true)
        diagnostics.record("PLAYBACK", "START")
        playbackStartAtMs = android.os.SystemClock.elapsedRealtime()
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
                    // Kaixinguo SDK resolves the MP4 model and content key;
                    // its CENC range source feeds our ONE service-owned player.
                    val quality = state.value.qualityLimit
                    val resolveStarted = System.nanoTime()
                    val prepared = com.yagay.hongguo.kxg.KxgPlaybackStreams.resolve(
                        getApplication(), openHongguo, vid, quality)
                    diagnostics.record("PLAYBACK", "KXG_STREAM_ACQUIRED",
                        elapsed(resolveStarted))
                    if (version != detailId ||
                        state.value.detail?.episodes
                            ?.getOrNull(state.value.episodeIndex)?.vid != vid ||
                        state.value.qualityLimit != quality) {
                        com.yagay.hongguo.kxg.KxgPlaybackStreams.forget(prepared.uri)
                        return@launch
                    }
                    mutable.update { it.copy(playbackUrl = prepared.uri, playing = true) }
                    if (serviceStarted) launchBackgroundMedia(
                        detail, index, prepared.uri, null, checkpoint, version)
                }
                diagnostics.record("PLAYBACK", "SOURCE_READY", elapsed(started))
                // Background continuity is owned by the service's network
                // listener; no Activity/Compose callback is required.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == detailId) {
                    if (networkResumeMs != null)
                        diagnostics.record("NETWORK_RESUME", "MODEL_REFRESH_FAILED")
                    mutable.update {
                        it.copy(playbackError = errorText("准备播放", e), playing = false)
                    }
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
        if (ep.vid != expectedVid) return
        // The background progress fallback can mark firstFrameFor without
        // a visible surface. Still reveal the frame only on this callback.
        if (s.renderedVideoVid != expectedVid)
            mutable.update { it.copy(renderedVideoVid = expectedVid) }
        if (firstFrameFor == ep.vid) return
        firstFrameFor = ep.vid
        if (s.nextDramaTransition != NextDramaTransition.NONE) {
            mutable.update { it.copy(nextDramaTransition = NextDramaTransition.NONE) }
            diagnostics.record("REELS", "NEXT_DRAMA_FIRST_FRAME")
        }
        diagnostics.record("MEDIA", "FIRST_FRAME")
        if (playbackStartAtMs > 0L) diagnostics.record("PLAYBACK",
            "SWITCH_TO_FIRST_FRAME", android.os.SystemClock.elapsedRealtime() - playbackStartAtMs)
        library.record(s.detail.drama, ep.index)
        rememberLastPlaybackMode(s.feedMode)
        updateLibrary()
        // Allow the initial video to render before fetching more recommendations.
        // Further feed pages and stream-model requests otherwise compete for
        // startup bandwidth and the upstream registration/session lock.
        if (s.feedMode && !deferRestoredFeedRecommendations &&
            OfficialReelsPolicy.needsMore(s.feedItems,
                s.feedIndex, s.hiddenRecommendationIds, s.feedHasMore, s.feedLoading)) {
            diagnostics.record("REELS", "POST_FIRST_FRAME_TOP_UP")
            loadOfficialReels()
        }
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
        if (s.nextDramaTransition != NextDramaTransition.NONE) {
            mutable.update { it.copy(nextDramaTransition = NextDramaTransition.NONE) }
            diagnostics.record("REELS", "NEXT_DRAMA_BACKGROUND_CONFIRMED")
        }
        diagnostics.record("MEDIA", "BACKGROUND_PROGRESS_CONFIRMED")
        library.record(s.detail.drama, ep.index)
        rememberLastPlaybackMode(s.feedMode)
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

    /**
     * A completed full-series player joins the real swipe recommendation
     * queue. Keep the existing Media3 session until a new recommended
     * episode is resolved; never restart episode one of the finished drama.
     */
    private fun continueWithOfficialRecommendation() {
        val snapshot = state.value
        val finishedDrama = snapshot.detail?.drama ?: return
        if (snapshot.offlinePlaybackUri != null) {
            diagnostics.record("REELS", "OFFLINE_SERIES_FINISHED")
            return
        }
        markFinaleAdvance(finishedDrama.id)
        diagnostics.record("REELS", "FINALE_AUTOCONTINUE_STARTED")
        // Swipe mode normally advances its existing official queue.
        // When the server cursor has already been exhausted, start a fresh
        // official recommendation session instead of leaving the last reel
        // frozen at the end of the video.
        if (snapshot.feedMode) {
            diagnostics.record("REELS", "FEED_FINISHED_NEXT_DRAMA")
            val next = OfficialReelsPolicy.nextIndex(
                snapshot.feedItems, snapshot.feedIndex, 1,
                snapshot.hiddenRecommendationIds,
            )
            if (next != null || snapshot.feedHasMore || snapshot.feedLoading) {
                navigateFeed(1)
            } else {
                deferRestoredFeedRecommendations = false
                pendingFeedAdvance = true
                mutable.update { it.copy(feedHasMore = true, feedError = null) }
                diagnostics.record("REELS", "FEED_FINISHED_NEW_RECOMMENDATION_CURSOR")
                loadOfficialReels(restart = true)
            }
            return
        }
        val continuation = OfficialReelsPolicy.continuationAfterCompletedSeries(
            finishedDrama = finishedDrama,
            existing = snapshot.feedItems,
            cameFromFeed = snapshot.navigation.origin == PlaybackOrigin.SWIPE_FEED,
        )
        val nextIndex = OfficialReelsPolicy.nextIndex(
            continuation.queue, continuation.currentIndex, 1,
            snapshot.hiddenRecommendationIds,
        )
        // The final episode has ended: the NEXT media item is a different
        // official recommendation, not a replay of this completed series.
        deferRestoredFeedRecommendations = false
        pendingFeedAdvance = false
        val alreadyFetchingRecommendation = snapshot.feedLoading &&
            finaleRecommendationRequestFor == finishedDrama.id
        mutable.update {
            it.copy(
                feedMode = true,
                feedItems = continuation.queue,
                feedIndex = continuation.currentIndex,
                // Preserve the finale's in-flight official request; do not
                // replace it with a second registration after the last frame.
                feedLoading = alreadyFetchingRecommendation,
                feedHasMore = if (nextIndex == null) true else it.feedHasMore,
                feedError = null,
                detailPageVisible = false,
                navigation = if (it.miniPlayerVisible) it.navigation.toCatalog()
                    else it.navigation.toFeed(),
            )
        }
        updateNotificationNavigation()
        if (nextIndex != null) {
            diagnostics.record("REELS", "SERIES_FINISHED_QUEUE_NEXT")
            navigateFeed(1)
        } else {
            // No queued recommendation: fetch through the same official
            // endpoint as swipe mode, preserving the finished drama as the
            // excluded seed. pendingFeedAdvance starts the next item as
            // soon as the new official batch is available.
            pendingFeedAdvance = true
            if (alreadyFetchingRecommendation) {
                diagnostics.record("REELS", "SERIES_FINISHED_WAIT_PREFETCH")
            } else {
                diagnostics.record("REELS", "SERIES_FINISHED_FETCH_RECOMMENDATIONS")
                loadOfficialReels(restart = true)
            }
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
                if (s.feedMode &&
                    s.returnSeries?.drama?.id == s.detail?.drama?.id) {
                    // A full-series video temporarily displayed in feed
                    // mode must continue its episodes without changing UI.
                    val next = s.episodeIndex + 1
                    mutable.update { it.copy(returnSeries = Watched(
                        s.detail!!.drama, s.detail.episodes[next].index,
                        System.currentTimeMillis())) }
                    diagnostics.record("NAVIGATION", "FEED_SERIES_AUTONEXT_KEEP_MODE")
                    play(next)
                } else if (s.feedMode) {
                    diagnostics.record("NAVIGATION", "REEL_AUTONEXT_TO_SERIES")
                    exitFeedToEpisode(s.episodeIndex + 1)
                } else {
                    diagnostics.record("PLAYBACK", "NEXT_EPISODE")
                    play(s.episodeIndex + 1)
                }
            }
            ReelAutoplayPolicy.Action.NEXT_DRAMA -> {
                continueWithOfficialRecommendation()
            }
        }
    }

    /** Explicit Browse Home action; never used as an implicit Back target. */
    fun minimizePlayer() {
        val snapshot = state.value
        if (snapshot.detail == null || snapshot.detailPageVisible) return
        mutable.update { it.copy(miniPlayerVisible = true, modal = Modal.NONE,
            navigation = it.navigation.toCatalog()) }
        diagnostics.record("NAVIGATION", "EXPLICIT_BROWSE_HOME")
        // Home data is not loaded at cold start now that reels is the root.
        if (state.value.section == Section.RECOMMEND && state.value.items.isEmpty())
            refresh()
    }

    /** Restore the video without changing its Media3 item or position. */
    fun restorePlayer() {
        val snapshot = state.value
        if (snapshot.detail == null) return
        mutable.update { it.copy(
            miniPlayerVisible = false,
            detailPageVisible = false,
            navigation = it.navigation.restorePlayer(it.feedMode),
            modal = Modal.NONE,
        ) }
        diagnostics.record("PLAYER", "MINI_RESTORE")
        if (snapshot.playing && snapshot.playbackUrl != null &&
            BackgroundPlaybackService.active == null) {
            recoverPlaybackAfterServiceLoss()
        }
    }

    /** Keep playing the same Media3 item while browsing the series details. */
    fun showDramaDetails() {
        val snapshot = state.value
        val detail = snapshot.detail ?: return
        if (snapshot.detailPageVisible) return
        loadSdkDetails(detail.drama.id)
        loadGuesses(detail.drama.id)
        mutable.update { it.copy(
            detailPageVisible = true,
            navigation = it.navigation.toDetails(),
            // The mini surface is a visual companion inside details, not a
            // navigation source. Back always goes to full player.
            miniPlayerVisible = it.miniPlayerVisible || it.playbackUrl != null,
        ) }
        diagnostics.record("DETAIL", "OPEN_WITH_MINI")
    }

    /** Back from details never chooses the legacy catalog based on mini UI. */
    fun backFromDramaDetails() {
        sdkDetailGeneration++
        sdkDetailJob?.cancel()
        val snapshot = state.value
        if (snapshot.navigation.destination != PlaybackDestination.DETAILS) return
        mutable.update { it.copy(
            navigation = it.navigation.fromDetails(),
            detailPageVisible = false,
            miniPlayerVisible = it.navigation.detailsParent ==
                PlaybackDestination.CATALOG,
            modal = Modal.NONE,
        ) }
        diagnostics.record("NAVIGATION", "DETAILS_TO_FULL_SERIES")
        // Reuse the current playing item. Only recover if the service died.
        if (snapshot.playing && snapshot.playbackUrl != null &&
            BackgroundPlaybackService.active == null) recoverPlaybackAfterServiceLoss()
    }

    /** Explicit episode selection from details opens fullscreen, unlike
     * automatic next-episode playback which stays in the mini player.
     */
    fun playFromDetails(index: Int) {
        val snapshot = state.value
        val episode = snapshot.detail?.episodes?.getOrNull(index) ?: return
        val alreadyActive = index == snapshot.episodeIndex &&
            snapshot.playbackUrl != null &&
            BackgroundPlaybackService.playerFlow.value?.currentMediaItem?.mediaId ==
                episode.vid
        if (snapshot.feedMode) {
            exitFeedToEpisode(index)
        } else {
            restorePlayer()
            if (!alreadyActive) play(index)
        }
    }

    /** Remember the active episode before swapping the sole Media3 source. */
    private fun checkpointActiveEpisode() {
        val snapshot = state.value
        val ep = snapshot.detail?.episodes?.getOrNull(snapshot.episodeIndex) ?: return
        val player = BackgroundPlaybackService.playerFlow.value ?: return
        if (player.currentMediaItem?.mediaId != ep.vid) return
        savePlaybackPosition(ep.vid, player.currentPosition.coerceAtLeast(0L),
            player.duration.coerceAtLeast(0L))
    }

    /** Return from the swipe feed to the most recently opened full series.
     * Same drama and episode: UI-only transition, no playback reload.
     * Different drama: checkpoint the feed, then reuse the sole Media3 player
     * to resume the remembered episode. The feed queue/index stays untouched. */
    fun returnToPreviousSeries() {
        val snapshot = state.value
        val previous = snapshot.returnSeries ?: return
        if (!snapshot.feedMode || snapshot.navigation.destination != PlaybackDestination.FEED)
            return
        if (snapshot.detail?.drama?.id == previous.drama.id) {
            // The very same Media3 source continued playing in feed mode.
            // Switching back must never seek to the old bookmarked episode.
            exitFeedToEpisode(snapshot.episodeIndex)
            diagnostics.record("NAVIGATION", "SERIES_RETURN_SAME_SOURCE")
            return
        }
        val activeDrama = snapshot.detail?.drama
            ?: snapshot.feedItems.getOrNull(snapshot.feedIndex)
        val activeEpisode = snapshot.detail?.episodes
            ?.getOrNull(snapshot.episodeIndex)?.index ?: 1
        checkpointActiveEpisode()
        mutable.update { it.copy(returnFeedItem = activeDrama?.let { drama ->
            Watched(drama, activeEpisode, System.currentTimeMillis())
        }) }
        rememberLastPlaybackMode(false)
        open(previous.drama, resumeEp = previous.episode,
            origin = PlaybackOrigin.SWIPE_FEED)
        diagnostics.record("NAVIGATION", "SERIES_RETURN_REOPEN_CHECKPOINT")
    }

    /** When a remembered series differs from the active reel, restore the
     * original recommendation position rather than replacing the feed queue. */
    private fun restoreFeedAfterSeriesReturn(): Boolean {
        val snapshot = state.value
        val bookmark = snapshot.returnFeedItem ?: return false
        if (snapshot.navigation.origin != PlaybackOrigin.SWIPE_FEED ||
            snapshot.feedMode || snapshot.navigation.destination != PlaybackDestination.SERIES)
            return false
        checkpointActiveEpisode()
        val index = snapshot.feedItems.indexOfFirst { it.id == bookmark.drama.id }
        val safeIndex = index.takeIf { it >= 0 }
        mutable.update { it.copy(returnFeedItem = null,
            feedIndex = safeIndex ?: it.feedIndex) }
        rememberLastPlaybackMode(true)
        open(bookmark.drama, resumeEp = bookmark.episode, feed = true)
        diagnostics.record("NAVIGATION", "SERIES_RETURN_ORIGINAL_FEED_POSITION")
        return true
    }

    /** Explicit Home/刷剧 action. A mini player must never turn the
     * bottom Home tab into a loop that restores an unrelated series. */
    fun returnToSwipeFeed() {
        val current = state.value
        if (current.feedMode) {
            if (current.navigation.destination != PlaybackDestination.FEED ||
                current.miniPlayerVisible) restorePlayer()
            diagnostics.record("NAVIGATION", "HOME_FEED_RESTORED")
            return
        }
        // Home is a visual mode switch, not a video source change: the full
        // episode stays attached to the sole Media3 player at its live
        // position. Add it to the reel queue if opened from Theater/My.
        val active = current.detail
        if (active != null && current.offlinePlaybackUri == null) {
            checkpointActiveEpisode()
            val selected = active.drama
            val queueIndex = current.feedItems.indexOfFirst { it.id == selected.id }
            val queue = if (queueIndex >= 0) current.feedItems
                else current.feedItems + selected
            val position = if (queueIndex >= 0) queueIndex else queue.lastIndex
            rememberLastPlaybackMode(true)
            mutable.update { it.copy(
                feedMode = true,
                navigation = it.navigation.toFeed(),
                returnSeries = Watched(selected,
                    active.episodes.getOrNull(current.episodeIndex)?.index ?: 1,
                    System.currentTimeMillis()),
                returnFeedItem = null,
                feedItems = queue,
                feedIndex = position,
                feedHasMore = true,
                miniPlayerVisible = false,
                detailPageVisible = false,
                modal = Modal.NONE,
            ) }
            updateNotificationNavigation()
            diagnostics.record("NAVIGATION", "HOME_TO_FEED_KEEP_MEDIA3")
            if (!state.value.feedLoading &&
                OfficialReelsPolicy.needsMore(queue, position,
                    current.hiddenRecommendationIds, true, false))
                loadOfficialReels()
            return
        }
        // When no playable cloud item exists, start the default feed.
        closePlayer()
        startFeed()
        diagnostics.record("NAVIGATION", "HOME_FEED_NEW_SESSION")
    }

    /** The route, not a mini-player flag, owns Back for full-series video. */
    fun backFromPlayer() {
        if (restoreFeedAfterSeriesReturn()) return
        if (restoreReelFeed()) return
        // Offline playback opened from the swipe drawer cannot simply
        // restore a different cloud MediaItem. Rejoin its prior swipe queue
        // explicitly instead of falling back to the catalog homepage.
        val snapshot = state.value
        if (snapshot.navigation.origin == PlaybackOrigin.SWIPE_FEED &&
            snapshot.offlinePlaybackUri != null) {
            val previous = snapshot.feedItems.getOrNull(snapshot.feedIndex)
                ?.takeIf { it.id !in snapshot.hiddenRecommendationIds }
            closePlayer()
            startFeed(previous)
            return
        }
        if (snapshot.navigation.origin == PlaybackOrigin.SWIPE_FEED) {
            // A broken or stale return queue must not unexpectedly open home.
            val previous = snapshot.feedItems.getOrNull(snapshot.feedIndex)
                ?.takeIf { it.id !in snapshot.hiddenRecommendationIds }
            closePlayer()
            startFeed(previous)
            diagnostics.record("NAVIGATION", "RESTORE_FEED_NEW_SESSION")
        } else {
            // Default Home is always the swipe feed. Catalog-origin series
            // Back must not minimize into a Home/mini restore loop.
            checkpointActiveEpisode()
            closePlayer()
            startFeed()
            diagnostics.record("NAVIGATION", "SERIES_BACK_TO_FEED")
        }
    }

    fun closePlayer() {
        BackgroundPlaybackService.stopActive("CLOSE_PLAYER")
        sdkDetailGeneration++
        sdkDetailJob?.cancel()
        homeFeedExcludedIds = emptySet()
        pendingFeedAdvance = false
        clearFinaleAdvance()
        autoNextTargetDramaId = null
        officialFeedJob?.cancel()
        officialFeedGeneration++
        finaleRecommendationRequestFor = null
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
        firstFrameFor = null
        mutable.update { it.copy(detail = null, playbackUrl = null,
            renderedVideoVid = null,
            offlinePlaybackUri = null,
            feedMode = false, navigation = it.navigation.toCatalog()
                .copy(origin = PlaybackOrigin.CATALOG),
            reelSearchRequest = 0L, detailPageVisible = false,
            miniPlayerVisible = false,
            feedItems = emptyList(), feedLoading = false,
            feedHasMore = false, feedError = null,
            nextDramaTransition = NextDramaTransition.NONE,
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
        val fromFeed = state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED &&
            !state.value.navigation.isExplicitCatalog
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                val (id, ep) = if (api != null) api.resolveShare(message)
                    else nativeSource.resolveShare(message)
                mutable.update { it.copy(modal = Modal.NONE) }
                val drama = Drama(id, "分享的短剧")
                if (fromFeed && state.value.navigation.origin == PlaybackOrigin.SWIPE_FEED)
                    openFromFeedSelection(drama, resumeEp = ep)
                else open(drama, ep)
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

    /** Foreground SAF export; the manager owns all jobs and stream handles. */
    fun downloadNative(vid: String, uri: Uri, previousTaskId: String? = null) {
        val details = state.value.detail
        val taskId = localDownloader.start(
            vid = vid,
            uri = uri,
            drama = details?.drama,
            episode = details?.episodes?.firstOrNull { it.vid == vid },
            previousTaskId = previousTaskId,
        )
        if (taskId != null) mutable.update { it.copy(modal = Modal.DOWNLOAD) }
    }

    fun cancelDownload(taskId: String) = localDownloader.cancel(taskId)

    fun retryLocalDownload(taskId: String) {
        if (localDownloader.retry(taskId))
            mutable.update { it.copy(modal = Modal.DOWNLOAD) }
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
