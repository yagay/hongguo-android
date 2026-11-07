package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuLayoutTest {
    @Test fun separateConcurrentMessagesIntoDistinctLanes() {
        val messages = (0..5).map { DanmakuEntry("id$it", "弹幕 $it", 1_000L) }
        val placed = DanmakuLayout.arrange(messages)
        assertEquals(4, placed.size)
        assertEquals(4, placed.map { it.lane }.toSet().size)
    }

    @Test fun reuseLaneOnlyAfterMessageExits() {
        val messages = listOf(
            DanmakuEntry("one", "第一条", 100L),
            DanmakuEntry("two", "第二条", 7_099L),
            DanmakuEntry("three", "第三条", 7_100L),
        )
        val placed = DanmakuLayout.arrange(messages, lanes = 1, durationMs = 7_000L)
        assertEquals(listOf("one", "three"), placed.map { it.id })
    }

    @Test fun configurableSpeedAndTrackLimits() {
        assertEquals(10_000L, DanmakuLayout.flightForSpeed(0))
        assertEquals(7_000L, DanmakuLayout.flightForSpeed(1))
        assertEquals(5_000L, DanmakuLayout.flightForSpeed(2))
        assertEquals(1, DanmakuLayout.arrange(
            (0..3).map { DanmakuEntry("id$it", "message", 1_000L) },
            lanes = 1,
        ).size)
    }

    @Test fun movementUsesMeasuredTextWidthAndPlaybackTime() {
        assertEquals(360f, DanmakuLayout.xPosition(360f, 120f, 0f), 0.001f)
        assertEquals(120f, DanmakuLayout.xPosition(360f, 120f, 0.5f), 0.001f)
        assertEquals(-120f, DanmakuLayout.xPosition(360f, 120f, 1f), 0.001f)
        assertEquals(0f, DanmakuLayout.progress(1_000L, 2_000L), 0.001f)
        assertEquals(0.5f, DanmakuLayout.progress(5_500L, 2_000L), 0.001f)
        assertEquals(1f, DanmakuLayout.progress(9_100L, 2_000L), 0.001f)
    }

    @Test fun orderIndependentAndPausedPositionStable() {
        val items = listOf(
            DanmakuEntry("b", "two", 100L), DanmakuEntry("a", "one", 100L),
        )
        val sorted = DanmakuLayout.arrange(items, lanes = 2)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
        assertEquals(sorted, DanmakuLayout.arrange(items.reversed(), lanes = 2))
        assertTrue(DanmakuLayout.progress(100L, 100L) == 0f)
    }
}
