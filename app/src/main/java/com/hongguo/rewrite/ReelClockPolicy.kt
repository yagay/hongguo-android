package com.hongguo.rewrite

/**
 * A single Media3 ExoPlayer is reused across all episodes. The UI must not
 * expose the old MediaItem's position/duration to the next episode's dock.
 */
internal object ReelClockPolicy {
    data class Sample(
        val currentItem: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
    )

    fun sample(
        expectedVid: String,
        actualMediaId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ): Sample {
        if (expectedVid.isBlank() || expectedVid != actualMediaId)
            return Sample(false, 0L, 0L, false)
        val validDuration = durationMs.takeIf { it > 0L } ?: 0L
        val validPosition = positionMs.coerceAtLeast(0L).let {
            if (validDuration > 0L) it.coerceAtMost(validDuration) else it
        }
        return Sample(true, validPosition, validDuration, isPlaying)
    }
}
