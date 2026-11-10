package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesReturnPolicyTest {
    private val series = Drama("101", "上次的全集")
    private val reel = Drama("202", "当前刷剧作品")
    private val bookmark = Watched(series, 7, 123L)
    private val state = AppState(
        feedMode = true,
        navigation = PlaybackNavigation().toFeed(),
        detail = DramaDetail(reel, listOf(Episode(1, "201"))),
        feedItems = listOf(reel, series),
        feedIndex = 0,
        returnSeries = bookmark,
    )

    @Test fun shortcutOnlyAppearsInFeedWithPreviousFullSeries() {
        assertTrue(SeriesReturnPolicy.showShortcut(state))
        assertTrue(SeriesReturnPolicy.homeTapReturnsToSeries(state))
        assertFalse(SeriesReturnPolicy.homeTapReturnsToSeries(
            state.copy(miniPlayerVisible = true)))
        assertFalse(SeriesReturnPolicy.homeTapReturnsToSeries(
            state.copy(feedMode = false)))
        assertFalse(SeriesReturnPolicy.showShortcut(state.copy(returnSeries = null)))
        assertFalse(SeriesReturnPolicy.showShortcut(state.copy(feedMode = false)))
        assertFalse(SeriesReturnPolicy.showShortcut(state.copy(
            navigation = state.navigation.toSeries())))
    }

    @Test fun matchingDramaUsesSameMediaPlayerWithoutReopeningModel() {
        assertFalse(SeriesReturnPolicy.currentDramaMatches(state))
        assertTrue(SeriesReturnPolicy.currentDramaMatches(state.copy(
            detail = DramaDetail(series, listOf(Episode(7, "701"))))))
    }

    @Test fun originalFeedPositionIsRecoveredAfterReturningToOtherSeries() {
        val remembered = state.copy(
            returnFeedItem = Watched(reel, 2, 432L),
        )
        assertEquals(0, SeriesReturnPolicy.originalFeedIndex(remembered))
        assertEquals(null, SeriesReturnPolicy.originalFeedIndex(state))
        assertEquals(null, SeriesReturnPolicy.originalFeedIndex(remembered.copy(
            feedItems = listOf(series))))
    }
}
