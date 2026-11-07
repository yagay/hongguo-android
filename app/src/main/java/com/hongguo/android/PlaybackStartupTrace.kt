package com.hongguo.android

/**
 * Monotonic, redacted first-frame timing probe. Each checkpoint keeps only the first
 * occurrence so repeated buffer/progress callbacks do not overwrite the actual latency.
 * Never accepts server responses, episode IDs, URLs, account metadata or error messages.
 */
internal class PlaybackStartupTrace(private val clock: () -> Long = System::nanoTime) {
    private val started = clock()
    private val checkpoints = LinkedHashMap<String, Long>()
    private val allowed = setOf("detail_ready", "episode_selected", "device_register",
        "video_model", "index", "reused", "cached", "compatibility", "downloading",
        "processing", "media_ready", "player_buffering", "player_ready",
        "first_frame", "failed")

    @Synchronized fun mark(label: String) {
        if (label in allowed && label !in checkpoints)
            checkpoints[label] = ((clock() - started).coerceAtLeast(0L) / 1_000_000L)
    }

    fun elapsedMillis(): Long = ((clock() - started).coerceAtLeast(0L) / 1_000_000L)

    @Synchronized fun report(): String = buildString {
        append("startup_timing_ms")
        checkpoints.forEach { (key, ms) -> append("\n").append(key).append("=").append(ms) }
    }
}
