package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelAutoplayPolicyTest {
    private val NEXT_EPISODE = ReelAutoplayPolicy.Action.NEXT_EPISODE
    private val NEXT_DRAMA = ReelAutoplayPolicy.Action.NEXT_DRAMA
    private val STOP = ReelAutoplayPolicy.Action.STOP

    @Test fun reelContinuesEveryEpisodeUntilFinalEpisode() {
        assertEquals(NEXT_EPISODE, ReelAutoplayPolicy.onEnded(0, 10, true, true))
        assertEquals(NEXT_EPISODE, ReelAutoplayPolicy.onEnded(8, 10, true, true))
        assertEquals(NEXT_DRAMA, ReelAutoplayPolicy.onEnded(9, 10, true, true))
    }

    @Test fun fullSeriesAutoplayFetchesNextDramaAfterFinalEpisode() {
        assertEquals(NEXT_EPISODE, ReelAutoplayPolicy.onEnded(0, 2, true, false))
        assertEquals(NEXT_DRAMA, ReelAutoplayPolicy.onEnded(1, 2, true, false))
    }

    @Test fun disabledAutoplayNeverMovesToNextEpisodeOrSeries() {
        assertEquals(STOP, ReelAutoplayPolicy.onEnded(0, 10, false, true))
        assertEquals(STOP, ReelAutoplayPolicy.onEnded(9, 10, false, true))
        assertEquals(STOP, ReelAutoplayPolicy.onEnded(9, 10, false, false))
    }

    @Test fun singleEpisodeDramaAndInvalidIndicesAreHandled() {
        assertEquals(NEXT_DRAMA, ReelAutoplayPolicy.onEnded(0, 1, true, true))
        assertEquals(NEXT_DRAMA, ReelAutoplayPolicy.onEnded(0, 1, true, false))
        assertEquals(STOP, ReelAutoplayPolicy.onEnded(0, 0, true, true))
        assertEquals(STOP, ReelAutoplayPolicy.onEnded(-1, 3, true, true))
    }
}
