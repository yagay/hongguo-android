package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatterTest {
    @Test fun actualPositionAndDurationOnly() {
        assertEquals("01:24 / 03:16", PlaybackTimeFormatter.label(84_000L, 196_000L))
        assertEquals("00:00 / --:--", PlaybackTimeFormatter.label(0L, 0L))
        assertEquals("1:02:03 / 1:04:00",
            PlaybackTimeFormatter.label(3_723_000L, 3_840_000L))
    }
}
