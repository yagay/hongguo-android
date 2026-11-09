package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupPlaybackPolicyTest {
    @Test fun opensMostRecentConfirmedDramaAndItsEpisode() {
        val previous = Watched(Drama("101", "之前播放"), 2, 100L)
        val last = Watched(Drama("202", "上次正在播放"), 14, 500L)
        assertEquals(last, StartupPlaybackPolicy.lastConfirmed(listOf(previous, last)))
        assertEquals(14, StartupPlaybackPolicy.lastConfirmed(listOf(previous, last))?.episode)
    }
    @Test fun hiddenOrInvalidHistoryIsNotRestored() {
        val hidden = Watched(Drama("202", "隐藏"), 7, 500L)
        val available = Watched(Drama("101", "可用"), 3, 100L)
        assertEquals(available, StartupPlaybackPolicy.lastConfirmed(
            listOf(hidden, available), setOf("202")))
        assertNull(StartupPlaybackPolicy.lastConfirmed(
            listOf(Watched(Drama("invalid", "无效"), 1, 10L))))
    }
    @Test fun firstRunFallsBackToNormalFeed() {
        assertNull(StartupPlaybackPolicy.lastConfirmed(emptyList()))
    }
}
