package com.yagay.hongguo

/**
 * Explicit in-app player navigation. Video presentation (full/mini) is not a
 * navigation destination: a Media3 surface must never decide where Back goes.
 *
 * Flow when entered through the default swipe screen:
 * FEED -> SERIES -> DETAILS -> (related work) SERIES -> FEED.
 *
 * The selected related work joins the live feed queue. Back keeps its current
 * Media3 item and the queue, rather than revealing the legacy home catalog.
 */
enum class PlaybackOrigin { SWIPE_FEED, CATALOG }

enum class PlaybackDestination { FEED, SERIES, DETAILS, CATALOG }

data class PlaybackNavigation(
    val origin: PlaybackOrigin = PlaybackOrigin.SWIPE_FEED,
    val destination: PlaybackDestination = PlaybackDestination.FEED,
    /** The surface immediately below details (feed, series or explicit catalog). */
    val detailsParent: PlaybackDestination = PlaybackDestination.SERIES,
) {
    fun toFeed(): PlaybackNavigation =
        PlaybackNavigation(PlaybackOrigin.SWIPE_FEED, PlaybackDestination.FEED)

    fun toSeries(origin: PlaybackOrigin = this.origin): PlaybackNavigation =
        PlaybackNavigation(origin, PlaybackDestination.SERIES)

    fun toDetails(): PlaybackNavigation = copy(
        detailsParent = if (destination == PlaybackDestination.DETAILS)
            detailsParent else destination,
        destination = PlaybackDestination.DETAILS)

    fun fromDetails(): PlaybackNavigation = copy(destination = detailsParent)

    fun toCatalog(): PlaybackNavigation = copy(destination = PlaybackDestination.CATALOG)

    fun restorePlayer(feedMode: Boolean): PlaybackNavigation =
        copy(destination = if (feedMode) PlaybackDestination.FEED
            else PlaybackDestination.SERIES)

    val isExplicitCatalog: Boolean
        get() = destination == PlaybackDestination.CATALOG ||
            (destination == PlaybackDestination.DETAILS &&
                detailsParent == PlaybackDestination.CATALOG)

    val returnsToFeed: Boolean
        get() = origin == PlaybackOrigin.SWIPE_FEED &&
            destination == PlaybackDestination.SERIES
}
