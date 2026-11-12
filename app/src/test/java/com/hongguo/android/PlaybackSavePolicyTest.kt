package com.hongguo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSavePolicyTest {
    @Test fun skipsOnlyPeriodicWritesWithinTheWindow() {
        val policy = PlaybackSavePolicy(intervalMs = 10_000)
        assertTrue(policy.shouldWrite("episode1", 100, false))
        assertFalse(policy.shouldWrite("episode1", 200, false))
        assertFalse(policy.shouldWrite("episode1", 10_099, false))
        assertTrue(policy.shouldWrite("episode1", 10_100, false))
    }

    @Test fun explicitSeekPauseAndExitAlwaysSave() {
        val policy = PlaybackSavePolicy()
        assertTrue(policy.shouldWrite("episode1", 10, false))
        assertTrue(policy.shouldWrite("episode1", 20, true))
        assertFalse(policy.shouldWrite("episode1", 21, false))
        assertTrue(policy.shouldWrite("episode2", 21, false))
        assertTrue(policy.shouldWrite("episode1", 22, true))
    }

    @Test fun backwardsClockDoesNotBlockPersistence() {
        val policy = PlaybackSavePolicy()
        assertTrue(policy.shouldWrite("episode1", 5000, false))
        assertTrue(policy.shouldWrite("episode1", 1000, false))
    }

    @Test fun keyWindowHasBoundedHistory() {
        val policy = PlaybackSavePolicy(maxKeys = 2)
        assertTrue(policy.shouldWrite("a", 1, false))
        assertTrue(policy.shouldWrite("b", 1, false))
        assertTrue(policy.shouldWrite("c", 1, false))
        assertTrue("Evicted keys must be written on their next playback", policy.shouldWrite("a", 2, false))
    }
}
