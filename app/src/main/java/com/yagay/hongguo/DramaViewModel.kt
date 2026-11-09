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
            clearPreloadedNext()
            clearPreloadedLater()
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

    fun diskCacheEnabled(): Boolean =
        NativeDiskVideoCache.enabled(getApplication())

    fun wifiBackgroundPreloadEnabled(): Boolean =
        NativeDiskVideoCache.wifiPreload(getApplication())

    fun mobileBackgroundPreloadEnabled(): Boolean =
        NativeDiskVideoCache.mobilePreload(getApplication())

    fun diskCacheSizeMiB(): Long = NativeDiskVideoCache.sizeBytes() / (1024 * 1024)

    fun setDiskCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("disk_cache_enabled", enabled).apply()
        diagnostics.record("DISK_CACHE", if (enabled) "ENABLED" else "DISABLED")
        if (enabled) viewModelScope.launch(Dispatchers.IO) {
            NativeDiskVideoCache.prepare(getApplication())
        }
    }

    fun setWifiBackgroundPreload(enabled: Boolean) {
        prefs.edit().putBoolean("preload_wifi", enabled).apply()
        diagnostics.record("DISK_CACHE",
            if (enabled) "WIFI_PREFETCH_ON" else "WIFI_PREFETCH_OFF")
        if (!enabled) {
            clearPreloadedNext()
            clearPreloadedLater()
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
        } else if (NativeDiskVideoCache.mayBackgroundPreload(getApplication()))
            prepareUpcomingPlayback(state.value)
    }

    fun setMobileBackgroundPreload(enabled: Boolean) {
        prefs.edit().putBoolean("preload_mobile", enabled).apply()
        diagnostics.record("DISK_CACHE",
            if (enabled) "METERED_PREFETCH_ON" else "METERED_PREFETCH_OFF")
        if (!enabled && !NativeDiskVideoCache.mayBackgroundPreload(getApplication())) {
            clearPreloadedNext()
            clearPreloadedLater()
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
        } else if (NativeDiskVideoCache.mayBackgroundPreload(getApplication()))
            prepareUpcomingPlayback(state.value)
    }

    fun clearDiskVideoCache() {
        if (BackgroundPlaybackService.playerFlow.value?.currentMediaItem != null) {
            mutable.update { it.copy(settingsMessage = "请先关闭播放器再清理视频缓存") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                NativeDiskVideoCache.clear(getApplication())
            }
            diagnostics.record("DISK_CACHE", "USER_CLEARED")
            mutable.update { it.copy(settingsMessage = "已清理磁盘视频缓存") }
        }
    }

    fun setPreloadNext(enabled: Boolean) {
        prefs.edit().putBoolean("preload_next_episode", enabled).apply()
        mutable.update { it.copy(preloadNext = enabled) }
        if (!enabled) {
            clearPreloadedNext()
            clearPreloadedLater()
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
            cancelFinaleRecommendationWarmup()
        } else {
            // Switching preload ON while a video is already playing should
            // work immediately, not only after another first-frame callback.
            val snapshot = state.value
            val currentVid = snapshot.detail?.episodes
                ?.getOrNull(snapshot.episodeIndex)?.vid
            if (currentVid != null && currentVid == firstFrameFor)
                prepareUpcomingPlayback(snapshot)
        }
    }

    /** Change the actual encrypted-video prefetch budget without restart. */
    fun setPreloadBufferMiB(value: Int) {
        if (value !in VideoPreloadPolicy.OPTIONS_MIB ||
            value == state.value.preloadBufferMiB) return
        prefs.edit().putInt("preload_buffer_mib", value).apply()
        mutable.update { it.copy(preloadBufferMiB = value) }
        clearPreloadedNext()
        clearPreloadedLater()
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
        diagnostics.record("PLAYBACK", "PRELOAD_BUDGET_${value}M")
        val current = state.value
        val vid = current.detail?.episodes?.getOrNull(current.episodeIndex)?.vid
        if (current.preloadNext && vid != null && vid == firstFrameFor)
            prepareUpcomingPlayback(current)
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
            if (!state.value.feedMode) {
                feedPrefetchJob?.cancel()
                clearReelWarmVideo()
            }
        } else {
            val snapshot = state.value
            val current = snapshot.detail?.episodes
                ?.getOrNull(snapshot.episodeIndex)?.vid
            if (snapshot.preloadNext && current == firstFrameFor)
                prepareUpcomingPlayback(snapshot)
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
    private val playbackResolver = NativePlaybackResolver(nativeSource, diagnostics)
    private val catalogRepository = CatalogRepository(nativeSource) { source }
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
    private var preloadEpisodeTargetVid: String? = null
    /** Low-priority N+2 stream: separate from N+1 so advancing doesn't discard it. */
    private var preloadedLaterVid: String? = null
    private var preloadedLaterQuality = 0
    private var preloadedLaterVideo: RemoteVideo? = null
    private var preloadLaterJob: Job? = null
    private var preloadLaterTargetVid: String? = null

    private fun clearPreloadedLater() {
        preloadLaterJob?.cancel()
        preloadLaterJob = null
        preloadLaterTargetVid = null
        preloadedLaterVideo?.close()
        preloadedLaterVideo = null
        preloadedLaterVid = null
    }

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
        preloadEpisodeTargetVid = null
        preloadedNextVideo?.close()
        preloadedNextVideo = null
        preloadedNextVid = null
    }

    private fun isNextWarmupUseful(
        vid: String, quality: Int, bufferMiB: Int, generation: Long,
    ): Boolean {
        val s = state.value
        if (!s.preloadNext || s.qualityLimit != quality ||
            s.preloadBufferMiB != bufferMiB) return false
        val episodes = s.detail?.episodes ?: return false
        return (generation == detailId &&
            episodes.getOrNull(s.episodeIndex + 1)?.vid == vid) ||
            episodes.getOrNull(s.episodeIndex)?.vid == vid
    }

    private fun isActivePreloadJobForEpisode(
        vid: String, quality: Int, bufferMiB: Int,
    ): Boolean = state.value.preloadNext &&
        state.value.qualityLimit == quality &&
        state.value.preloadBufferMiB == bufferMiB &&
        (preloadedNextVid == vid ||
            BackgroundPlaybackService.playerFlow.value?.currentMediaItem?.mediaId == vid)

    private fun preloadFollowingEpisode() {
        val s = state.value
        if (!s.preloadNext ||
            !NativeDiskVideoCache.mayBackgroundPreload(getApplication()) ||
            (source != null && s.navigation.origin != PlaybackOrigin.SWIPE_FEED)) return
        val next = s.detail?.episodes?.getOrNull(s.episodeIndex + 1)?.vid ?: return
        if (next == preloadedNextVid && preloadedNextQuality == s.qualityLimit) {
            preloadFurtherEpisode(s)
            return
        }
        if (preloadEpisodeTargetVid == next && preloadEpisodeJob?.isActive == true)
            return
        // N+2 may still be resolving when the user advances to N+1.
        // Wait for that request instead of opening the same encrypted stream twice.
        if (preloadLaterTargetVid == next && preloadLaterJob?.isActive == true)
            return
        // Promote an already prepared N+2 after advancing to N+1.
        if (next == preloadedLaterVid && preloadedLaterQuality == s.qualityLimit &&
            preloadedLaterVideo != null) {
            clearPreloadedNext()
            preloadLaterJob?.cancel()
            preloadLaterJob = null
            preloadLaterTargetVid = null
            preloadedNextVideo = preloadedLaterVideo
            preloadedNextVid = preloadedLaterVid
            preloadedNextQuality = preloadedLaterQuality
            preloadedLaterVideo = null
            preloadedLaterVid = null
            diagnostics.record("PLAYBACK", "LATER_STREAM_PROMOTED")
            preloadFurtherEpisode(s)
            return
        }
        clearPreloadedNext()
        val generation = detailId
        val quality = s.qualityLimit
        val targetBytes = VideoPreloadPolicy.bytes(s.preloadBufferMiB)
        val originatingVid = s.detail?.episodes?.getOrNull(s.episodeIndex)?.vid
        diagnostics.record("PLAYBACK", "NEXT_PREFETCH_SCHEDULED")
        preloadEpisodeTargetVid = next
        preloadEpisodeJob = viewModelScope.launch {
            // Start ahead of the first frame if the source is already resolved.
            // Once a first frame exists, never insert an artificial delay.
            if (firstFrameFor != originatingVid) delay(1_200)
            if (!isNextWarmupUseful(next, quality, s.preloadBufferMiB, generation))
                return@launch
            var remote: RemoteVideo? = null
            try {
                // The upstream playback model intermittently returns an
                // empty response. Retry at most once for prefetch only, and
                // only while the target episode and quality are unchanged.
                remote = try {
                    nativeSource.openVideo(next, qualityLimit = quality,
                        preload = true, preloadBytes = targetBytes)
                } catch (first: CancellationException) {
                    throw first
                } catch (first: Exception) {
                    if (!PlaybackProblem.isEmptyPlaybackModel(first)) throw first
                    diagnostics.record("PLAYBACK", "PREFETCH_EMPTY_RETRY")
                    delay(1_500)
                    if (!isNextWarmupUseful(
                            next, quality, s.preloadBufferMiB, generation)) return@launch
                    nativeSource.openVideo(next, qualityLimit = quality,
                        preload = true, preloadBytes = targetBytes)
                }
                if (isNextWarmupUseful(
                        next, quality, s.preloadBufferMiB, generation)) {
                    preloadedNextVideo = remote
                    preloadedNextVid = next
                    preloadedNextQuality = quality
                    val ready = remote
                    remote = null
                    diagnostics.record("PLAYBACK", "NEXT_NATIVE_PREPARED")
                    // Extend the SAME RemoteVideo after publishing the usable
                    // first MiB. Fast episode switches can consume its current
                    // prefix even while later chunks are still downloading.
                    if (ready != null && targetBytes > VideoPreloadPolicy.STARTUP_BYTES) {
                        try {
                            withContext(Dispatchers.IO) {
                                ready.warm(targetBytes) {
                                    isActivePreloadJobForEpisode(next, quality, s.preloadBufferMiB)
                                }
                            }
                            NativeDiskVideoCache.warm(
                                getApplication(), ready, next, quality, targetBytes) {
                                    isActivePreloadJobForEpisode(
                                        next, quality, s.preloadBufferMiB)
                                }
                            diagnostics.record("PLAYBACK", "NEXT_VIDEO_WARMED")
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            diagnostics.record("PLAYBACK", "NEXT_WARM_PARTIAL")
                        }
                    }
                    // Once N+1 is usable, N+2 is allowed a small low-priority
                    // range. Never let this compete with the N+1 initial request.
                    preloadFurtherEpisode(state.value)
                    // Once the next EPISODE has a prepared range, also warm
                    // the next swipe drama, sequentially rather than in
                    // parallel with the episode model request.
                    val latest = state.value
                    val latestActiveVid = latest.detail?.episodes
                        ?.getOrNull(latest.episodeIndex)?.vid
                    if (latest.feedMode && latest.preloadNext &&
                        latest.detail?.drama?.id == s.detail?.drama?.id &&
                        latestActiveVid != null &&
                        firstFrameFor == latestActiveVid) {
                        // Fetch the missing official queue *after* the next
                        // episode's encrypted range has already been prepared.
                        if (!deferRestoredFeedRecommendations &&
                            OfficialReelsPolicy.needsMore(
                                latest.feedItems, latest.feedIndex,
                                latest.hiddenRecommendationIds,
                                latest.feedHasMore, latest.feedLoading,
                            )) {
                            loadOfficialReels()
                        } else if (latest.feedItems.drop(latest.feedIndex + 1).any {
                                it.id !in latest.hiddenRecommendationIds
                            }) {
                            prefetchFollowingFeedItem(latest.feedIndex)
                        }
                    }
                }
            } catch (e: CancellationException) {
                diagnostics.record("PLAYBACK", "NEXT_PREFETCH_CANCELLED")
                throw e
            } catch (_: Exception) {
                diagnostics.record("PLAYBACK", "NEXT_PREFETCH_UNAVAILABLE")
            } finally { remote?.close() }
        }
    }
    /** A slow N+2 request remains useful after advancing one episode. */
    private fun isLaterWarmupUseful(
        vid: String, quality: Int, bufferMiB: Int, generation: Long,
    ): Boolean {
        val s = state.value
        if (!s.preloadNext || s.qualityLimit != quality ||
            s.preloadBufferMiB != bufferMiB) return false
        val episodes = s.detail?.episodes ?: return false
        return (generation == detailId &&
            episodes.getOrNull(s.episodeIndex + 2)?.vid == vid) ||
            episodes.getOrNull(s.episodeIndex + 1)?.vid == vid
    }

    /** Prepare N+2 only after N+1 is ready; never hold a full episode here. */
    private fun preloadFurtherEpisode(snapshot: AppState) {
        if (!snapshot.preloadNext || snapshot.offlinePlaybackUri != null ||
            !NativeDiskVideoCache.mayBackgroundPreload(getApplication()) ||
            (source != null && snapshot.navigation.origin != PlaybackOrigin.SWIPE_FEED)) return
        val detail = snapshot.detail ?: return
        val next = detail.episodes.getOrNull(snapshot.episodeIndex + 1)?.vid ?: return
        val later = detail.episodes.getOrNull(snapshot.episodeIndex + 2)?.vid ?: return
        if (preloadedNextVid != next || preloadedNextVideo == null) return
        if (preloadedLaterVid == later && preloadedLaterQuality == snapshot.qualityLimit) return
        if (preloadLaterTargetVid == later && preloadLaterJob?.isActive == true) return
        clearPreloadedLater()
        val generation = detailId
        val quality = snapshot.qualityLimit
        val bufferMiB = snapshot.preloadBufferMiB
        preloadLaterTargetVid = later
        preloadLaterJob = viewModelScope.launch {
            var remote: RemoteVideo? = null
            try {
                // Yield to the critical startup prefix of N+1.
                delay(500)
                if (!isLaterWarmupUseful(later, quality, bufferMiB, generation))
                    return@launch
                diagnostics.record("PLAYBACK", "LATER_PREFETCH_SCHEDULED")
                remote = nativeSource.openVideo(later, qualityLimit = quality,
                    preload = true, preloadBytes = VideoPreloadPolicy.LATER_BYTES)
                if (!isLaterWarmupUseful(later, quality, bufferMiB, generation))
                    return@launch
                preloadedLaterVideo = remote
                preloadedLaterVid = later
                preloadedLaterQuality = quality
                val ready = remote
                remote = null
                diagnostics.record("PLAYBACK", "LATER_VIDEO_PREPARED")
                if (ready != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            ready.warm(VideoPreloadPolicy.LATER_BYTES) {
                                state.value.preloadNext &&
                                    state.value.qualityLimit == quality &&
                                    (preloadedLaterVid == later ||
                                        BackgroundPlaybackService.playerFlow.value
                                            ?.currentMediaItem?.mediaId == later)
                            }
                        }
                        NativeDiskVideoCache.warm(
                            getApplication(), ready, later, quality,
                            VideoPreloadPolicy.LATER_BYTES)
                        diagnostics.record("PLAYBACK", "LATER_VIDEO_WARMED")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        diagnostics.record("PLAYBACK", "LATER_WARM_PARTIAL")
                    }
                }
            } catch (cancel: CancellationException) {
                diagnostics.record("PLAYBACK", "LATER_PREFETCH_CANCELLED")
                throw cancel
            } catch (_: Exception) {
                diagnostics.record("PLAYBACK", "LATER_PREFETCH_UNAVAILABLE")
            } finally {
                remote?.close()
            }
            // If playback advanced while this slot was filling, hand it over
            // on the next main-loop turn rather than issuing a duplicate model request.
            if (preloadedLaterVid == later &&
                state.value.detail?.episodes
                    ?.getOrNull(state.value.episodeIndex + 1)?.vid == later) {
                viewModelScope.launch { preloadFollowingEpisode() }
            }
        }
    }

    /**
     * Schedule only the next useful stream while the current Media3 item is
     * already rendering. For non-final episodes, episode N+1 is more urgent
     * than the first video in a different drama. On the last episode, warm a
     * real official recommendation before the completion callback arrives.
     */
    private fun prepareUpcomingPlayback(snapshot: AppState) {
        val detail = snapshot.detail ?: return
        val currentEpisode = detail.episodes.getOrNull(snapshot.episodeIndex) ?: return
        if (!snapshot.preloadNext || snapshot.offlinePlaybackUri != null ||
            !NativeDiskVideoCache.mayBackgroundPreload(getApplication()) ||
            (firstFrameFor != currentEpisode.vid && snapshot.playbackUrl == null)) return

        val episodeDecision = SeamlessPrefetchPolicy.choose(
            enabled = snapshot.preloadNext,
            autoPlayNext = snapshot.autoPlayNext,
            inSwipeFeed = snapshot.feedMode,
            offline = snapshot.offlinePlaybackUri != null,
            hasNextEpisode = snapshot.episodeIndex < detail.episodes.lastIndex,
            hasQueuedNextDrama = false,
        )
        if (episodeDecision == SeamlessPrefetchPolicy.Next.NEXT_EPISODE) {
            preloadFollowingEpisode()
            return
        }
        if (episodeDecision == SeamlessPrefetchPolicy.Next.NONE) return

        val continuation = OfficialReelsPolicy.continuationAfterCompletedSeries(
            finishedDrama = detail.drama,
            existing = snapshot.feedItems,
            cameFromFeed = snapshot.feedMode ||
                snapshot.navigation.origin == PlaybackOrigin.SWIPE_FEED,
        )
        // Catalog/history playback is not itself a swipe session. Keep its
        // final episode visible, but seed an OFFSCREEN official queue that
        // excludes the finished drama from subsequent recommendations.
        if (!snapshot.feedMode &&
            (snapshot.feedItems != continuation.queue ||
                snapshot.feedIndex != continuation.currentIndex)) {
            mutable.update {
                it.copy(feedItems = continuation.queue,
                    feedIndex = continuation.currentIndex,
                    feedHasMore = true, feedError = null)
            }
        }
        val current = state.value
        val nextIndex = OfficialReelsPolicy.nextIndex(
            current.feedItems, current.feedIndex, 1,
            current.hiddenRecommendationIds,
        )
        val plan = SeamlessPrefetchPolicy.choose(
            enabled = current.preloadNext,
            autoPlayNext = current.autoPlayNext,
            inSwipeFeed = current.feedMode,
            offline = current.offlinePlaybackUri != null,
            hasNextEpisode = false,
            hasQueuedNextDrama = nextIndex != null,
        )
        if (plan == SeamlessPrefetchPolicy.Next.NEXT_DRAMA) {
            prefetchFollowingFeedItem(current.feedIndex)
            return
        }
        if (plan != SeamlessPrefetchPolicy.Next.FETCH_RECOMMENDATIONS ||
            current.feedLoading) return // The official request is already in flight.
        deferRestoredFeedRecommendations = false
        if (current.feedMode) {
            if (!current.feedHasMore) mutable.update { it.copy(feedHasMore = true) }
            diagnostics.record("REELS", "FINALE_FEED_TOP_UP")
            loadOfficialReels(restart = !current.feedHasMore)
        } else {
            mutable.update { it.copy(feedHasMore = true, feedError = null) }
            diagnostics.record("REELS", "FINALE_RECOMMENDATION_PREFETCH")
            loadOfficialReels(restart = true, warmingFinaleId = detail.drama.id)
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

    private var feedPrefetchJob: Job? = null
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
                addAll(snapshot.hiddenRecommendationIds)
                if (!relaxRecentExclusions) {
                    addAll(homeFeedExcludedIds)
                    addAll(localContent.recentlySwiped())
                }
            }
        mutable.update { it.copy(feedLoading = true, feedError = null) }
        officialFeedJob = viewModelScope.launch {
            try {
                // The recommendation endpoint may retry several official
                // categories in sequence. Bound the entire request so the
                // startup screen cannot spin indefinitely on a stalled host.
                val result = withTimeout(if (needsFirstDrama) 22_000L else 18_000L) {
                    nativeSource.officialReelsPage(restart, excluded,
                        realOnly = snapshot.reelsMode == "real",
                        comicOnly = snapshot.reelsMode == "comic",
                        firstItemOnly = needsFirstDrama)
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
                        feedError = if (merged.size == current.feedItems.size)
                            "官方暂未推荐新作品，可上滑重试" else null)
                }
                updateNotificationNavigation()
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
                        // Do not cancel the coroutine that delivered this batch.
                        if (warmingFinaleId != null &&
                            finaleRecommendationRequestFor == warmingFinaleId)
                            finaleRecommendationRequestFor = null
                        diagnostics.record("REELS", "FINALE_ADVANCE_READY")
                        navigateFeed(1)
                    } else if (finaleAdvanceFor != null) {
                        // Keep pending through an empty or duplicate-only batch.
                        retryFinaleAdvance("EMPTY")
                    } else {
                        pendingFeedAdvance = false
                    }
                } else if (mayPreloadFollowingDrama(state.value))
                    prefetchFollowingFeedItem(state.value.feedIndex)
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
                        feedError = "获取官方推荐超时，请点击重新获取") }
                    diagnostics.record("REELS", "OFFICIAL_TIMEOUT")
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
                        feedError = OfficialReelsFailure.userMessage(error)) }
                    diagnostics.record("REELS",
                        "OFFICIAL_FAIL_" + OfficialReelsFailure.code(error))
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
        if (!current.preloadNext || !mayPreloadFollowingDrama(current) ||
            !NativeDiskVideoCache.mayBackgroundPreload(getApplication())) return
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
                // During a multi-episode reel, prioritize the upcoming
                // episode. Once its range is ready, begin warming the
                // adjacent drama without contending for the initial bytes.
                val activeDetail = state.value.detail
                if (activeDetail != null &&
                    state.value.episodeIndex < activeDetail.episodes.lastIndex) {
                    val followingVid = activeDetail.episodes
                        .getOrNull(state.value.episodeIndex + 1)?.vid
                    if (followingVid != null && preloadedNextVid != followingVid)
                        return@launch
                }
                val first = detail.episodes.first().vid
                val quality = state.value.qualityLimit
                val bufferMiB = state.value.preloadBufferMiB
                val targetBytes = VideoPreloadPolicy.bytes(bufferMiB)
                warming = try {
                    nativeSource.openVideo(first, qualityLimit = quality,
                        preload = true, preloadBytes = targetBytes)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (firstFailure: Exception) {
                    // The official playback model can briefly return an
                    // empty source. Reuse the same bounded retry as next
                    // episode preload, BEFORE the last episode finishes.
                    if (!PlaybackProblem.isEmptyPlaybackModel(firstFailure))
                        throw firstFailure
                    diagnostics.record("FEED", "NEXT_MODEL_EMPTY_RETRY")
                    delay(1_250)
                    if (!isUpcomingReel(next.id) || !state.value.preloadNext ||
                        state.value.qualityLimit != quality ||
                        state.value.preloadBufferMiB != bufferMiB) return@launch
                    nativeSource.openVideo(first, qualityLimit = quality,
                        preload = true, preloadBytes = targetBytes)
                }
                if (!isUpcomingReel(next.id) || !state.value.preloadNext ||
                    state.value.qualityLimit != quality ||
                    state.value.preloadBufferMiB != bufferMiB) return@launch
                clearReelWarmVideo()
                reelWarmSeriesId = next.id
                reelWarmVid = first
                reelWarmQuality = quality
                reelWarmVideo = warming
                val ready = warming
                warming = null
                diagnostics.record("FEED", "NEXT_VIDEO_PREPARED")
                if (ready != null && targetBytes > VideoPreloadPolicy.STARTUP_BYTES) {
                    try {
                        withContext(Dispatchers.IO) {
                            ready.warm(targetBytes) {
                                state.value.preloadNext &&
                                    state.value.preloadBufferMiB == bufferMiB &&
                                    state.value.qualityLimit == quality &&
                                    (isUpcomingReel(next.id) ||
                                        BackgroundPlaybackService.playerFlow.value
                                            ?.currentMediaItem?.mediaId == first)
                            }
                        }
                        NativeDiskVideoCache.warm(
                            getApplication(), ready, first, quality, targetBytes) {
                                isUpcomingReel(next.id) ||
                                    BackgroundPlaybackService.playerFlow.value
                                        ?.currentMediaItem?.mediaId == first
                            }
                        diagnostics.record("FEED", "NEXT_VIDEO_WARMED")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        diagnostics.record("FEED", "NEXT_WARM_PARTIAL")
                    }
                }
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
                val activeDetail = current.detail
                if (activeAtStart != null && activeDetail != null &&
                    activeDetail.drama.id == activeAtStart &&
                    current.preloadNext && current.episodeIndex in activeDetail.episodes.indices &&
                    current.episodeIndex < activeDetail.episodes.lastIndex &&
                    firstFrameFor == activeDetail.episodes[current.episodeIndex].vid) {
                    preloadFollowingEpisode()
                }
            }
        }
    }

    private fun isUpcomingReel(seriesId: String): Boolean {
        val current = state.value
        return mayPreloadFollowingDrama(current) &&
            current.feedItems.drop(current.feedIndex + 1)
                .firstOrNull { it.id !in current.hiddenRecommendationIds }?.id == seriesId
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
            preloadNext = prefs.getBoolean("preload_next_episode", true),
            preloadBufferMiB = VideoPreloadPolicy.normalizedMiB(
                prefs.getInt("preload_buffer_mib", VideoPreloadPolicy.DEFAULT_MIB)
            ),
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
        // Start the disk index on IO at app launch. The playback path still
        // awaits this only if the user starts a video before initialization ends.
        viewModelScope.launch(Dispatchers.IO) {
            if (NativeDiskVideoCache.prepare(application))
                diagnostics.record("DISK_CACHE", "INDEX_READY")
            else if (NativeDiskVideoCache.enabled(application))
                diagnostics.record("DISK_CACHE", "INDEX_UNAVAILABLE")
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
                    ?: nativeSource.list(section, 0, snapshot.genre, snapshot.board,
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
                        ?: nativeSource.filters(snapshot.genre)
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
                    ?: nativeSource.list(section, snapshot.nextOffset,
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
        officialFeedJob?.cancel()
        officialFeedGeneration++
        finaleRecommendationRequestFor = null
        pendingFeedAdvance = false
        clearFinaleAdvance()
        autoNextTargetDramaId = null
        feedPrefetchJob?.cancel()
        clearReelWarmVideo()
        prefetchedFeedDetails.clear()
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
            prefetchFollowingFeedItem(index)
            if (direction > 0 && OfficialReelsPolicy.needsMore(
                    current.feedItems, index, current.hiddenRecommendationIds,
                    current.feedHasMore, current.feedLoading)) loadOfficialReels()
        } else if (direction > 0 && current.feedHasMore) {
            pendingFeedAdvance = true
            if (!current.feedLoading) loadOfficialReels()
        }
    }

    /** Enter the current drama's episode pager without replacing its active media.
     * Keep the feed queue/index so Back can return to this same drama and resume swiping.
     */
    fun exitFeedToEpisode(index: Int) {
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
        // Keep the prepared following drama while switching only the UI from
        // reel to episode mode; the sole Media3 item does not change.
        if (index != snapshot.detail?.episodes?.lastIndex || !snapshot.preloadNext) {
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
        }
        val currentMediaMatches = snapshot.playbackUrl != null &&
            index == snapshot.episodeIndex &&
            BackgroundPlaybackService.playerFlow.value?.currentMediaItem?.mediaId == target.vid
        rememberLastPlaybackMode(false)
        mutable.update {
            it.copy(feedMode = false,
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
        } else if (index == snapshot.detail?.episodes?.lastIndex &&
            snapshot.autoPlayNext && snapshot.preloadNext) {
            // Entering the series screen cancelled the official feed request
            // above. Re-arm finale prefetch while retaining this exact
            // playing media item and its current progress.
            diagnostics.record("REELS", "FINALE_PRELOAD_REARM_ON_MODE_SWITCH")
            prepareUpcomingPlayback(state.value)
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
        } else if (current.preloadNext) {
            prefetchFollowingFeedItem(current.feedIndex)
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
        clearPreloadedNext()
        clearPreloadedLater()
        startEpisodeHint = resumeEp

        // Reels frequently revisit a prefetched or recently watched series.
        // Resolve that in this main-thread navigation call, not in a later
        // coroutine. Otherwise we briefly publish detail=null, and Compose
        // displays a full-screen "reading episodes" loading page even though
        // we already have the episodes in memory.
        val api = if (origin == PlaybackOrigin.SWIPE_FEED) null else source
        val prefetched = if (api == null) prefetchedFeedDetails.remove(drama.id) else null
        val cached = if (origin == PlaybackOrigin.SWIPE_FEED)
            visitedFeedDetails[drama.id] else null
        val immediate = prefetched ?: cached
        fun resolvedDetail(result: DramaDetail) = result.copy(drama = result.drama.copy(
            title = result.drama.title.ifBlank { drama.title },
            cover = result.drama.cover.ifBlank { drama.cover },
            total = result.episodes.size,
            synopsis = result.drama.synopsis.ifBlank { drama.synopsis },
            completionStatus = result.drama.completionStatus ?: drama.completionStatus,
            actors = result.drama.actors.ifEmpty { drama.actors },
            categories = result.drama.categories.ifEmpty { drama.categories },
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
            diagnostics.record("FEED", if (prefetched != null)
                "DETAIL_PREFETCH_INSTANT" else "DETAIL_RECENT_INSTANT")
            displayAndPlay(ready, readyIndex)
            return
        }
        detailJob = viewModelScope.launch {
            val started = System.nanoTime()
            try {
                val result = api?.detail(drama.id) ?: nativeSource.detail(drama.id)
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
                    clearPreloadedNext()
                    clearPreloadedLater()
                    diagnostics.record("BACKGROUND", "FOREGROUND_RECONNECT")
                    play(current.episodeIndex)
                }
            } finally {
                serviceRecoveryRequested = false
            }
        }
    }


    fun play(index: Int) {
        val detail = state.value.detail ?: return
        if (index !in detail.episodes.indices) return
        if (finaleRecommendationRequestFor != null &&
            index != detail.episodes.lastIndex)
            cancelFinaleRecommendationWarmup()
        // A different episode should not retain a prepared next-drama stream.
        if (index != detail.episodes.lastIndex && !state.value.feedMode) {
            feedPrefetchJob?.cancel()
            clearReelWarmVideo()
        }
        // Notification commands run while the Activity is STOPPED. Reuse the
        // running foreground service rather than making a forbidden background
        // service start request on Android 12+.
        val serviceStarted = BackgroundPlaybackService.active != null || runCatching {
            getApplication<Application>().startService(
                Intent(getApplication<Application>(), BackgroundPlaybackService::class.java))
        }.isSuccess
        if (!serviceStarted) diagnostics.record("PLAYBACK", "SERVICE_START_DENIED")
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
        detailJob?.cancel()
        closeNativeVideo()
        val reelCached = state.value.feedMode &&
            reelWarmSeriesId == detail.drama.id &&
            reelWarmVid == vid && reelWarmQuality == state.value.qualityLimit
        val cachedNative = if (reelCached) reelWarmVideo
            else if (preloadedNextVid == vid &&
                preloadedNextQuality == state.value.qualityLimit) preloadedNextVideo
            else if (preloadedLaterVid == vid &&
                preloadedLaterQuality == state.value.qualityLimit) preloadedLaterVideo
            else BackgroundPlaybackService.active?.takeRecentVideo(
                vid, state.value.qualityLimit)
        diagnostics.record("PLAYBACK",
            if (cachedNative != null) "PREFETCH_CACHE_HIT" else "PREFETCH_CACHE_MISS")
        if (cachedNative != null) {
            diagnostics.record("PLAYBACK", "PREPARED_STREAM_REUSED")
            // Count locally available prefix, not merely a successful model lookup.
            val prefixKiB = runCatching { cachedNative.cachedBytes(0L) / 1024 }
                .getOrDefault(0)
            diagnostics.record("PLAYBACK", "CACHE_PREFIX_KIB_${prefixKiB}")
        }
        if (cachedNative != null && cachedNative === preloadedLaterVideo) {
            preloadLaterJob?.cancel()
            preloadLaterJob = null
            preloadLaterTargetVid = null
            preloadedLaterVideo = null
            preloadedLaterVid = null
            diagnostics.record("PLAYBACK", "LATER_STREAM_REUSED")
        }
        // Transfer the warmed reel's ownership to the active playback request.
        if (reelCached) {
            reelWarmVideo = null
            reelWarmVid = null
            reelWarmSeriesId = null
        }
        val pendingNextJob = if (cachedNative == null &&
            preloadEpisodeTargetVid == vid && preloadEpisodeJob?.isActive == true &&
            api == null) preloadEpisodeJob else null
        if (pendingNextJob == null) preloadEpisodeJob?.cancel()
        if (pendingNextJob == null) {
            preloadEpisodeJob = null
            preloadEpisodeTargetVid = null
        }
        if (cachedNative !== preloadedNextVideo) preloadedNextVideo?.close()
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
                    val ahead = if (state.value.preloadNext &&
                        NativeDiskVideoCache.mayBackgroundPreload(getApplication()))
                        detail.episodes.getOrNull(index + 1)?.vid else null
                    val quality = state.value.qualityLimit
                    // A rapid advance may catch the N+1 model still in flight.
                    // Give it a short bounded handoff window before issuing
                    // another network request for the same video ID.
                    val inFlightVideo = if (pendingNextJob != null)
                        kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                            while (preloadedNextVid != vid && pendingNextJob.isActive) {
                                delay(40)
                            }
                            if (preloadedNextVid == vid) {
                                val prepared = preloadedNextVideo
                                preloadedNextVideo = null
                                preloadedNextVid = null
                                preloadEpisodeJob?.cancel()
                                preloadEpisodeJob = null
                                preloadEpisodeTargetVid = null
                                if (prepared != null)
                                    diagnostics.record("PLAYBACK", "INFLIGHT_PRELOAD_REUSED")
                                prepared
                            } else null
                        } else null
                    if (pendingNextJob != null && inFlightVideo == null) {
                        diagnostics.record("PLAYBACK", "INFLIGHT_PRELOAD_TIMEOUT")
                        preloadEpisodeJob?.cancel()
                        preloadEpisodeJob = null
                        preloadEpisodeTargetVid = null
                        preloadedNextVideo?.close()
                        preloadedNextVideo = null
                        preloadedNextVid = null
                    }
                    val video = cachedNative ?: inFlightVideo ?: playbackResolver.resolve(
                        vid, ahead, quality,
                        isCurrent = {
                            val current = state.value
                            version == detailId &&
                                current.detail?.episodes
                                    ?.getOrNull(current.episodeIndex)?.vid == vid &&
                                current.qualityLimit == quality
                        },
                    ) ?: return@launch
                    if (version != detailId) {
                        video.close()
                        return@launch
                    }
                    activeNativeVideo = video
                    activeNativeVid = vid
                    val nativeUri = "native://episode/" + vid
                    mutable.update { it.copy(playbackUrl = nativeUri,
                        playing = true) }
                    if (serviceStarted) {
                        // Scan/load the disk index on IO, never during the
                        // service's Media3 configuration on the main thread.
                        withContext(Dispatchers.IO) {
                            if (NativeDiskVideoCache.prepare(getApplication())) {
                                NativeDiskVideoCache.register(video, vid, quality)
                            }
                        }
                        if (version == detailId) launchBackgroundMedia(
                            detail, index, nativeUri, video, checkpoint, version)
                    }
                }
                diagnostics.record("PLAYBACK", "SOURCE_READY", elapsed(started))
                // Begin preparing the upcoming stream while the current item
                // is buffering. The job checks its generation after a grace
                // period so it cannot replace a rapid swipe's target.
                if (version == detailId && api == null) {
                    val latest = state.value
                    if (index < detail.episodes.lastIndex) {
                        preloadFollowingEpisode()
                    } else if (latest.preloadNext && latest.autoPlayNext) {
                        // Resolve official continuation during the finale itself,
                        // not in the ENDED callback. The request runs after a
                        // short grace interval to protect current startup.
                        viewModelScope.launch {
                            delay(1_200)
                            if (version == detailId &&
                                state.value.detail?.drama?.id == detail.drama.id &&
                                state.value.episodeIndex == index &&
                                state.value.playbackUrl != null) {
                                diagnostics.record("REELS", "FINALE_EARLY_WARMUP")
                                prepareUpcomingPlayback(state.value)
                            }
                        }
                    }
                }
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
        clearPreloadedNext()
        clearPreloadedLater()
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
                s.feedIndex, s.hiddenRecommendationIds, s.feedHasMore,
                s.feedLoading) &&
            (!s.preloadNext ||
                s.episodeIndex >= (s.detail?.episodes?.lastIndex ?: 0))) {
            // A live episode's next range beats new official feed pages.
            // Request more recommendations after the next episode is warm.
            diagnostics.record("REELS", "POST_FIRST_FRAME_TOP_UP")
            loadOfficialReels()
        }
        prepareUpcomingPlayback(s)
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
        prepareUpcomingPlayback(s)
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
                if (s.feedMode) {
                    // The first recommendation episode has finished.
                    // Enter the series player before advancing so the UI,
                    // pager, and navigation are all episode-scoped. Keep the
                    // same Media3 session; exitFeedToEpisode invokes play()
                    // only for the newly selected episode.
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

    /** The route, not a mini-player flag, owns Back for full-series video. */
    fun backFromPlayer() {
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
        } else if (snapshot.detail == null) closePlayer()
        else minimizePlayer()
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
        clearPreloadedLater()
        firstFrameFor = null
        mutable.update { it.copy(detail = null, playbackUrl = null,
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
