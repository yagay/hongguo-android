package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationLoadingPolicyTest {
    @Test fun initialThreeCardsMustKeepLoadingWithinBound() {
        assertTrue(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, false, 3, true, 1, 3))
        assertTrue(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, false, 9, true, 4, 3))
        assertFalse(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, false, 12, true, 4, 3))
        assertFalse(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, false, 3, true, 8, 3))
        assertFalse(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, false, 3, true, 3, 0))
        assertFalse(RecommendationLoadingPolicy.shouldPrefill(
            true, Section.RECOMMEND, true, 3, true, 3, 3))
        assertFalse(RecommendationLoadingPolicy.shouldPrefill(
            false, Section.RECOMMEND, false, 3, true, 3, 3))
    }

    @Test fun autoScrollLoadsOnlyWhenReadyAndNearEnd() {
        assertTrue(RecommendationLoadingPolicy.nearEnd(
            true, Section.RECOMMEND, false, false, true, false, 3, 4, 5))
        assertFalse(RecommendationLoadingPolicy.nearEnd(
            true, Section.RECOMMEND, true, false, true, false, 3, 4, 5))
        assertFalse(RecommendationLoadingPolicy.nearEnd(
            true, Section.RECOMMEND, false, true, true, false, 3, 4, 5))
        assertFalse(RecommendationLoadingPolicy.nearEnd(
            true, Section.RECOMMEND, false, false, true, true, 3, 4, 5))
        assertFalse(RecommendationLoadingPolicy.nearEnd(
            true, Section.RECOMMEND, false, false, true, false, 24, 4, 30))
    }
}
