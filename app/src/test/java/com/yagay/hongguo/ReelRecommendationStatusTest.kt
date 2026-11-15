package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelRecommendationStatusTest {
    @Test fun loginToggleAloneNeverMeansReceived() {
        assertFalse(ReelRecommendationStatus.NOT_REQUESTED.received)
        assertFalse(ReelRecommendationStatusPolicy.loading(true).received)
        assertFalse(ReelRecommendationStatusPolicy.received(true, newItems = false).received)
    }

    @Test fun onlySuccessfulAccountResultsConfirmAccountFeedDelivery() {
        val account = ReelRecommendationStatusPolicy.received(true, newItems = true)
        assertEquals(ReelRecommendationStatus.ACCOUNT_RECEIVED, account)
        assertTrue(account.accountRequest)
        assertTrue(account.received)
        // Confirmation of account requests and proof of personalized ranking
        // are separate concepts; don't call the signed feed "unverified" in
        // its compact label while keeping the caveat in the explanation.
        assertEquals("账号官方推荐已接入", account.shortLabel)
        assertTrue(account.explanation.contains("服务端"))
        val anonymous = ReelRecommendationStatusPolicy.received(false, newItems = true)
        assertEquals(ReelRecommendationStatus.ANONYMOUS_RECEIVED, anonymous)
        assertFalse(anonymous.accountRequest)
    }

    @Test fun serverFlagAndCategoryFallbackHaveDistinctStatuses() {
        assertEquals(ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED,
            ReelRecommendationStatusPolicy.received(true, true,
                com.hongguotv.core.PersonalizationEvidence.SERVER_ENABLED))
        assertEquals(ReelRecommendationStatus.ACCOUNT_SERVER_DISABLED,
            ReelRecommendationStatusPolicy.received(true, true,
                com.hongguotv.core.PersonalizationEvidence.SERVER_DISABLED))
        assertEquals(ReelRecommendationStatus.ACCOUNT_CATEGORY_FALLBACK,
            ReelRecommendationStatusPolicy.received(true, true,
                com.hongguotv.core.PersonalizationEvidence.SERVER_ENABLED,
                categoryFallback = true))
        assertEquals(ReelRecommendationStatus.ACCOUNT_RECEIVED,
            ReelRecommendationStatusPolicy.received(true, true))
    }

    @Test fun accountFailureDoesNotClaimPersonalization() {
        assertEquals(ReelRecommendationStatus.ACCOUNT_FAILED,
            ReelRecommendationStatusPolicy.failed(true))
        assertFalse(ReelRecommendationStatusPolicy.failed(true).received)
        assertEquals(ReelRecommendationStatus.ANONYMOUS_FAILED,
            ReelRecommendationStatusPolicy.failed(false))
    }
}
