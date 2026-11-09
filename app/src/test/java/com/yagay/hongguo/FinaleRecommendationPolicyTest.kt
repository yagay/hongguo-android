package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinaleRecommendationPolicyTest {
    @Test fun completedDramaDoesNotStopOnFirstEmptyBatch() {
        assertTrue(FinaleRecommendationPolicy.canRetry(0))
        assertTrue(FinaleRecommendationPolicy.canRetry(1))
        assertFalse(FinaleRecommendationPolicy.canRetry(2))
    }

    @Test fun retryCanUsePreviouslyWatchedRecommendationButNotCurrentDrama() {
        assertTrue(FinaleRecommendationPolicy.relaxRecentExclusions(1))
        val current = listOf(Drama("99", "刚播完"))
        val result = OfficialReelsPolicy.append(current,
            listOf(Drama("99", "刚播完"), Drama("88", "之前看过")),
            emptySet(),
        )
        assertTrue(result.any { it.id == "88" })
        assertTrue(result.count { it.id == "99" } == 1)
    }
}
