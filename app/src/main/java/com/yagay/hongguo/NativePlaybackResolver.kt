package com.yagay.hongguo

import com.hongguotv.core.RemoteVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Resolve native encrypted media with bounded transport/empty-model retries.
 * Playback policy is independent of UI, navigation and ExoPlayer lifecycle.
 *
 * The caller's generation/episode/quality guard is rechecked after every
 * backoff, so an obsolete request cannot install media after rapid swipes.
 */
internal class NativePlaybackResolver(
    private val source: NativeHongguoSource,
    private val diagnostics: DiagnosticLog,
) {
    suspend fun resolve(
        vid: String,
        ahead: String?,
        quality: Int,
        isCurrent: () -> Boolean,
    ): RemoteVideo? {
        // The core already performs multi-route, deadline-bound recovery.
        // At most ONE outer retry prevents a second complete 8-second model
        // round from stacking on top of multiple more 8-second rounds.
        var retried = false
        while (true) {
            try {
                return source.openVideo(
                    vid,
                    if (retried) null else ahead,
                    quality,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!retried && (PlaybackProblem.isEmptyPlaybackModel(error) ||
                        PlaybackProblem.isTransientTransport(error))) {
                    retried = true
                    val empty = PlaybackProblem.isEmptyPlaybackModel(error)
                    diagnostics.record("PLAYBACK",
                        if (empty) "PRIMARY_EMPTY_RETRY" else "PRIMARY_NETWORK_RETRY_1")
                    delay(if (empty) 700L else 800L)
                    if (!isCurrent()) {
                        diagnostics.record("PLAYBACK", "PRIMARY_RETRY_CANCELLED_STALE")
                        return null
                    }
                } else {
                    if (PlaybackProblem.isEmptyPlaybackModel(error))
                        diagnostics.record("PLAYBACK", "PRIMARY_EMPTY_RETRY_EXHAUSTED")
                    if (PlaybackProblem.isTransientTransport(error))
                        diagnostics.record("PLAYBACK", "PRIMARY_NETWORK_RETRY_EXHAUSTED")
                    throw error
                }
            }
        }
    }
}
