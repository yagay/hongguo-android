package com.hongguo.rewrite

/** Keep mini-player placement deterministic across all top-level app pages. */
internal object MiniPlayerPresentation {
    // The prior video's metadata remains available while the next series
    // loads; preparingDetail must NOT unmount the existing video surface.
    fun show(state: AppState): Boolean =
        state.miniPlayerVisible && state.detail != null

    /** The catalog's navigation bar is a sibling below the mini dock, never
     * a parent for the PlayerView. This prevents Surface destruction when
     * navigating between catalog, settings, search and drama details.
     */
    fun catalogNavigationVisible(state: AppState, searchLandingOpen: Boolean): Boolean =
        state.modal != Modal.SETTINGS &&
            state.modal != Modal.ACCOUNT &&
            !state.detailPageVisible &&
            !searchLandingOpen &&
            (show(state) || (state.detail == null && !state.preparingDetail &&
                !state.feedMode))

    /** Put the mini row above the catalog NavigationBar whenever one exists. */
    fun inCatalog(state: AppState, searchLandingOpen: Boolean): Boolean =
        show(state) &&
            state.modal != Modal.SETTINGS &&
            state.modal != Modal.ACCOUNT &&
            !state.detailPageVisible &&
            !searchLandingOpen
}
