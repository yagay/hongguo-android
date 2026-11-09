package com.yagay.hongguo

/** Finishing the final episode can continue with an official recommended drama. */
internal object ReelAutoplayPolicy {
    enum class Action { STOP, NEXT_EPISODE, NEXT_DRAMA }

    fun onEnded(
        currentEpisodeIndex: Int,
        episodeCount: Int,
        autoPlayEnabled: Boolean,
        @Suppress("UNUSED_PARAMETER") inReels: Boolean,
    ): Action {
        if (!autoPlayEnabled || currentEpisodeIndex !in 0 until episodeCount) return Action.STOP
        if (currentEpisodeIndex + 1 < episodeCount) return Action.NEXT_EPISODE
        // Both the swipe feed and the full-series player share official
        // recommendations after the FINAL episode, when autoplay is enabled.
        return Action.NEXT_DRAMA
    }
}
