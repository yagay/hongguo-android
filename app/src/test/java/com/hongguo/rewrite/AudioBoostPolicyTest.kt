package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBoostPolicyTest {
    @Test fun originalAudioIsDefaultAndUsesZeroGain() {
        assertTrue(AudioBoostPolicy.valid(0))
        assertEquals(0, AudioBoostPolicy.targetGainMb(0))
    }

    @Test fun supportedOptionsMatchAndroidMillibels() {
        assertTrue(AudioBoostPolicy.valid(3))
        assertTrue(AudioBoostPolicy.valid(6))
        assertEquals(300, AudioBoostPolicy.targetGainMb(3))
        assertEquals(600, AudioBoostPolicy.targetGainMb(6))
    }

    @Test fun highUnsafeOrNegativeGainIsNeverApplied() {
        for (db in listOf(-6, -1, 1, 9, 12, 100)) {
            assertFalse(AudioBoostPolicy.valid(db))
            assertEquals(0, AudioBoostPolicy.targetGainMb(db))
        }
    }
}
