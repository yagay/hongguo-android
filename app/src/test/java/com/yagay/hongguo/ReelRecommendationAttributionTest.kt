package com.yagay.hongguo

import com.hongguotv.core.PersonalizationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReelRecommendationAttributionTest {
    @Test fun mixedDynamicAndCategoryKeepDistinctStatuses() {
        val dynamic = ReelItemAttribution(ReelRecommendationOrigin.ACCOUNT_DYNAMIC,
            PersonalizationEvidence.SERVER_ENABLED)
        val category = ReelItemAttribution(ReelRecommendationOrigin.ACCOUNT_CATEGORY)
        val sources = mapOf("1" to dynamic, "2" to category)
        assertEquals(ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED,
            ReelRecommendationAttributionPolicy.statusFor("1", sources,
                ReelRecommendationStatus.ACCOUNT_RECEIVED))
        assertEquals(ReelRecommendationStatus.ACCOUNT_CATEGORY_FALLBACK,
            ReelRecommendationAttributionPolicy.statusFor("2", sources,
                ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED))
    }

    @Test fun repeatedItemsRetainOriginalSource() {
        val old = listOf(Drama("1", "previous"))
        val mixed = old + Drama("2", "new")
        val account = ReelItemAttribution(ReelRecommendationOrigin.ACCOUNT_DYNAMIC)
        val anonymous = ReelItemAttribution(ReelRecommendationOrigin.ANONYMOUS_KAIXINGUO)
        val sources = ReelRecommendationAttributionPolicy.merge(
            mapOf("1" to anonymous), old, mixed,
            mapOf("1" to account, "2" to account))
        assertEquals(anonymous, sources["1"])
        assertEquals(account, sources["2"])
        assertFalse("3" in sources)
    }

    @Test fun missingAttributionDoesNotInheritLastRequestStatus() {
        assertEquals(ReelRecommendationStatus.NOT_REQUESTED,
            ReelRecommendationAttributionPolicy.statusFor(
                "unknown", emptyMap(), ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED))
        assertEquals(ReelRecommendationStatus.ACCOUNT_LOADING,
            ReelRecommendationAttributionPolicy.statusFor(
                null, emptyMap(), ReelRecommendationStatus.ACCOUNT_LOADING))
    }

    @Test fun anonymousSourcesAreDistinct() {
        assertEquals(ReelRecommendationStatus.ANONYMOUS_RECEIVED,
            ReelRecommendationAttributionPolicy.statusFor("1",
                mapOf("1" to ReelItemAttribution(ReelRecommendationOrigin.ANONYMOUS_KAIXINGUO)),
                ReelRecommendationStatus.NOT_REQUESTED))
        assertEquals(ReelRecommendationStatus.ANONYMOUS_OFFICIAL_CATEGORY,
            ReelRecommendationAttributionPolicy.statusFor("1",
                mapOf("1" to ReelItemAttribution(ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_CATEGORY)),
                ReelRecommendationStatus.NOT_REQUESTED))
    }
}
