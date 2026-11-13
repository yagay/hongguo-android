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
