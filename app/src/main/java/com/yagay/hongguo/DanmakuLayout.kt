package com.yagay.hongguo

/**
 * Deterministic, seek-safe danmaku lane assignment.
 *
 * The legacy arranger reserves a full flight; the geometry-aware arranger
 * permits safe same-lane following without overlapping or catching up.
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
        val widthPx: Float = 0f,
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

    /**
     * Independently implemented width-aware lane packing.
     *
     * Comments scroll linearly from x = viewportWidth to x = -textWidth over
     * [durationMs]. Two consecutive comments on one lane are safe only if the
     * earlier one's right edge stays at least [safeGapPx] before the later
     * one's left edge for their ENTIRE overlapping lifetime. Because both
     * velocities are constant, checking both endpoints is sufficient.
     *
     * No Android or Compose dependencies: deterministic on pause, seeking,
     * different display widths and playback speeds.
     */
    fun arrangeCollisionAware(
        entries: List<DanmakuEntry>,
        lanes: Int,
        durationMs: Long,
        viewportWidthPx: Float,
        safeGapPx: Float,
        measureTextPx: (String) -> Float,
    ): List<Placed> {
        require(lanes in 1..12 && durationMs > 0L)
        require(viewportWidthPx > 0f && viewportWidthPx.isFinite())
        require(safeGapPx >= 0f && safeGapPx.isFinite())
        val last = arrayOfNulls<Placed>(lanes)
        val placed = ArrayList<Placed>()
        for (entry in entries.sortedWith(
            compareBy<DanmakuEntry> { it.timeMs }.thenBy { it.id }
        )) {
            if (entry.timeMs < 0L || entry.content.isBlank()) continue
            val text = entry.content.take(44)
            val measured = measureTextPx(text)
            if (!measured.isFinite() || measured <= 0f) continue
            var selected = -1
            var oldestTime = Long.MAX_VALUE
            for (lane in last.indices) {
                val previous = last[lane]
                if (previous != null && !canFollow(
                    previous, entry.timeMs, measured,
                    viewportWidthPx, durationMs, safeGapPx,
                )) continue
                val previousTime = previous?.startMs ?: Long.MIN_VALUE
                if (selected == -1 || previousTime < oldestTime) {
                    selected = lane
                    oldestTime = previousTime
                }
            }
            if (selected < 0) continue
            val comment = Placed(entry.id, text, entry.timeMs, selected, measured)
            last[selected] = comment
            placed.add(comment)
        }
        return placed
    }

    private fun canFollow(
        previous: Placed,
        nextStartMs: Long,
        nextWidthPx: Float,
        viewportWidthPx: Float,
        durationMs: Long,
        safeGapPx: Float,
    ): Boolean {
        if (nextStartMs <= previous.startMs) return false
        val elapsed = (nextStartMs - previous.startMs).toFloat()
        if (elapsed >= durationMs) return true
        val oldSpeed = (viewportWidthPx + previous.widthPx) / durationMs
        val nextSpeed = (viewportWidthPx + nextWidthPx) / durationMs
        // At the second entry's spawn time, the earlier right edge must be
        // sufficiently left of the viewport's right edge.
        val previousRightAtSpawn =
            viewportWidthPx + previous.widthPx - oldSpeed * elapsed
        if (previousRightAtSpawn + safeGapPx > viewportWidthPx) return false
        // If the new item is longer/faster, it may still catch the older one
        // before the older item's right edge disappears at the left boundary.
        val timeUntilOldExit = durationMs - elapsed
        val nextLeftAtOldExit =
            viewportWidthPx - nextSpeed * timeUntilOldExit
        return nextLeftAtOldExit >= safeGapPx
    }

    /**
     * The placed entries are chronological. Binary-search the short active
     * window instead of allocating/filtering up to 600 entries every display
     * frame. Preserve the original chronological 'take(lanes)' behavior.
     */
    fun visibleAt(
        placed: List<Placed>,
        currentMs: Long,
        durationMs: Long,
        maxVisible: Int,
    ): List<Placed> {
        if (placed.isEmpty() || durationMs <= 0L || maxVisible <= 0) return emptyList()
        val expiredAtOrBefore = currentMs - durationMs
        var low = 0
        var high = placed.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (placed[middle].startMs <= expiredAtOrBefore) low = middle + 1
            else high = middle
        }
        val result = ArrayList<Placed>(minOf(maxVisible, 12))
        var index = low
        while (index < placed.size && result.size < maxVisible) {
            val entry = placed[index++]
            if (entry.startMs > currentMs) break
            result.add(entry)
        }
        return result
    }

    fun progress(currentMs: Long, startMs: Long, durationMs: Long = FLIGHT_MS): Float =
        if (durationMs <= 0) 1f else
            ((currentMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)

    fun xPosition(viewWidthPx: Float, textWidthPx: Float, progress: Float): Float =
        viewWidthPx - (viewWidthPx + textWidthPx) * progress.coerceIn(0f, 1f)
}
