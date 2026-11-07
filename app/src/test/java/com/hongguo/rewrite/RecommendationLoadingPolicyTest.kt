package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationLoadingPolicyTest {
    private fun nearEnd(
        loaded: Int = 12, scrolled: Boolean = true,
        lastVisible: Int = 13, count: Int = 14,
        loading: Boolean = false, moreLoading: Boolean = false,
        hasError: Boolean = false,
    ) = RecommendationLoadingPolicy.nearEnd(
        native = true, section = Section.RECOMMEND,
        loading = loading, moreLoading = moreLoading, hasMore = true,
        hasError = hasError, itemCount = loaded, hasScrolled = scrolled,
        lastVisible = lastVisible, layoutItems = count,
    )

    @Test fun batchesHaveTwelveDramaTargets() {
        assertTrue(RecommendationLoadingPolicy.BATCH_SIZE == 12)
    }

    @Test fun onlyScrollToLastRowLoadsNext() {
        assertTrue(nearEnd())
        assertFalse(nearEnd(scrolled = false))
        assertFalse(nearEnd(lastVisible = 8))
        assertFalse(nearEnd(loaded = 3))
        assertFalse(nearEnd(loading = true))
        assertFalse(nearEnd(moreLoading = true))
        assertFalse(nearEnd(hasError = true))
    }
}
