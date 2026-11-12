package com.hongguo.android

import org.junit.Assert.*
import org.junit.Test

class PlaybackStartupTraceTest {
    @Test fun monotonicCheckpointsKeepFirstOccurrenceAndDoNotExposeArbitraryText() {
        var ms = 0L
        val trace = PlaybackStartupTrace { ms * 1_000_000L }
        ms = 8; trace.mark("episode_selected")
        ms = 40; trace.mark("device_register")
        ms = 70; trace.mark("device_register")
        ms = 125; trace.mark("video_model")
        ms = 250; trace.mark("index")
        ms = 410; trace.mark("media_ready")
        ms = 600; trace.mark("first_frame")
        trace.mark("https://secret.invalid?token=leak")
        val report = trace.report()
        assertTrue(report.contains("device_register=40"))
        assertTrue(report.contains("first_frame=600"))
        assertFalse(report.contains("device_register=70"))
        assertFalse(report.contains("secret"))
        assertEquals(1, report.lines().count { it.startsWith("device_register=") })
    }
}
