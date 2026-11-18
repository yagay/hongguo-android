package com.yagay.hongguo.kxg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class CdnStreamingPolicyTest {
    @Test fun socketTimeoutIsEligibleForBackupCdn() {
        assertFalse(CdnStreamingPolicy.isCancellation(SocketTimeoutException("read timed out"), false))
    }

    @Test fun actualThreadInterruptionMustNotRetry() {
        assertTrue(CdnStreamingPolicy.isCancellation(SocketTimeoutException("timed out"), true))
        assertTrue(CdnStreamingPolicy.isCancellation(InterruptedIOException("cancelled"), false))
    }

    @Test fun transportIOExceptionIsNotCancellation() {
        assertFalse(CdnStreamingPolicy.isCancellation(IOException("reset"), false))
    }

    @Test fun prolongedTinyReadsTriggerBoundedReconnection() {
        assertTrue(CdnStreamingPolicy.isSlowRead(2067, 1780))
        assertTrue(CdnStreamingPolicy.isSlowRead(1600, 1118))
        assertFalse(CdnStreamingPolicy.isSlowRead(357, 1_124_360))

        assertFalse(CdnStreamingPolicy.shouldReconnectSlowRange(
            3_900, 6000, 500_000, 0, false))
        assertTrue(CdnStreamingPolicy.shouldReconnectSlowRange(
            5_200, 6000, 500_000, 0, false))
        assertFalse(CdnStreamingPolicy.shouldReconnectSlowRange(
            5_200, 6000, 500_000, 1, false))
        assertFalse(CdnStreamingPolicy.shouldReconnectSlowRange(
            5_200, 6000, 50_000, 0, false))
        assertFalse(CdnStreamingPolicy.shouldReconnectSlowRange(
            5_200, 6000, 500_000, 0, true))
    }

    @Test fun rangeChunkIsSmallerThanLongPlaybackResource() {
        assertTrue(CdnStreamingPolicy.RANGE_CHUNK_BYTES in 512_000L..2_097_152L)
    }

    @Test fun retriesAreBoundedAndFasterThanMediaRecovery() {
        assertTrue(CdnStreamingPolicy.MAX_RANGE_RETRIES == 1)
        assertTrue(CdnStreamingPolicy.READ_TIMEOUT_MS < CdnStreamingPolicy.BUFFER_STALL_MS)
    }
}
