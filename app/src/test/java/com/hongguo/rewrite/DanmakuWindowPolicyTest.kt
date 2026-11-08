package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuWindowPolicyTest {
    @Test fun firstPageStartsAtCurrentTimelineWindow() {
        assertEquals(
            DanmakuWindowPolicy.Request(0L, true),
            DanmakuWindowPolicy.next(
                positionMs = 1_000L,
                durationMs = 120_000L,
                sameEpisode = false,
                loadedFromMs = 0L,
                loadedUntilMs = 0L,
            ),
        )
        assertEquals(
            DanmakuWindowPolicy.Request(180_000L, true),
            DanmakuWindowPolicy.next(
                positionMs = 194_000L,
                durationMs = 300_000L,
                sameEpisode = false,
                loadedFromMs = 0L,
                loadedUntilMs = 0L,
            ),
        )
    }

    @Test fun nearbyPlaybackPagesOnlyWhenWithinFifteenSecondsOfEdge() {
        assertNull(DanmakuWindowPolicy.next(
            10_000L, 180_000L, true, 0L, 30_000L))
        assertEquals(
            DanmakuWindowPolicy.Request(30_000L, false),
            DanmakuWindowPolicy.next(
                17_000L, 180_000L, true, 0L, 30_000L),
        )
    }

    @Test fun seekingFarForwardOrBackwardResetsWindow() {
        assertEquals(
            DanmakuWindowPolicy.Request(180_000L, true),
            DanmakuWindowPolicy.next(
                191_000L, 300_000L, true, 0L, 30_000L),
        )
        assertEquals(
            DanmakuWindowPolicy.Request(30_000L, true),
            DanmakuWindowPolicy.next(
                36_000L, 300_000L, true, 180_000L, 210_000L),
        )
    }

    @Test fun autoplayNextEpisodeDoesNotReuseThePreviousEpisodeCursor() {
        val request = DanmakuWindowPolicy.next(
            0L, 92_000L, false, 60_000L, 90_000L)
        assertTrue(request!!.replaceWindow)
        assertEquals(0L, request.startMs)
    }

    @Test fun durationUnknownAndFullyLoadedSegmentsDoNotSpamRequests() {
        assertNull(DanmakuWindowPolicy.next(
            0L, 0L, false, 0L, 0L))
        assertNull(DanmakuWindowPolicy.next(
            20_000L, 90_000L, true, 0L, 90_000L))
        assertFalse(DanmakuWindowPolicy.next(
            70_000L, 90_000L, true, 0L, 90_000L)?.replaceWindow ?: false)
    }
}
