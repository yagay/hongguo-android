package com.hongguo.rewrite

/** Recommendation catalog pages hold up to 12 distinct dramas. */
internal object RecommendationLoadingPolicy {
    const val BATCH_SIZE = 12
    const val MAX_OFFICIAL_REQUESTS = 8
    const val MAX_TOTAL_REQUESTS = 12

    /** Never load a second page merely because the first page is visible. */
    fun nearEnd(
        native: Boolean, section: Section, loading: Boolean,
        moreLoading: Boolean, hasMore: Boolean, hasError: Boolean,
        itemCount: Int, hasScrolled: Boolean, lastVisible: Int, layoutItems: Int,
    ) = native && section == Section.RECOMMEND &&
        !loading && !moreLoading && hasMore && !hasError &&
        itemCount >= BATCH_SIZE && hasScrolled && layoutItems > 0 &&
        lastVisible >= layoutItems - 2
}
