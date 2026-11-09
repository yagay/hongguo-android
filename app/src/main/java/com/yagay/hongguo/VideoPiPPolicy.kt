package com.yagay.hongguo

/**
 * PiP is only a presentation mode for the existing service-owned video.
 * Auto-enter is reserved for actual, actively playing Media3 content.
 */
internal object VideoPiPPolicy {
    const val PORTRAIT_WIDTH = 9
    const val PORTRAIT_HEIGHT = 16

    /** Only the auto PiP preference gates background/leave-app auto-enter. */
    fun isAutoPipAllowed(
        autoPipEnabled: Boolean,
        systemAccess: VideoPiPAccess.Status,
    ): Boolean = autoPipEnabled && isManualEntryPermitted(systemAccess)

    /**
     * Manual PiP is independent of auto-enter. Its toolbar button is
     * controlled separately by the "show manual PiP button" preference.
     */
    fun isManualEntryPermitted(systemAccess: VideoPiPAccess.Status): Boolean =
        systemAccess != VideoPiPAccess.Status.BLOCKED &&
            systemAccess != VideoPiPAccess.Status.UNSUPPORTED

    fun showManualButton(manualButtonVisible: Boolean): Boolean =
        manualButtonVisible

    /** The live Media3 player is the source of truth for auto PiP. */
    fun canAutoEnterFromActivePlayer(
        isPlayerVisible: Boolean,
        actualMediaId: String?,
        isPlaying: Boolean,
    ): Boolean = isPlayerVisible && isPlaying && !actualMediaId.isNullOrBlank()

    /**
     * Shared eligibility for leaving via Home, Recents, a notification or
     * another app. Android may emit onPause without onUserLeaveHint.
     * Never enter while a PiP request is in flight or a settings activity
     * is being launched on purpose.
     */
    fun canEnterWhenLeavingApp(
        autoPipEnabled: Boolean,
        systemAccess: VideoPiPAccess.Status,
        isPlayerVisible: Boolean,
        actualMediaId: String?,
        isPlaying: Boolean,
        alreadyInPip: Boolean,
        entryRequested: Boolean,
        openingSettings: Boolean,
        finishingOrRecreating: Boolean,
    ): Boolean = isAutoPipAllowed(autoPipEnabled, systemAccess) &&
        canAutoEnterFromActivePlayer(isPlayerVisible, actualMediaId, isPlaying) &&
        !alreadyInPip && !entryRequested && !openingSettings &&
        !finishingOrRecreating

    fun canAutoEnter(
        hasDetail: Boolean,
        preparingDetail: Boolean,
        hasPlaybackSource: Boolean,
        expectedVid: String?,
        actualMediaId: String?,
        isPlaying: Boolean,
    ): Boolean = hasDetail && !preparingDetail && hasPlaybackSource &&
        !expectedVid.isNullOrBlank() && expectedVid == actualMediaId && isPlaying
}
