package com.yagay.hongguo.kxg

import java.util.concurrent.TimeUnit

/**
 * Configurable, file-backed encrypted CENC prefix cache. No signed URLs,
 * CENC keys, or decrypted media are persisted. Files are purged at app start.
 * Large user budgets are on disk, never a single giant ByteArray.
 */
internal object KxgWarmupPolicy {
    const val MIB = 1024 * 1024
    const val CHUNK_BYTES = 256 * 1024
    val prefixOptionsMb = setOf(4, 8, 16, 32, 64, 128, 256, 512)
    val totalOptionsMb = setOf(256, 512, 1024, 2048, 4096)
    val countOptions = setOf(1, 2, 3, 5)
    val bufferOptionsSeconds = setOf(0, 3, 6, 12, 20, 30)
    val deadlineOptionsSeconds = setOf(30, 60, 120, 300, 600)

    const val DEFAULT_PREFIX_MB = 32
    const val DEFAULT_TOTAL_MB = 1024
    const val DEFAULT_COUNT = 2
    const val DEFAULT_BUFFER_SECONDS = 6
    const val DEFAULT_DEADLINE_SECONDS = 120
    const val MAX_CACHED_MODELS = 5
    const val MAX_WARMUP_WAIT_MS = 12_000L

    fun validPrefixMb(value: Int) = value in prefixOptionsMb
    fun validTotalMb(value: Int) = value in totalOptionsMb
    fun validPreloadCount(value: Int) = value in countOptions
    fun validBufferSeconds(value: Int) = value in bufferOptionsSeconds
    fun validDeadlineSeconds(value: Int) = value in deadlineOptionsSeconds

    fun cacheValidUntilMs(expiresAtSeconds: Long, nowMs: Long): Long {
        // A known signed expiry permits a longer reuse window. Otherwise
        // unknown-expiry models have a deliberately short lifetime.
        val capped = nowMs + TimeUnit.MINUTES.toMillis(10)
        if (expiresAtSeconds <= 0L) return nowMs + 120_000L
        if (expiresAtSeconds >= Long.MAX_VALUE / 1000L) return capped
        return minOf(expiresAtSeconds * 1000L - 60_000L, capped)
    }

    fun hardExpired(expiresAtSeconds: Long, nowMs: Long): Boolean =
        expiresAtSeconds > 0L && expiresAtSeconds <= nowMs / 1000L

    fun reusable(validUntilMs: Long, nowMs: Long): Boolean =
        validUntilMs > nowMs + 5_000L

    fun bufferedEnough(ready: Boolean, playing: Boolean,
                       bufferedAheadMs: Long, requiredSeconds: Int = DEFAULT_BUFFER_SECONDS): Boolean =
        ready && playing && bufferedAheadMs >= requiredSeconds * 1000L

    fun effectivePrefixBytes(perVideoMb: Int, totalMb: Int): Int =
        minOf(perVideoMb, totalMb).coerceIn(1, 512) * MIB
}
