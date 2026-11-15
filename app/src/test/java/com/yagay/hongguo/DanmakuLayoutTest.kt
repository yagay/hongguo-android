package com.yagay.hongguo

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

    @Test fun activeWindowExcludesExpiredAndFutureEntries() {
        val rows = listOf(
            DanmakuEntry("expired", "old", 500L),
            DanmakuEntry("boundary", "boundary", 1_000L),
            DanmakuEntry("live", "live", 1_001L),
            DanmakuEntry("now", "now", 8_000L),
            DanmakuEntry("future", "later", 9_000L),
        )
        val arranged = DanmakuLayout.arrange(rows, lanes = 5)
        assertEquals(listOf("live", "now"),
            DanmakuLayout.visibleAt(arranged, 8_000L, 7_000L, 5).map { it.id })
        assertEquals(emptyList<String>(),
            DanmakuLayout.visibleAt(arranged, 30_000L, 7_000L, 5).map { it.id })
    }

    @Test fun activeWindowMatchesReferenceDuringPlaybackAndSeeks() {
        val arranged = DanmakuLayout.arrange(
            (0..250).map { i -> DanmakuEntry("id$i", "弹幕$i", i * 1_750L) },
            lanes = 5,
        )
        val duration = DanmakuLayout.flightForSpeed(1)
        for (time in listOf(0L, 1L, 1_000L, 7_000L, 50_000L,
            88_000L, 200_000L, 438_000L, 500_000L, 2_000_000L)) {
            val expected = arranged.filter {
                it.startMs <= time && time < it.startMs + duration
            }.take(5)
            assertEquals(expected, DanmakuLayout.visibleAt(arranged, time, duration, 5))
        }
        assertEquals(emptyList<DanmakuLayout.Placed>(),
            DanmakuLayout.visibleAt(arranged, 1_000L, duration, 0))
    }

    @Test fun shortCommentsCanFollowInOneLaneBeforeFullFlightEnds() {
        val comments = listOf(
            DanmakuEntry("first", "short", 1_000L),
            DanmakuEntry("second", "short", 3_000L),
            DanmakuEntry("tooSoon", "short", 3_500L),
        )
        val placed = DanmakuLayout.arrangeCollisionAware(
            comments, lanes = 1, durationMs = 7_000L,
            viewportWidthPx = 400f, safeGapPx = 12f,
            measureTextPx = { 80f },
        )
        assertEquals(listOf("first", "second"), placed.map { it.id })
        assertEquals(listOf(0, 0), placed.map { it.lane })
        assertEquals(listOf("first", "second"),
            DanmakuLayout.visibleAt(placed, 4_000L, 7_000L, 10).map { it.id })
    }

    @Test fun longFastFollowerCannotCatchEarlierShortComment() {
        val comments = listOf(
            DanmakuEntry("short", "short", 0L),
            DanmakuEntry("long", "much longer text", 2_000L),
        )
        val measure: (String) -> Float = {
            if (it == "short") 80f else 600f
        }
        val singleLane = DanmakuLayout.arrangeCollisionAware(
            comments, 1, 7_000L, 400f, 12f, measure,
        )
        assertEquals(listOf("short"), singleLane.map { it.id })
        val twoLanes = DanmakuLayout.arrangeCollisionAware(
            comments, 2, 7_000L, 400f, 12f, measure,
        )
        assertEquals(listOf(0, 1), twoLanes.map { it.lane })
    }

    @Test fun collisionCheckRemainsSafeThroughEntireOverlap() {
        val comments = (0..90).map { index ->
            DanmakuEntry("id$index", "msg$index", index * 230L)
        }
        val placed = DanmakuLayout.arrangeCollisionAware(
            comments, 3, 7_000L, 412f, 14f,
            measureTextPx = {
                val id = it.removePrefix("msg").toInt()
                50f + (id % 7) * 46f
            },
        )
        val reverse = DanmakuLayout.arrangeCollisionAware(
            comments.reversed(), 3, 7_000L, 412f, 14f,
            measureTextPx = {
                val id = it.removePrefix("msg").toInt()
                50f + (id % 7) * 46f
            },
        )
        assertEquals(placed, reverse)
        for (lane in 0..2) {
            val inLane = placed.filter { it.lane == lane }
            for ((previous, next) in inLane.zipWithNext()) {
                val start = next.startMs
                val end = minOf(previous.startMs + 7_000L, next.startMs + 7_000L)
                if (start >= end) continue
                for (time in start..end step 250L) {
                    val prevX = DanmakuLayout.xPosition(412f, previous.widthPx,
                        DanmakuLayout.progress(time, previous.startMs, 7_000L))
                    val nextX = DanmakuLayout.xPosition(412f, next.widthPx,
                        DanmakuLayout.progress(time, next.startMs, 7_000L))
                    assertTrue("lane $lane at $time had overlapping comments",
                        nextX - (prevX + previous.widthPx) >= 13.5f)
                }
            }
        }
    }

    @Test fun collisionSchedulerChangesWithScreenWidthAndRemainsSeekSafe() {
        val comments = listOf(
            DanmakuEntry("a", "a", 0L), DanmakuEntry("b", "b", 1_500L)
        )
        val wide = DanmakuLayout.arrangeCollisionAware(
            comments, 1, 7_000L, 600f, 10f, measureTextPx = { 65f })
        val narrow = DanmakuLayout.arrangeCollisionAware(
            comments, 1, 7_000L, 180f, 10f, measureTextPx = { 65f })
        assertEquals(2, wide.size)
        assertEquals(1, narrow.size)
        assertEquals(listOf("a", "b"),
            DanmakuLayout.visibleAt(wide, 2_000L, 7_000L, 10).map { it.id })
        assertEquals(listOf("a"),
            DanmakuLayout.visibleAt(wide, 800L, 7_000L, 10).map { it.id })
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
