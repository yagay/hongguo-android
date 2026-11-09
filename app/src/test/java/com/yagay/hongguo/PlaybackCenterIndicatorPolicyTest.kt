package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCenterIndicatorPolicyTest {
    private fun icon(
        stage: NextDramaTransition = NextDramaTransition.NONE,
        error: Boolean = false,
        matching: Boolean = true,
        playing: Boolean = false,
        readyToPlay: Boolean = false,
        buffering: Boolean = false,
        ended: Boolean = false,
        progress: Boolean = true,
        feedback: Boolean = false,
    ) = PlaybackCenterIndicatorPolicy.resolve(
        transition = stage, hasPlaybackError = error,
        currentMediaMatches = matching, isPlaying = playing,
        playWhenReady = readyToPlay, buffering = buffering, ended = ended,
        hasProgress = progress, showResumeFeedback = feedback,
    )

    @Test fun playerPauseAndResumeHaveDifferentIcons() {
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.PLAY, icon())
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.PAUSE_FEEDBACK,
            icon(playing = true, readyToPlay = true, feedback = true))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.NONE,
            icon(playing = true, readyToPlay = true))
    }

    @Test fun bufferingAndFinishedDoNotLookLikeManualPause() {
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.BUFFERING,
            icon(buffering = true, readyToPlay = true))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.REPLAY,
            icon(ended = true, readyToPlay = false))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.NONE,
            icon(matching = false))
    }

    @Test fun officialNextDramaAlwaysOverridesOldEndedMedia() {
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.FINDING_NEXT_DRAMA,
            icon(stage = NextDramaTransition.FINDING_RECOMMENDATION, ended = true))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.PREPARING_NEXT_VIDEO,
            icon(stage = NextDramaTransition.PREPARING_VIDEO, ended = true))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.RETRY_NEXT_DRAMA,
            icon(stage = NextDramaTransition.UNAVAILABLE, ended = true))
        assertEquals(PlaybackCenterIndicatorPolicy.Indicator.NONE,
            icon(error = true))
    }
}
