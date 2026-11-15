package com.yagay.hongguo

/**
 * Cross-season continuation is only considered at the end of a full-series
 * remote playback. Swipe recommendations remain their own queue; downloaded
 * single episodes and manual navigation never trigger network season lookups.
 */
internal object SeasonAutoplayPolicy {
    fun shouldCheck(
        autoPlay: Boolean,
        feedMode: Boolean,
        offline: Boolean,
        finalEpisode: Boolean,
    ): Boolean = autoPlay && !feedMode && !offline && finalEpisode

    fun isSameFinishedEpisode(
        expectedSeries: String,
        expectedVid: String,
        currentSeries: String?,
        currentVid: String?,
        autoPlay: Boolean,
    ): Boolean = autoPlay &&
        expectedSeries == currentSeries &&
        expectedVid == currentVid
}
