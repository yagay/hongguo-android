package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicPlaybackRetryPolicyTest {
    @Test fun repeatedFailuresBackOffAndRemainBounded() {
        assertEquals(15_000L, PeriodicPlaybackRetryPolicy.delayMs(1))
        assertEquals(30_000L, PeriodicPlaybackRetryPolicy.delayMs(2))
        assertEquals(60_000L, PeriodicPlaybackRetryPolicy.delayMs(3))
        assertEquals(120_000L, PeriodicPlaybackRetryPolicy.delayMs(4))
        assertEquals(300_000L, PeriodicPlaybackRetryPolicy.delayMs(5))
        assertEquals(300_000L, PeriodicPlaybackRetryPolicy.delayMs(1_000))
    }

    @Test fun permanentFailuresNeverAutomaticallyRepeat() {
        listOf("UPSTREAM_RATE_LIMIT", "UPSTREAM_UNAUTHORIZED",
            "UPSTREAM_FORBIDDEN", "CONTENT_UNAVAILABLE",
            "MEDIA_CODEC_UNSUPPORTED", "CODEC_NOT_SUPPORTED",
            "SOURCE_NOT_SUPPORTED", "MEDIA_PLAYER_ERROR").forEach {
                assertFalse(it, PeriodicPlaybackRetryPolicy.recoverableCode(it))
            }
    }

    @Test fun transientFailuresCanRepeat() {
        listOf("NETWORK_TIMEOUT", "NETWORK_IO · 获取播放模型：网络错误",
            "PLAYBACK_MODEL_EMPTY", "MEDIA_IO_ERROR", "NETWORK_DNS").forEach {
                assertTrue(it, PeriodicPlaybackRetryPolicy.recoverableCode(it))
            }
    }
}
