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

    @Test fun retriesAreBoundedAndFasterThanMediaRecovery() {
        assertTrue(CdnStreamingPolicy.MAX_RANGE_RETRIES == 1)
        assertTrue(CdnStreamingPolicy.READ_TIMEOUT_MS < CdnStreamingPolicy.BUFFER_STALL_MS)
    }
}
