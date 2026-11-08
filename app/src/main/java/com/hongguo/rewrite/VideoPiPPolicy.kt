package com.hongguo.rewrite

/**
 * PiP is only a presentation mode for the existing service-owned video.
 * Auto-enter is reserved for actual, actively playing Media3 content.
 */
internal object VideoPiPPolicy {
    const val PORTRAIT_WIDTH = 9
    const val PORTRAIT_HEIGHT = 16

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
