package com.yagay.hongguo

/** Explicit cross-drama transition: a stopped last frame is not a paused video. */
enum class NextDramaTransition {
    NONE,
    FINDING_RECOMMENDATION,
    PREPARING_VIDEO,
    UNAVAILABLE,
}

/** Render the real Media3 playback state instead of always drawing PlayArrow. */
internal object PlaybackCenterIndicatorPolicy {
    enum class Indicator {
        NONE, PLAY, REPLAY, PAUSE_FEEDBACK, BUFFERING,
        FINDING_NEXT_DRAMA, PREPARING_NEXT_VIDEO, RETRY_NEXT_DRAMA,
    }

    fun resolve(
        transition: NextDramaTransition,
        hasPlaybackError: Boolean,
        currentMediaMatches: Boolean,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        buffering: Boolean,
        ended: Boolean,
        hasProgress: Boolean,
        showResumeFeedback: Boolean,
    ): Indicator {
        // The last episode has ended; the old Media3 item remains STATE_ENDED
        // while the official recommendation and first new video are resolving.
        return when (transition) {
            NextDramaTransition.FINDING_RECOMMENDATION -> Indicator.FINDING_NEXT_DRAMA
            NextDramaTransition.PREPARING_VIDEO -> Indicator.PREPARING_NEXT_VIDEO
            NextDramaTransition.UNAVAILABLE -> Indicator.RETRY_NEXT_DRAMA
            NextDramaTransition.NONE -> when {
                hasPlaybackError || !currentMediaMatches -> Indicator.NONE
                buffering && playWhenReady -> Indicator.BUFFERING
                ended -> Indicator.REPLAY
                isPlaying && showResumeFeedback -> Indicator.PAUSE_FEEDBACK
                !isPlaying && !playWhenReady && hasProgress -> Indicator.PLAY
                else -> Indicator.NONE
            }
        }
    }
}
