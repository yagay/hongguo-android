package com.hongguo.rewrite

/** Keep mini-player placement deterministic across all top-level app pages. */
internal object MiniPlayerPresentation {
    fun show(state: AppState): Boolean =
        state.miniPlayerVisible && state.detail != null && !state.preparingDetail

    /** Put the mini row above the catalog NavigationBar whenever one exists. */
    fun inCatalog(state: AppState, searchLandingOpen: Boolean): Boolean =
        show(state) &&
            state.modal != Modal.SETTINGS &&
            state.modal != Modal.ACCOUNT &&
            !state.detailPageVisible &&
            !searchLandingOpen
}
