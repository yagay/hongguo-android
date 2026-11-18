package com.yagay.hongguo.kxg

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Small bounded transport policy shared by the MP4 range reader and Media3.
 * A socket timeout inherits InterruptedIOException, but must still be eligible
 * for CDN failover. Actual thread cancellation always wins over recovery.
 */
internal object CdnStreamingPolicy {
    const val CONNECT_TIMEOUT_MS = 6_000
    const val READ_TIMEOUT_MS = 8_000
    const val SLOW_READ_MS = 1_500L
    const val BUFFER_STALL_MS = 12_000L
    const val MAX_RANGE_RETRIES = 1
    const val RANGE_CHUNK_BYTES = 1L shl 20 // 1 MiB, never until MP4 EOF
    const val SLOW_SAMPLE_MIN_MS = 800L
    const val SLOW_BYTES_PER_SECOND = 24L * 1024
    const val TRICKLE_WINDOW_MS = 4_000L
    const val TRICKLE_MAX_BYTES = 64L * 1024
    const val MIN_RECONNECT_REMAINING_BYTES = 128L * 1024

    /** Discard fast bursts when measuring sustained slow reads. */
    fun isSlowRead(elapsedMs: Long, bytes: Int): Boolean =
        bytes > 0 && elapsedMs >= SLOW_SAMPLE_MIN_MS &&
            bytes.toLong() * 1000 < SLOW_BYTES_PER_SECOND * elapsedMs

    /** Reopen at the next unread byte once, without reloading Media3. */
    fun shouldReconnectSlowRange(
        slowMs: Long, slowBytes: Long, remainingBytes: Long,
        retries: Int, interrupted: Boolean,
    ): Boolean = !interrupted &&
        retries < MAX_RANGE_RETRIES &&
        slowMs >= TRICKLE_WINDOW_MS &&
        slowBytes < TRICKLE_MAX_BYTES &&
        remainingBytes >= MIN_RECONNECT_REMAINING_BYTES

    fun isCancellation(error: IOException, threadInterrupted: Boolean): Boolean =
        threadInterrupted || (error is InterruptedIOException && error !is SocketTimeoutException)
}
