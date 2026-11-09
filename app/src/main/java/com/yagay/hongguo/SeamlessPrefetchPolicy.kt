package com.yagay.hongguo

/**
 * Only one warmup lane at a time: upcoming episode > upcoming recommended
 * drama > official recommendation discovery. The actual video still plays
 * through the one Media3 instance owned by BackgroundPlaybackService.
 */
internal object SeamlessPrefetchPolicy {
    enum class Next { NONE, NEXT_EPISODE, NEXT_DRAMA, FETCH_RECOMMENDATIONS }

    fun choose(
        enabled: Boolean,
        autoPlayNext: Boolean,
        inSwipeFeed: Boolean,
        offline: Boolean,
        hasNextEpisode: Boolean,
        hasQueuedNextDrama: Boolean,
    ): Next {
        if (!enabled || offline) return Next.NONE
        if (hasNextEpisode) return Next.NEXT_EPISODE
        if (!autoPlayNext && !inSwipeFeed) return Next.NONE
        if (hasQueuedNextDrama) return Next.NEXT_DRAMA
        return Next.FETCH_RECOMMENDATIONS
    }
}
