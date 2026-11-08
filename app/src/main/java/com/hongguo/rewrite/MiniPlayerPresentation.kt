package com.hongguo.rewrite

/** Video surfaces are a presentation detail, never the navigation back stack. */
internal object MiniPlayerPresentation {
    fun show(state: AppState): Boolean =
        state.miniPlayerVisible && state.detail != null &&
            state.navigation.destination in setOf(
                PlaybackDestination.DETAILS, PlaybackDestination.CATALOG)

    /** The bottom catalog bar exists only after explicit Browse Home. */
    fun catalogNavigationVisible(state: AppState, searchLandingOpen: Boolean): Boolean =
        state.navigation.destination == PlaybackDestination.CATALOG &&
            state.modal !in setOf(Modal.SETTINGS, Modal.ACCOUNT) &&
            !searchLandingOpen

    fun inCatalog(state: AppState, searchLandingOpen: Boolean): Boolean =
        show(state) && state.navigation.destination == PlaybackDestination.CATALOG &&
            state.modal !in setOf(Modal.SETTINGS, Modal.ACCOUNT) &&
            !searchLandingOpen
}
