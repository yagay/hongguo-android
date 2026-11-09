package com.yagay.hongguo

/**
 * Show destination artwork only when the video surface belongs to a different
 * media item (or has no media). Buffering the same media keeps its decoded
 * frame visible instead of flashing a poster.
 *
 * The same rule is used by the in-app mini player and Android PiP.
 */
internal object PlaybackFramePolicy {
    fun showCover(expectedVid: String?, actualMediaId: String?): Boolean =
        expectedVid.isNullOrBlank() || expectedVid != actualMediaId
}
