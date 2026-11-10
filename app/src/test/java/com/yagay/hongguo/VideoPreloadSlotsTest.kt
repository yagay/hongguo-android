package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreloadSlotsTest {
    @Test fun immediateEpisodeUsesFullConfiguredBuffer() {
        assertEquals(16 * VideoPreloadPolicy.MIB,
            VideoPreloadPolicy.bytesFor(VideoPreloadPolicy.Slot.NEXT_EPISODE, 16))
    }
    @Test fun distantTargetsHaveSmallerBudgets() {
        assertEquals(8 * VideoPreloadPolicy.MIB,
            VideoPreloadPolicy.bytesFor(VideoPreloadPolicy.Slot.NEXT_DRAMA, 16))
        assertEquals(2 * VideoPreloadPolicy.MIB,
            VideoPreloadPolicy.bytesFor(VideoPreloadPolicy.Slot.LATER, 16))
        assertEquals(1 * VideoPreloadPolicy.MIB,
            VideoPreloadPolicy.bytesFor(VideoPreloadPolicy.Slot.LATER, 1))
    }
}
