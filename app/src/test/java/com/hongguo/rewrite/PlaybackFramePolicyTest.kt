package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFramePolicyTest {
    @Test fun currentMediaRemainsVisibleWhileBuffering() {
        assertFalse(PlaybackFramePolicy.showCover("episode-2", "episode-2"))
    }

    @Test fun transitioningToDifferentEpisodeShowsDestinationCover() {
        assertTrue(PlaybackFramePolicy.showCover("episode-2", "episode-1"))
    }

    @Test fun unavailableMediaOrExpectedEpisodeDoesNotShowStaleFrame() {
        assertTrue(PlaybackFramePolicy.showCover("episode-2", null))
        assertTrue(PlaybackFramePolicy.showCover(null, "episode-1"))
        assertTrue(PlaybackFramePolicy.showCover("", "episode-1"))
    }
}
