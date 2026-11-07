package com.hongguo.rewrite

/** Official video feeds may provide just 1–3 cards per request. The homepage
 * is a catalog grid, so it must aggregate small batches and fetch next pages
 * when the user reaches the end. Bound auto-fill and stop if no new IDs arrive.
 */
internal object RecommendationLoadingPolicy {
    const val MIN_INITIAL_ITEMS = 12
    const val MAX_INITIAL_BATCHES = 8

    fun shouldPrefill(
        native: Boolean, section: Section, feedMode: Boolean,
        total: Int, hasMore: Boolean, fetchedBatches: Int, newItems: Int,
    ) = native && section == Section.RECOMMEND && !feedMode &&
        total < MIN_INITIAL_ITEMS && hasMore &&
        fetchedBatches < MAX_INITIAL_BATCHES && newItems > 0

    fun nearEnd(
        native: Boolean, section: Section, loading: Boolean,
        moreLoading: Boolean, hasMore: Boolean, hasError: Boolean,
        itemCount: Int, lastVisible: Int, layoutItems: Int,
    ) = native && section == Section.RECOMMEND &&
        !loading && !moreLoading && hasMore && !hasError &&
        itemCount > 0 && layoutItems > 0 && lastVisible >= layoutItems - 4
}
