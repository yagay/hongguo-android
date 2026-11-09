package com.yagay.hongguo

/** The media duration is unknown until Media3 has prepared the source. */
internal object PlaybackTimeFormatter {
    fun format(ms: Long, available: Boolean = true): String {
        if (!available || ms < 0L) return "--:--"
        val sec = ms / 1000
        val hours = sec / 3600
        val minutes = (sec / 60) % 60
        val seconds = sec % 60
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    fun label(positionMs: Long, durationMs: Long): String =
        format(positionMs) + " / " + format(durationMs, available = durationMs > 0L)
}
