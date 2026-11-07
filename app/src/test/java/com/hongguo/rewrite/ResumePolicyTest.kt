package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePolicyTest {
    @Test fun resumesOnlyMidEpisode() {
        assertEquals(0L, ResumePolicy.resumable(2_000, 60_000))
        assertEquals(12_300L, ResumePolicy.resumable(12_300, 60_000))
        assertEquals(0L, ResumePolicy.resumable(59_000, 60_000))
        assertEquals(0L, ResumePolicy.resumable(10_000, 0))
    }
    @Test fun ignoresInvalidMediaDurations() {
        assertFalse(ResumePolicy.persistable(0, 90_000))
        assertFalse(ResumePolicy.persistable(85_000, -1))
        assertTrue(ResumePolicy.persistable(30_000, 90_000))
    }
}
