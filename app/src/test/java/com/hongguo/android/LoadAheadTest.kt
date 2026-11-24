package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadAheadTest {
    private val ready = FeedState(items = listOf(DramaCard("1", "剧", "", 1, "")), hasMore = true, loaded = true)

    @Test fun loadsOnlyWithinTheReadAheadDistance() {
        assertTrue(shouldLoadAhead(ready, LoadAheadItems))
        assertTrue(shouldLoadAhead(ready, 0))
        assertFalse(shouldLoadAhead(ready, LoadAheadItems + 1))
    }

    @Test fun preloadsOnlyTheCoversBelowTheVisiblePosition() {
        val items = (0 until 20).map { DramaCard("$it", "剧", if (it == 12) "" else "c$it", 1, "") }
        assertEquals((10 until 19).map { "c$it" }.filter { it != "c12" }, upcomingCovers(items, 10))
        assertTrue(upcomingCovers(items, 0).isEmpty())
        assertEquals("c0", upcomingCovers(items, 500).first())
    }

    @Test fun doesNotRequestWhileBusyFinishedEmptyOrFailed() {
        assertFalse(shouldLoadAhead(ready.copy(loadingMore = true), 0))
        assertFalse(shouldLoadAhead(ready.copy(loading = true), 0))
        assertFalse(shouldLoadAhead(ready.copy(hasMore = false), 0))
        assertFalse(shouldLoadAhead(ready.copy(items = emptyList()), 0))
        assertFalse("a failed page waits for the retry button", shouldLoadAhead(ready.copy(error = "失败"), 0))
    }
    @Test fun recentCoverRequestsAreDeduplicatedAndBounded() {
        val tracker = RecentCoverRequests(limit = 3)
        assertEquals(listOf("a", "b"), tracker.select(listOf("a", "", "b", "a")))
        assertEquals(listOf("c", "d"), tracker.select(listOf("b", "c", "d")))
        assertEquals(3, tracker.size)
        assertEquals(emptyList<String>(), tracker.select(listOf("b", "c", "d")))
        // The oldest URL may be requested again; the image loader can reuse its disk cache.
        assertEquals(listOf("a"), tracker.select(listOf("a")))
        assertEquals(3, tracker.size)
    }

    @Test fun recentCoverRequestsRejectInvalidLimit() {
        try {
            RecentCoverRequests(limit = 0)
            org.junit.Assert.fail("A non-positive tracking bound must be rejected")
        } catch (_: IllegalArgumentException) {
            // The limit is an invariant, not a memory growth hint.
        }
    }

}
