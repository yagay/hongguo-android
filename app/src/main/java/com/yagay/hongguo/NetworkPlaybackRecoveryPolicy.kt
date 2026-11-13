package com.yagay.hongguo

/**
 * Background network handoff policy for the single MediaSession player.
 * Only temporary network IO failures can trigger a fresh playback model;
 * unsupported codecs, authentication and HTTP denials never do.
 */
internal object NetworkPlaybackRecoveryPolicy {
    // Media3 PlaybackException: IO_UNSPECIFIED / NETWORK_CONNECTION_FAILED / TIMEOUT.
    private val transientIoCodes = setOf(2000, 2001, 2002)

    fun isTransientMediaIo(errorCode: Int): Boolean = errorCode in transientIoCodes

    /** Maximum new playback-model requests per continuous reconnection episode. */
    const val MAX_MODEL_ATTEMPTS = 4

    /**
     * Allow Media3 to resume on its own first; subsequent failures retry at
     * increasing intervals. Attempts are 1-based; no tight looping.
     */
    fun retryDelayMs(nextAttempt: Int): Long = when (nextAttempt) {
        1 -> 2_500L
        2 -> 4_000L
        3 -> 8_000L
        else -> 16_000L
    }

    fun canAttempt(nextAttempt: Int): Boolean =
        nextAttempt in 1..MAX_MODEL_ATTEMPTS


    fun shouldArm(
        enabled: Boolean, sourceActive: Boolean, playRequested: Boolean,
        ended: Boolean, offlineFile: Boolean,
    ): Boolean = enabled && sourceActive && playRequested && !ended && !offlineFile

    fun shouldRefresh(
        pending: Boolean, validatedNetwork: Boolean, playRequested: Boolean,
        playingAgain: Boolean, ended: Boolean,
    ): Boolean = pending && validatedNetwork && playRequested && !playingAgain && !ended

    fun resumeMs(positionMs: Long, lastKnownMs: Long): Long =
        (if (positionMs > 0L) positionMs else lastKnownMs).coerceAtLeast(0L)
}
