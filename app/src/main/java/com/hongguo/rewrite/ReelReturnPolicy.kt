package com.hongguo.rewrite

/**
 * A full-series player entered from the default swipe feed returns to its
 * queue only when the selected work still matches that queue's current item.
 * Surface/presentation flags are intentionally ignored.
 */
internal object ReelReturnPolicy {
    fun canRestore(state: AppState): Boolean =
        state.navigation.returnsToFeed &&
            !state.feedMode &&
            state.offlinePlaybackUri == null &&
            state.detail != null &&
            state.feedItems.getOrNull(state.feedIndex)?.id == state.detail.drama.id
}
