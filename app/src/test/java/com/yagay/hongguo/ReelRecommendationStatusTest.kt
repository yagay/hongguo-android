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
        val anonymous = ReelRecommendationStatusPolicy.received(false, newItems = true)
        assertEquals(ReelRecommendationStatus.ANONYMOUS_RECEIVED, anonymous)
        assertFalse(anonymous.accountRequest)
    }

    @Test fun accountFailureDoesNotClaimPersonalization() {
        assertEquals(ReelRecommendationStatus.ACCOUNT_FAILED,
            ReelRecommendationStatusPolicy.failed(true))
        assertFalse(ReelRecommendationStatusPolicy.failed(true).received)
        assertEquals(ReelRecommendationStatus.ANONYMOUS_FAILED,
            ReelRecommendationStatusPolicy.failed(false))
    }
}
