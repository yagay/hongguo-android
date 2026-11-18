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

    fun isCancellation(error: IOException, threadInterrupted: Boolean): Boolean =
        threadInterrupted || (error is InterruptedIOException && error !is SocketTimeoutException)
}
