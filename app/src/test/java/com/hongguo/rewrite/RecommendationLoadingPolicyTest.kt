package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationLoadingPolicyTest {
    @Test fun manuallyLoadedRecommendationBatchHasThreeItems() {
        assertEquals(3, RecommendationLoadingPolicy.BATCH_SIZE)
    }

    @Test fun boundedFeedRequestsRemainEnabled() {
        assertTrue(RecommendationLoadingPolicy.MAX_OFFICIAL_REQUESTS > 0)
        assertTrue(RecommendationLoadingPolicy.MAX_TOTAL_REQUESTS >=
            RecommendationLoadingPolicy.MAX_OFFICIAL_REQUESTS)
    }
}
