package com.yagay.hongguo

/** Manual recommendation loading: show three dramas per button click. */
internal object RecommendationLoadingPolicy {
    const val BATCH_SIZE = 3

    // Keep bounded requests if anonymous feed pages are sparse or unavailable.
    const val MAX_OFFICIAL_REQUESTS = 8
    const val MAX_TOTAL_REQUESTS = 12
}
