package com.yagay.hongguo

/** Two-way feed/series navigation is independent of the active Media3 source.
 * An already active reel can switch UI modes without recreating the player;
 * after swiping to another drama only a saved position can be resumed. */
internal object SeriesReturnPolicy {
    fun showShortcut(state: AppState): Boolean =
        state.feedMode &&
            state.navigation.destination == PlaybackDestination.FEED &&
            state.returnSeries != null

    fun currentDramaMatches(state: AppState): Boolean =
        state.returnSeries?.drama?.id != null &&
            state.returnSeries.drama.id == state.detail?.drama?.id

    fun originalFeedIndex(state: AppState): Int? {
        val dramaId = state.returnFeedItem?.drama?.id ?: return null
        return state.feedItems.indexOfFirst { it.id == dramaId }.takeIf { it >= 0 }
    }
}
