package com.hongguo.android

/**
 * Throttles periodic on-device progress snapshots without skipping explicit pause/seek/exit saves.
 * The authoritative position still updates in memory between writes.
 */
internal class PlaybackSavePolicy(
    private val intervalMs: Long = 10_000L,
    private val maxKeys: Int = 128,
) {
    init {
        require(intervalMs > 0)
        require(maxKeys > 0)
    }

    private val lastSavedAt = LinkedHashMap<String, Long>(maxKeys, 0.75f, true)

    fun shouldWrite(key: String, nowMs: Long, force: Boolean): Boolean {
        val last = lastSavedAt[key]
        if (!force && last != null && nowMs >= last && nowMs - last < intervalMs) return false
        lastSavedAt[key] = nowMs
        while (lastSavedAt.size > maxKeys) lastSavedAt.remove(lastSavedAt.keys.first())
        return true
    }
}
