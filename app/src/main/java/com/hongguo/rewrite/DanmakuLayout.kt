package com.hongguo.rewrite

/**
 * Deterministic, seek-safe danmaku lane assignment.
 *
 * A lane is unavailable until its existing comment has completely left the
 * screen. Overcapacity comments are skipped rather than visually overlapping.
 * Never bases an animation on wall-clock time; scrubbing and pause must use
 * the active Media3 playback position.
 */
internal object DanmakuLayout {
    const val FLIGHT_MS: Long = 7_000L
    const val LANES: Int = 4

    fun flightForSpeed(speed: Int): Long = when (speed) {
        0 -> 10_000L
        2 -> 5_000L
        else -> FLIGHT_MS
    }

    data class Placed(
        val id: String,
        val text: String,
        val startMs: Long,
        val lane: Int,
    )

    fun arrange(
        entries: List<DanmakuEntry>,
        lanes: Int = LANES,
        durationMs: Long = FLIGHT_MS,
    ): List<Placed> {
        require(lanes in 1..12 && durationMs > 0)
        val endTimes = LongArray(lanes) { Long.MIN_VALUE }
        val output = ArrayList<Placed>()
        for (entry in entries.sortedWith(
            compareBy<DanmakuEntry> { it.timeMs }.thenBy { it.id }
        )) {
            if (entry.content.isBlank() || entry.timeMs < 0) continue
            val lane = endTimes.indices
                .filter { endTimes[it] <= entry.timeMs }
                .minByOrNull { endTimes[it] }
                ?: continue
            endTimes[lane] = entry.timeMs + durationMs
            output.add(Placed(entry.id, entry.content, entry.timeMs, lane))
        }
        return output
    }

    fun progress(currentMs: Long, startMs: Long, durationMs: Long = FLIGHT_MS): Float =
        if (durationMs <= 0) 1f else
            ((currentMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)

    fun xPosition(viewWidthPx: Float, textWidthPx: Float, progress: Float): Float =
        viewWidthPx - (viewWidthPx + textWidthPx) * progress.coerceIn(0f, 1f)
}
