package com.hongguo.rewrite

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
        var networkRetries = 0
        var emptyRetries = 0
        while (true) {
            try {
                return source.openVideo(
                    vid,
                    if (networkRetries == 0 && emptyRetries == 0) ahead else null,
                    quality,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val waitMs = when {
                    PlaybackProblem.isEmptyPlaybackModel(error) && emptyRetries < 1 -> {
                        emptyRetries++
                        diagnostics.record("PLAYBACK", "PRIMARY_EMPTY_RETRY")
                        1_500L
                    }
                    PlaybackProblem.isTransientTransport(error) && networkRetries < 2 -> {
                        networkRetries++
                        diagnostics.record("PLAYBACK", "PRIMARY_NETWORK_RETRY_" + networkRetries)
                        networkRetries * 1_500L
                    }
                    else -> {
                        if (PlaybackProblem.isEmptyPlaybackModel(error))
                            diagnostics.record("PLAYBACK", "PRIMARY_EMPTY_RETRY_EXHAUSTED")
                        if (PlaybackProblem.isTransientTransport(error))
                            diagnostics.record("PLAYBACK", "PRIMARY_NETWORK_RETRY_EXHAUSTED")
                        throw error
                    }
                }
                delay(waitMs)
                if (!isCurrent()) {
                    diagnostics.record("PLAYBACK", "PRIMARY_RETRY_CANCELLED_STALE")
                    return null
                }
            }
        }
    }
}
