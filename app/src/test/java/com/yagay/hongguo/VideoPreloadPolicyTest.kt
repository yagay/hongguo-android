package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreloadPolicyTest {
    @Test fun secondNeighbourIsLowerPriorityAndBounded() {
        assertEquals(2 * VideoPreloadPolicy.MIB, VideoPreloadPolicy.LATER_BYTES)
        assertTrue(VideoPreloadPolicy.LATER_BYTES < VideoPreloadPolicy.DEFAULT_BYTES)
        assertTrue(VideoPreloadPolicy.STARTUP_BYTES <= VideoPreloadPolicy.LATER_BYTES)
    }

    @Test fun defaultPrefetchKeepsEightMegabytes() {
        assertEquals(8 * 1024 * 1024, VideoPreloadPolicy.DEFAULT_BYTES)
    }

    @Test fun supportedSizesIncludeSixteenMegabytes() {
        assertEquals(listOf(1, 2, 4, 8, 16),
            VideoPreloadPolicy.OPTIONS_MIB)
        assertEquals(16 * 1024 * 1024, VideoPreloadPolicy.bytes(16))
    }

    @Test fun corruptSavedPreferenceIsBounded() {
        assertEquals(8, VideoPreloadPolicy.normalizedMiB(256))
        assertEquals(VideoPreloadPolicy.DEFAULT_BYTES,
            VideoPreloadPolicy.validBytes(64 * 1024 * 1024))
        assertEquals(1024 * 1024, VideoPreloadPolicy.bytes(1))
    }
}
