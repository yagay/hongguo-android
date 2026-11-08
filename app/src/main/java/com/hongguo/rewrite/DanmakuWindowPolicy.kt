package com.hongguo.rewrite

/**
 * Cursor selection for the upstream timed-comment API.
 *
 * A seek must not page 30-second windows from the beginning all the way to
 * the new playback time. Requests are read-only and never fabricate comments.
 */
internal object DanmakuWindowPolicy {
    private const val WINDOW_MS = 30_000L
    private const val LOOKAHEAD_MS = 15_000L
    private const val SEEK_GAP_MS = 30_000L

    data class Request(val startMs: Long, val replaceWindow: Boolean)

    fun next(
        positionMs: Long,
        durationMs: Long,
        sameEpisode: Boolean,
        loadedFromMs: Long,
        loadedUntilMs: Long,
    ): Request? {
        if (durationMs !in 1_000L..86_400_000L) return null
        val position = positionMs.coerceIn(0L, durationMs - 1L)
        val newWindow = !sameEpisode ||
            loadedUntilMs <= loadedFromMs ||
            position < loadedFromMs ||
            position > loadedUntilMs + SEEK_GAP_MS
        if (newWindow) {
            val start = (position / WINDOW_MS) * WINDOW_MS
            return Request(start, true).takeIf { start < durationMs }
        }
        if (loadedUntilMs >= durationMs) return null
        if (position + LOOKAHEAD_MS < loadedUntilMs) return null
        return Request(loadedUntilMs, false)
    }
}
