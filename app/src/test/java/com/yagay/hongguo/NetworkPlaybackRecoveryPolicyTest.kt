package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkPlaybackRecoveryPolicyTest {
    @Test fun onlyTransientIoErrorsQualify() {
        listOf(2000, 2001, 2002).forEach {
            assertTrue(NetworkPlaybackRecoveryPolicy.isTransientMediaIo(it))
        }
        listOf(2003, 2004, 2006, 3001, 4001, 5001).forEach {
            assertFalse(NetworkPlaybackRecoveryPolicy.isTransientMediaIo(it))
        }
    }

    @Test fun noRecoveryAfterUserPauseOrAtNaturalEnd() {
        val p = NetworkPlaybackRecoveryPolicy
        assertTrue(p.shouldArm(true, true, true, false, false))
        assertFalse(p.shouldArm(false, true, true, false, false))
        assertFalse(p.shouldArm(true, true, false, false, false))
        assertFalse(p.shouldArm(true, true, true, true, false))
        assertFalse(p.shouldArm(true, true, true, false, true))
    }

    @Test fun restoresOnlyWhenStillStalledAfterNetworkReturn() {
        val p = NetworkPlaybackRecoveryPolicy
        assertTrue(p.shouldRefresh(true, true, true, false, false))
        assertFalse(p.shouldRefresh(true, false, true, false, false))
        assertFalse(p.shouldRefresh(true, true, false, false, false))
        assertFalse(p.shouldRefresh(true, true, true, true, false))
        assertFalse(p.shouldRefresh(true, true, true, false, true))
        assertFalse(p.shouldRefresh(false, true, true, false, false))
    }

    @Test fun preferCurrentPlaybackPositionAndFallbackToLastProgress() {
        val p = NetworkPlaybackRecoveryPolicy
        assertEquals(87_000L, p.resumeMs(87_000L, 81_000L))
        assertEquals(81_000L, p.resumeMs(0L, 81_000L))
        assertEquals(0L, p.resumeMs(-1L, -100L))
        // Seeking backwards must not jump forward to an older checkpoint.
        assertEquals(1_000L, p.resumeMs(1_000L, 81_000L))
    }
}
