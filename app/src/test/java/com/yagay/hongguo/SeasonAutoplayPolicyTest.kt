package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonAutoplayPolicyTest {
    @Test fun onlyRemoteFullSeriesFinaleChecksOfficialSeasons() {
        assertTrue(SeasonAutoplayPolicy.shouldCheck(true, false, false, true))
        assertFalse(SeasonAutoplayPolicy.shouldCheck(false, false, false, true))
        assertFalse(SeasonAutoplayPolicy.shouldCheck(true, true, false, true))
        assertFalse(SeasonAutoplayPolicy.shouldCheck(true, false, true, true))
        assertFalse(SeasonAutoplayPolicy.shouldCheck(true, false, false, false))
    }

    @Test fun lateResultsCannotReplaceAnotherDramaOrEpisode() {
        assertTrue(SeasonAutoplayPolicy.isSameFinishedEpisode(
            "100", "vid1", "100", "vid1", true))
        assertFalse(SeasonAutoplayPolicy.isSameFinishedEpisode(
            "100", "vid1", "200", "vid1", true))
        assertFalse(SeasonAutoplayPolicy.isSameFinishedEpisode(
            "100", "vid1", "100", "vid2", true))
        assertFalse(SeasonAutoplayPolicy.isSameFinishedEpisode(
            "100", "vid1", "100", "vid1", false))
    }
}
