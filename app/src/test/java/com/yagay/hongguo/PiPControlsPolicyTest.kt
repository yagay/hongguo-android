package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiPControlsPolicyTest {
    @Test fun enabledDirectionsReflectRealEpisodeAvailability() {
        val middle = BackgroundNavigationPolicy.availability(
            feedMode = false, selectedIndex = 5, episodeCount = 10,
            previousFeedExists = false, nextFeedAvailableOrPending = false,
        )
        assertTrue(middle.previous)
        assertTrue(middle.next)
        val start = BackgroundNavigationPolicy.availability(
            feedMode = false, selectedIndex = 0, episodeCount = 10,
            previousFeedExists = false, nextFeedAvailableOrPending = false,
        )
        assertFalse(start.previous)
        assertTrue(start.next)
    }

    @Test fun outsideFeedOnlyAdvertisesRealPreviousItem() {
        val first = BackgroundNavigationPolicy.availability(
            feedMode = true, selectedIndex = 0, episodeCount = 1,
            previousFeedExists = false, nextFeedAvailableOrPending = true,
        )
        assertFalse(first.previous)
        assertTrue(first.next)
    }
}
