package com.yagay.hongguo

/**
 * App pages and playback presentation are orthogonal.
 *
 * The primary tab only chooses Home, Theater or My content. The playback
 * route/mini flag determine whether a Media3 surface is full-sized, a mini
 * surface accompanies a catalog page, or no video surface is displayed.
 *
 * Keeping this policy pure avoids two LaunchedEffects fighting over a tab
 * when a series was opened from history/theater.
 */
internal object AppShellNavigationPolicy {
    enum class VideoLayer { FULL, BEHIND_DETAILS, MINI, NONE }

    fun videoLayer(state: AppState): VideoLayer {
        if (state.detail == null) return VideoLayer.NONE
        val destination = state.navigation.destination
        if (destination == PlaybackDestination.DETAILS &&
            state.navigation.detailsParent in setOf(
                PlaybackDestination.FEED, PlaybackDestination.SERIES)
        ) return VideoLayer.BEHIND_DETAILS
        if (destination in setOf(PlaybackDestination.FEED, PlaybackDestination.SERIES) &&
            !state.miniPlayerVisible
        ) return VideoLayer.FULL
        if (MiniPlayerPresentation.show(state)) return VideoLayer.MINI
        return VideoLayer.NONE
    }

    fun shouldMinimizeForTab(state: AppState, tab: String): Boolean =
        tab != "home" && videoLayer(state) == VideoLayer.FULL &&
            !state.detailPageVisible

    /** Home is the swipe-feed destination, not the series player toggle.
     * The mini player's own expand button remains the explicit restore action. */
    fun shouldRestoreForHome(state: AppState): Boolean =
        state.feedMode && state.detail != null &&
            (state.miniPlayerVisible ||
                videoLayer(state) == VideoLayer.FULL)

    fun homeTabNeedsFeedNavigation(state: AppState): Boolean =
        !state.feedMode || state.miniPlayerVisible ||
            state.navigation.destination != PlaybackDestination.FEED

    fun shouldSelectHomeForNewSeries(state: AppState): Boolean =
        state.navigation.destination == PlaybackDestination.SERIES &&
            videoLayer(state) == VideoLayer.FULL

    fun shouldShowLoading(state: AppState, tab: String): Boolean =
        tab == "home" && !state.miniPlayerVisible &&
            state.navigation.destination in setOf(
                PlaybackDestination.FEED, PlaybackDestination.SERIES) &&
            videoLayer(state) == VideoLayer.NONE
}
