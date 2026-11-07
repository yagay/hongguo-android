package com.hongguo.rewrite

/** A completed video and a manual vertical swipe are independent events. */
internal object ReelAutoplayPolicy {
    enum class Action { STOP, NEXT_EPISODE, NEXT_DRAMA }

    fun onEnded(
        currentEpisodeIndex: Int,
        episodeCount: Int,
        autoPlayEnabled: Boolean,
        inReels: Boolean,
    ): Action {
        if (!autoPlayEnabled || currentEpisodeIndex !in 0 until episodeCount) return Action.STOP
        if (currentEpisodeIndex + 1 < episodeCount) return Action.NEXT_EPISODE
        return if (inReels) Action.NEXT_DRAMA else Action.STOP
    }
}
