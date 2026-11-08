package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNavigationTest {
    @Test fun relatedWorkFromDetailsOpensSeriesThenReturnsToFeed() {
        val fromFeed = PlaybackNavigation().toFeed()
        val playing = fromFeed.toSeries()
        val details = playing.toDetails()
        val related = details.toSeries()
        assertEquals(PlaybackDestination.DETAILS, details.destination)
        assertEquals(PlaybackOrigin.SWIPE_FEED, related.origin)
        assertEquals(PlaybackDestination.SERIES, related.destination)
        assertTrue(related.returnsToFeed)
        assertEquals(PlaybackDestination.FEED, related.toFeed().destination)
    }

    @Test fun openingDetailsDirectlyFromFeedReturnsToFeedWithoutChangingPlayer() {
        val feed = PlaybackNavigation().toFeed()
        val details = feed.toDetails()
        assertEquals(PlaybackDestination.FEED, details.detailsParent)
        assertEquals(PlaybackDestination.DETAILS, details.destination)
        assertEquals(PlaybackDestination.FEED, details.fromDetails().destination)
        assertEquals(PlaybackOrigin.SWIPE_FEED, details.fromDetails().origin)
    }

    @Test fun detailsFromExplicitHomeReturnToHomeWithMini() {
        val home = PlaybackNavigation().toFeed().toCatalog()
        val detailPage = home.toDetails()
        assertTrue(detailPage.isExplicitCatalog)
        assertEquals(PlaybackDestination.CATALOG, detailPage.detailsParent)
        assertEquals(PlaybackDestination.CATALOG, detailPage.fromDetails().destination)
        assertFalse(detailPage.fromDetails().returnsToFeed)
    }

    @Test fun catalogOpenNeverReturnsToFeed() {
        val catalog = PlaybackNavigation().toSeries(PlaybackOrigin.CATALOG)
        assertFalse(catalog.returnsToFeed)
        assertFalse(catalog.toDetails().returnsToFeed)
        assertEquals(PlaybackDestination.CATALOG, catalog.toCatalog().destination)
    }

    @Test fun openingHomeIsExplicitAndDoesNotChangeOrigin() {
        val fromFeed = PlaybackNavigation().toFeed().toCatalog()
        assertEquals(PlaybackOrigin.SWIPE_FEED, fromFeed.origin)
        assertEquals(PlaybackDestination.FEED,
            fromFeed.restorePlayer(feedMode = true).destination)
        assertEquals(PlaybackDestination.SERIES,
            fromFeed.restorePlayer(feedMode = false).destination)
    }
}
