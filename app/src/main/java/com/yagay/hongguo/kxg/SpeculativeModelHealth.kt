package com.yagay.hongguo.kxg

import dev.openhongguo.ErrorCode
import java.util.concurrent.TimeUnit

/**
 * Only new speculative lookups are suspended; existing authorized cached
 * models and normal user-initiated playback are not discarded.
 * Monotonic time avoids wall-clock jumps. Explicit server backoff cannot
 * be erased by a concurrent successful response.
 */
internal class SpeculativeModelHealth(
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    private var transientUntil: Long? = null
    private var refusalUntil: Long? = null
    private var emptyFailures = 0

    @Synchronized
    fun isCoolingDown(): Boolean {
        val now = nowMs()
        return transientUntil?.let { now < it } == true ||
            refusalUntil?.let { now < it } == true
    }

    @Synchronized
    fun onFailure(code: ErrorCode, retryAfterMs: Long = 0L): Long {
        val now = nowMs()
        val duration = when (code) {
            ErrorCode.RESPONSE_EMPTY -> {
                emptyFailures = (emptyFailures + 1).coerceAtMost(4)
                longArrayOf(300_000L, 600_000L, 1_200_000L, 1_800_000L)[emptyFailures - 1]
            }
            ErrorCode.TRANSPORT -> 60_000L
            ErrorCode.RATE_LIMITED, ErrorCode.ANONYMOUS_LIMITED,
            ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.CHALLENGE_REQUIRED,
            ErrorCode.SIGNATURE_REJECTED, ErrorCode.DEVICE_REJECTED,
            ErrorCode.SIGNER_UNAVAILABLE -> maxOf(300_000L, retryAfterMs.coerceAtLeast(0L))
            else -> return 0L // Episode-specific errors do not poison other episodes.
        }
        val until = if (now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
        if (code == ErrorCode.RESPONSE_EMPTY || code == ErrorCode.TRANSPORT) {
            transientUntil = maxOf(transientUntil ?: until, until)
        } else {
            refusalUntil = maxOf(refusalUntil ?: until, until)
        }
        return duration
    }

    @Synchronized
    fun onSuccess() {
        emptyFailures = 0
        transientUntil = null
    }
}
