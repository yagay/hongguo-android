package com.yagay.hongguo.kxg

import android.content.Context
import android.os.SystemClock
import com.yagay.hongguo.DiagnosticLog
import com.yagay.hongguo.OpenHongguoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Preloads the next Kaixinguo playback model and range/CENC index only.
 * No second player, competing signed request or native RemoteVideo handle.
 * The same registered URI is transferred to the sole MediaSessionService.
 */
internal class KxgStreamPreloader(
    private val scope: CoroutineScope,
    private val context: Context,
    private val sdk: OpenHongguoSource,
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
    private var worker: Job? = null
    private var ready: KxgPlaybackStreams.Prepared? = null
    private var failedVid: String? = null
    private var retryAfterMs = 0L

    fun hasSeries(id: String): Boolean =
        target?.seriesId == id && (ready != null || worker?.isActive == true)

    fun retainSeries(id: String) {
        if (target?.seriesId != id) cancel()
    }

    fun retainVideo(vid: String, quality: Int) {
        if (target?.vid != vid || target?.quality != quality) cancel()
    }

    fun cancel() {
        worker?.cancel()
        worker = null
        target = null
        ready?.let { KxgPlaybackStreams.forget(it.uri) }
        ready = null
    }

    fun schedule(next: Target, useful: () -> Boolean) {
        if (target == next && (ready != null || worker?.isActive == true)) return
        cancel()
        if (!useful() || (failedVid == next.vid &&
                SystemClock.elapsedRealtime() < retryAfterMs)) return
        target = next
        worker = scope.launch {
            var obtained: KxgPlaybackStreams.Prepared? = null
            try {
                delay(350)
                if (target != next || !useful()) return@launch
                obtained = KxgPlaybackStreams.resolve(context, sdk, next.vid, next.quality)
                if (target == next && useful()) {
                    ready = obtained
                    obtained = null
                    diagnostics.record("KXG_PRELOAD", "MODEL_AND_CENC_READY")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedVid = next.vid
                retryAfterMs = SystemClock.elapsedRealtime() + 30_000
                diagnostics.record("KXG_PRELOAD", "MODEL_FAILED_COOLDOWN")
            } finally {
                obtained?.let { KxgPlaybackStreams.forget(it.uri) }
            }
        }
    }

    fun takeReady(vid: String, quality: Int): KxgPlaybackStreams.Prepared? {
        if (target?.vid != vid || target?.quality != quality) return null
        val result = ready ?: return null
        ready = null
        target = null
        worker = null
        diagnostics.record("KXG_PRELOAD", "READY_TRANSFERRED")
        return result
    }

    suspend fun takeOrAwait(vid: String, quality: Int): KxgPlaybackStreams.Prepared? {
        if (target?.vid != vid || target?.quality != quality) return null
        if (ready == null && worker?.isActive == true) {
            withTimeoutOrNull(2_000L) {
                while (ready == null && worker?.isActive == true) delay(40)
            }
        }
        val item = takeReady(vid, quality)
        if (item == null && target?.vid == vid) cancel()
        return item
    }
}
