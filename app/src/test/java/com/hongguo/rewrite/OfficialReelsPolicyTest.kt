package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialReelsPolicyTest {
    private fun title(id: String) = Drama(id, "Drama $id")

    @Test fun officialPagesExtendPastTheThreeItemManualHomeBatch() {
        val seed = listOf(title("1"))
        val first = OfficialReelsPolicy.append(seed,
            listOf(title("2"), title("3"), title("4"), title("5")),
            emptySet())
        val next = OfficialReelsPolicy.append(first,
            listOf(title("4"), title("5"), title("6"), title("7")),
            emptySet())
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), next.map { it.id })
    }

    @Test fun hiddenAndRepeatedItemsAreNotNavigated() {
        val hidden = setOf("3")
        val q = OfficialReelsPolicy.append(listOf(title("1")),
            listOf(title("2"), title("3"), title("2"), title("4")), hidden)
        assertEquals(listOf("1", "2", "4"), q.map { it.id })
        assertEquals(1, OfficialReelsPolicy.nextIndex(q, 0, 1, hidden))
        assertEquals(2, OfficialReelsPolicy.nextIndex(q, 1, 1, hidden))
        assertEquals(1, OfficialReelsPolicy.nextIndex(q, 2, -1, hidden))
        assertNull(OfficialReelsPolicy.nextIndex(q, 2, 1, hidden))
    }

    @Test fun homeRecommendationCardsNeverLeakIntoOfficialSwipeQueue() {
        val homeIds = setOf("10", "11", "12")
        val current = emptyList<Drama>()
        val page = listOf(title("10"), title("11"), title("30"), title("31"))
        val queue = OfficialReelsPolicy.append(current, page, homeIds)
        assertEquals(listOf("30", "31"), queue.map { it.id })
    }

    @Test fun readAheadCountsOnlyVisibleUpcomingReels() {
        val feed = listOf(title("1"), title("2"), title("3"),
            title("4"), title("5"))
        assertEquals(true, OfficialReelsPolicy.needsMore(
            feed, 1, setOf("4"), hasMore = true, loading = false))
        assertEquals(false, OfficialReelsPolicy.needsMore(
            feed, 0, emptySet(), hasMore = true, loading = false))
        assertEquals(false, OfficialReelsPolicy.needsMore(
            feed, 4, emptySet(), hasMore = false, loading = false))
        assertEquals(false, OfficialReelsPolicy.needsMore(
            feed, 4, emptySet(), hasMore = true, loading = true))
    }

    @Test fun autoplayAdvancesEpisodesBeforeSwitchingDrama() {
        assertEquals(ReelAutoplayPolicy.Action.NEXT_EPISODE,
            ReelAutoplayPolicy.onEnded(0, 5, true, true))
        assertEquals(ReelAutoplayPolicy.Action.NEXT_EPISODE,
            ReelAutoplayPolicy.onEnded(3, 5, true, true))
        assertEquals(ReelAutoplayPolicy.Action.NEXT_DRAMA,
            ReelAutoplayPolicy.onEnded(4, 5, true, true))
        assertEquals(ReelAutoplayPolicy.Action.STOP,
            ReelAutoplayPolicy.onEnded(4, 5, true, false))
        assertEquals(ReelAutoplayPolicy.Action.STOP,
            ReelAutoplayPolicy.onEnded(0, 5, false, true))
        assertEquals(ReelAutoplayPolicy.Action.STOP,
            ReelAutoplayPolicy.onEnded(0, 0, true, true))
    }

    @Test fun userCanSwipeBackAfterAutoplayWhileHiddenIdsAreSkipped() {
        val q = (1..7).map { title(it.toString()) }
        assertEquals(5, OfficialReelsPolicy.nextIndex(q, 3, 1, setOf("5")))
        assertEquals(2, OfficialReelsPolicy.nextIndex(q, 3, -1, setOf("3")))
        assertNull(OfficialReelsPolicy.nextIndex(q, 6, 1, emptySet()))
        assertNull(OfficialReelsPolicy.nextIndex(q, 0, -1, emptySet()))
        assertNull(OfficialReelsPolicy.nextIndex(q, 0, 0, emptySet()))
    }

    @Test fun duplicateOnlyPageDoesNotExtendQueue() {
        val current = listOf(title("1"), title("2"))
        assertEquals(current, OfficialReelsPolicy.append(
            current, listOf(title("1"), title("2"), title("2")), emptySet()))
    }

    @Test fun homeListAndFeedQueueRemainSeparate() {
        val home = listOf(title("10"), title("11"), title("12"))
        val queue = OfficialReelsPolicy.append(listOf(home.first()),
            listOf(title("20"), title("21")), emptySet())
        assertEquals(3, home.size)
        assertEquals(listOf("10", "20", "21"), queue.map { it.id })
    }
}
