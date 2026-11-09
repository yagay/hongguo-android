package com.yagay.hongguo

/**
 * PiP is only a presentation mode for the existing service-owned video.
 * Auto-enter is reserved for actual, actively playing Media3 content.
 */
internal object VideoPiPPolicy {
    const val PORTRAIT_WIDTH = 9
    const val PORTRAIT_HEIGHT = 16

    /**
     * Android's system permission is a prerequisite, never a replacement
     * for the user's own in-app PiP toggle. Turning the switch off vetoes
     * auto-enter AND explicit entry, even if OS permissions remain granted.
     */
    fun isAllowedByUser(
        enabledInApp: Boolean,
        systemAccess: VideoPiPAccess.Status,
    ): Boolean = enabledInApp &&
        systemAccess != VideoPiPAccess.Status.BLOCKED &&
        systemAccess != VideoPiPAccess.Status.UNSUPPORTED

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
