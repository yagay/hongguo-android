package com.hongguo.rewrite

/** Pure checkpoint bounds: no resume from the start or inside the final credits. */
internal object ResumePolicy {
    fun resumable(positionMs: Long, durationMs: Long): Long {
        if (positionMs < 5_000L || durationMs <= 0L || positionMs >= durationMs - 12_000L)
            return 0L
        return positionMs.coerceAtMost(durationMs)
    }

    fun persistable(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 15_000L && positionMs in 1L until (durationMs - 10_000L)
}
