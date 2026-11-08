package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelClockPolicyTest {
    @Test fun nextEpisodeNeverReceivesPreviousEpisodesPositionOrDuration() {
        val first = ReelClockPolicy.sample(
            "ep1", "ep1", 88_000L, 90_000L, true)
        assertTrue(first.currentItem)
        assertEquals(88_000L, first.positionMs)
        assertEquals(90_000L, first.durationMs)

        // ExoPlayer is deliberately shared: the UI already shows ep2 while
        // the service may still hold ep1 for a few frames.
        val switching = ReelClockPolicy.sample(
            "ep2", "ep1", 88_250L, 90_000L, true)
        assertFalse(switching.currentItem)
        assertEquals(0L, switching.positionMs)
        assertEquals(0L, switching.durationMs)
        assertFalse(switching.playing)

        val second = ReelClockPolicy.sample(
            "ep2", "ep2", 3_500L, 125_000L, true)
        assertTrue(second.currentItem)
        assertEquals(3_500L, second.positionMs)
        assertEquals(125_000L, second.durationMs)
        assertTrue(second.playing)
    }

    @Test fun unknownDurationIsNotPresentedAsARealLength() {
        val snapshot = ReelClockPolicy.sample(
            "ep2", "ep2", 0L, -9223372036854775807L, false)
        assertTrue(snapshot.currentItem)
        assertEquals(0L, snapshot.durationMs)
        assertFalse(snapshot.playing)
    }

    @Test fun invalidAndOutOfBoundsClocksAreSafeForSeeks() {
        val negative = ReelClockPolicy.sample("ep2", "ep2", -1L, 60_000L, false)
        assertEquals(0L, negative.positionMs)
        assertEquals(60_000L, negative.durationMs)

        val beyondEnd = ReelClockPolicy.sample(
            "ep2", "ep2", 72_000L, 60_000L, false)
        assertEquals(60_000L, beyondEnd.positionMs)

        assertFalse(ReelClockPolicy.sample(
            "ep2", null, 72_000L, 60_000L, true).currentItem)
    }
}
