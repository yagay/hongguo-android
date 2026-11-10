package com.yagay.hongguo

import android.content.Context
import android.os.SystemClock
import com.hongguotv.core.RemoteVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-target coordinator for the service-owned TV Media3 preload manager.
 * One signed model/source preparation is enough: the same RemoteVideo is
 * transferred to the active player instead of being acquired twice.
 */
internal class NativeStreamPreloader(
    private val scope: CoroutineScope,
    private val context: Context,
    private val source: NativeHongguoSource,
    private val diagnostics: DiagnosticLog,
) {
    data class Target(
        val seriesId: String,
        val vid: String,
        val quality: Int,
        val durationMs: Long,
        val title: String,
        val episode: Int,
        val totalEpisodes: Int,
        val cover: String,
    )

    private var target: Target? = null
    private var prepareJob: Job? = null
    private var readyVideo: RemoteVideo? = null
    private var failedVid: String? = null
    private var retryAfterMs = 0L

    fun hasSeries(seriesId: String) =
        target?.seriesId == seriesId &&
            (readyVideo != null || prepareJob?.isActive == true)

    fun retainSeries(seriesId: String) {
        if (target?.seriesId != seriesId) cancel()
    }

    fun retainVideo(vid: String, quality: Int) {
        if (target?.vid != vid || target?.quality != quality) cancel()
    }

    fun cancel() {
        prepareJob?.cancel()
        prepareJob = null
        target = null
        val video = readyVideo
        readyVideo = null
        if (video != null) {
            BackgroundPlaybackService.active?.discardNativePreload(video)
            if (BackgroundPlaybackService.active?.ownsVideo(video) != true) video.close()
        }
    }

    /** Schedule only the nearest useful video; no speculative model endpoint. */
    fun schedule(next: Target, useful: () -> Boolean) {
        if (target == next &&
            (readyVideo != null || prepareJob?.isActive == true)) return
        cancel()
        if (failedVid == next.vid && SystemClock.elapsedRealtime() < retryAfterMs) {
            diagnostics.record("PRELOAD_V3", "COOLDOWN_SKIP")
            return
        }
        if (!useful()) return
        target = next
        diagnostics.record("PRELOAD_V3", "MODEL_SCHEDULED")
        prepareJob = scope.launch {
            var remote: RemoteVideo? = null
            try {
                delay(350)
                if (target != next || !useful()) return@launch
                // Normal video-model route; speculative route was observed
                // returning many empty responses in production diagnostics.
                remote = source.openVideo(next.vid, qualityLimit = next.quality)
                if (target != next || !useful()) return@launch
                withContext(Dispatchers.IO) {
                    if (NativeDiskVideoCache.prepare(context)) {
                        NativeDiskVideoCache.register(
                            context, remote!!, next.vid, next.quality)
                    }
                }
                if (target != next || !useful()) return@launch
                readyVideo = remote
                remote = null
                val video = readyVideo!!
                val queued = BackgroundPlaybackService.active?.queueNativePreload(
                    next.vid, next.quality, video, next.title, next.episode,
                    next.totalEpisodes, next.cover, next.durationMs) == true
                diagnostics.record("PRELOAD_V3",
                    if (queued) "MEDIA3_QUEUED" else "MODEL_READY_NO_SERVICE")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failedVid = next.vid
                retryAfterMs = SystemClock.elapsedRealtime() + 30_000
                diagnostics.record("PRELOAD_V3", "MODEL_FAILED_COOLDOWN")
            } finally {
                remote?.close()
            }
        }
    }

    fun takeReady(vid: String, quality: Int): RemoteVideo? {
        val candidate = target
        if (candidate?.vid != vid || candidate.quality != quality) return null
        val video = readyVideo ?: return null
        readyVideo = null
        target = null
        prepareJob = null
        diagnostics.record("PRELOAD_V3", "READY_TRANSFERRED")
        return video
    }

    suspend fun takeOrAwait(vid: String, quality: Int): RemoteVideo? {
        val selected = target
        if (selected?.vid != vid || selected.quality != quality) return null
        if (readyVideo == null && prepareJob?.isActive == true) {
            diagnostics.record("PRELOAD_V3", "HANDOFF_WAIT")
            withTimeoutOrNull(2_000L) {
                while (readyVideo == null && prepareJob?.isActive == true) delay(40)
            }
        }
        val prepared = takeReady(vid, quality)
        if (prepared == null && target?.vid == vid) {
            // Never race a second signed model against an abandoned prefetch.
            diagnostics.record("PRELOAD_V3", "HANDOFF_TIMEOUT_CANCEL")
            cancel()
        }
        return prepared
    }
}
