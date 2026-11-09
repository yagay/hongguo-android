package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class SeamlessPrefetchPolicyTest {
    private fun choice(
        auto: Boolean = true, feed: Boolean = false,
        nextEpisode: Boolean = false, queuedDrama: Boolean = false,
        enabled: Boolean = true, offline: Boolean = false,
    ) = SeamlessPrefetchPolicy.choose(
        enabled = enabled, autoPlayNext = auto, inSwipeFeed = feed,
        offline = offline, hasNextEpisode = nextEpisode,
        hasQueuedNextDrama = queuedDrama,
    )

    @Test fun episodeHasPriorityOverUpcomingDrama() {
        assertEquals(SeamlessPrefetchPolicy.Next.NEXT_EPISODE,
            choice(nextEpisode = true, queuedDrama = true, feed = true))
        assertEquals(SeamlessPrefetchPolicy.Next.NEXT_EPISODE,
            choice(nextEpisode = true, queuedDrama = false))
    }

    @Test fun finaleWarmsQueuedDramaOrFetchesOfficialRecommendations() {
        assertEquals(SeamlessPrefetchPolicy.Next.NEXT_DRAMA,
            choice(queuedDrama = true))
        assertEquals(SeamlessPrefetchPolicy.Next.FETCH_RECOMMENDATIONS,
            choice(queuedDrama = false))
        assertEquals(SeamlessPrefetchPolicy.Next.NEXT_DRAMA,
            choice(auto = false, feed = true, queuedDrama = true))
    }

    @Test fun explicitAutoplayAndPreloadOffStayOff() {
        assertEquals(SeamlessPrefetchPolicy.Next.NONE,
            choice(enabled = false, nextEpisode = true))
        assertEquals(SeamlessPrefetchPolicy.Next.NONE,
            choice(offline = true, queuedDrama = true))
        assertEquals(SeamlessPrefetchPolicy.Next.NONE,
            choice(auto = false, feed = false, queuedDrama = true))
    }
}
