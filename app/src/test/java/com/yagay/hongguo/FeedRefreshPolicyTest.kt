package com.yagay.hongguo

import org.junit.Assert.*
import org.junit.Test

class FeedRefreshPolicyTest {
    private val a = Drama("100", "first")
    private val b = Drama("200", "failed")
    @Test fun failedCurrentDramaRemainsOnScreenUntilNextBatch() {
        assertEquals(b, FeedRefreshPolicy.anchor(listOf(a,b), 1, b))
    }
    @Test fun fallbackUsesCurrentQueuePosition() {
        assertEquals(a, FeedRefreshPolicy.anchor(listOf(a), 0, null))
        assertNull(FeedRefreshPolicy.anchor(emptyList(), 0, null))
    }
}
