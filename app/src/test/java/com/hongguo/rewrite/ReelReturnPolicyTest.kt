package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelReturnPolicyTest {
    private val dramas = listOf(
        Drama("1", "第一部"), Drama("2", "第二部"), Drama("3", "第三部"),
    )
    private val second = DramaDetail(dramas[1], listOf(
        Episode(1, "21"), Episode(2, "22"), Episode(3, "23"),
    ))

    private val insideSeries = AppState(
        detail = second,
        episodeIndex = 2,
        feedMode = false,
        returnToFeed = true,
        feedItems = dramas,
        feedIndex = 1,
        playbackUrl = "native://episode/23",
    )

    @Test fun sameDramaReturnsToItsOriginalSwipePositionEvenAfterChangingEpisodes() {
        assertTrue(ReelReturnPolicy.canRestore(insideSeries))
        // Back to feed must continue on this same drama, not jump to item zero.
        assertTrue(ReelReturnPolicy.canRestore(insideSeries.copy(episodeIndex = 0)))
    }

    @Test fun unrelatedCatalogOrSearchPlaybackNeverInheritsOldFeed() {
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(returnToFeed = false)))
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(
            detail = DramaDetail(Drama("4", "其他作品"), listOf(Episode(1, "41"))))))
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(feedIndex = 99)))
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(feedItems = emptyList())))
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(detail = null)))
    }

    @Test fun alreadyInFeedOrOfflineVideoCannotUseSeriesReturn() {
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(feedMode = true)))
        assertFalse(ReelReturnPolicy.canRestore(insideSeries.copy(
            offlinePlaybackUri = "content://offline/video")))
    }
}
