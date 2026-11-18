package com.yagay.hongguo.kxg

import java.util.concurrent.TimeUnit

/**
 * Small, pure policy for foreground-first CENC warmups.
 * Only encrypted bytes are kept in volatile memory; never persist URLs or keys.
 */
internal object KxgWarmupPolicy {
    const val PREFIX_BYTES = 1 * 1024 * 1024
    const val CHUNK_BYTES = 256 * 1024
    const val MAX_CACHED_MODELS = 2
    const val MIN_FOREGROUND_BUFFER_MS = 6_000L
    const val MAX_WARMUP_WAIT_MS = 12_000L
    const val WARMUP_DEADLINE_MS = 10_000L

    fun cacheValidUntilMs(expiresAtSeconds: Long, nowMs: Long): Long {
        val capped = nowMs + TimeUnit.MINUTES.toMillis(2)
        if (expiresAtSeconds <= 0L) return nowMs + 30_000L
        if (expiresAtSeconds >= Long.MAX_VALUE / 1000L) return capped
        // Stay one minute ahead of signed CDN URL expiry.
        return minOf(expiresAtSeconds * 1000L - 60_000L, capped)
    }

    fun reusable(validUntilMs: Long, nowMs: Long): Boolean =
        validUntilMs > nowMs + 5_000L

    fun bufferedEnough(playbackStateReady: Boolean, playing: Boolean,
                       bufferedAheadMs: Long): Boolean =
        playbackStateReady && playing && bufferedAheadMs >= MIN_FOREGROUND_BUFFER_MS
}

/** Snapshot-based encrypted prefix: the decoder decrypts only on actual Media3 reads. */
internal class EncryptedPrefixMemory {
    @Volatile private var prefix = ByteArray(0)
    val size: Int get() = prefix.size

    @Synchronized
    fun publish(bytes: ByteArray) {
        if (bytes.size > prefix.size && bytes.size <= KxgWarmupPolicy.PREFIX_BYTES)
            prefix = bytes
    }

    fun copy(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= Int.MAX_VALUE || length <= 0) return 0
        val snapshot = prefix
        val start = position.toInt()
        if (start >= snapshot.size) return 0
        val count = minOf(length, snapshot.size - start)
        snapshot.copyInto(target, offset, start, start + count)
        return count
    }
}
