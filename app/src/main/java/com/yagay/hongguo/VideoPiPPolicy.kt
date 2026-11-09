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
