package com.yagay.hongguo

/**
 * A media notification's previous/next control behaves like a vertical pager
 * swipe, not a 10-second seek. The service must never invent a playable episode.
 */
internal object BackgroundNavigationPolicy {
    data class Availability(val previous: Boolean, val next: Boolean)

    fun availability(
        feedMode: Boolean,
        selectedIndex: Int,
        episodeCount: Int,
        previousFeedExists: Boolean,
        nextFeedAvailableOrPending: Boolean,
    ): Availability {
        if (episodeCount <= 0 || selectedIndex !in 0 until episodeCount)
            return Availability(false, false)
        return if (feedMode) Availability(
            previous = previousFeedExists,
            next = nextFeedAvailableOrPending,
        ) else Availability(
            previous = selectedIndex > 0,
            next = selectedIndex < episodeCount - 1,
        )
    }

    fun title(series: String, episode: Int): String =
        "$series · 第${episode.coerceAtLeast(1)}集"

    fun episodeLabel(episode: Int, total: Int): String =
        "第${episode.coerceAtLeast(1)}集 / 全${total.coerceAtLeast(1)}集"
}
