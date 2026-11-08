package com.hongguo.rewrite

/**
 * Only a series explicitly entered from the swipe feed can return there.
 * A new series opened through search or the catalog must never inherit
 * an old feed's navigation stack.
 */
internal object ReelReturnPolicy {
    fun canRestore(state: AppState): Boolean =
        state.returnToFeed && !state.feedMode &&
            state.offlinePlaybackUri == null &&
            state.detail != null &&
            state.feedItems.getOrNull(state.feedIndex)?.id == state.detail.drama.id
}
