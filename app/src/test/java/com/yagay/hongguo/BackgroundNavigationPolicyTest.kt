package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundNavigationPolicyTest {
    @Test fun normalEpisodeArrowsMatchVerticalSwipes() {
        val first = BackgroundNavigationPolicy.availability(
            false, 0, 80, false, false)
        assertFalse(first.previous)
        assertTrue(first.next)
        val middle = BackgroundNavigationPolicy.availability(
            false, 38, 80, false, false)
        assertTrue(middle.previous)
        assertTrue(middle.next)
        val last = BackgroundNavigationPolicy.availability(
            false, 79, 80, false, false)
        assertTrue(last.previous)
        assertFalse(last.next)
    }

    @Test fun reelArrowsUseFeedNeighboursNotEpisodeList() {
        val feed = BackgroundNavigationPolicy.availability(
            true, 12, 80, true, false)
        assertTrue(feed.previous)
        assertFalse(feed.next)
        val pending = BackgroundNavigationPolicy.availability(
            true, 0, 1, false, true)
        assertFalse(pending.previous)
        assertTrue(pending.next)
    }

    @Test fun invalidOrEmptyEpisodeStateDoesNotAdvertiseSkipping() {
        for (index in listOf(-1, 0, 99)) {
            val empty = BackgroundNavigationPolicy.availability(
                false, index, 0, true, true)
            assertFalse(empty.previous)
            assertFalse(empty.next)
        }
        val outOfRange = BackgroundNavigationPolicy.availability(
            true, 42, 2, true, true)
        assertFalse(outOfRange.previous)
        assertFalse(outOfRange.next)
    }

    @Test fun notificationShowsFullSeriesAndCurrentEpisode() {
        assertEquals("我的房东是偶像歌手 · 第17集",
            BackgroundNavigationPolicy.title("我的房东是偶像歌手", 17))
        assertEquals("第17集 / 全80集",
            BackgroundNavigationPolicy.episodeLabel(17, 80))
    }
}
