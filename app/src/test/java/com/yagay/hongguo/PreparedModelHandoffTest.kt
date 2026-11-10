package com.yagay.hongguo

import org.junit.Assert.*
import org.junit.Test

class PreparedModelHandoffTest {
    @Test fun backgroundModelCanBeClaimedExactlyOnce() {
        var time = 500L
        val cache = PreparedModelHandoff<String>({ time })
        cache.offer("vid", "model")
        assertEquals("model", cache.take("vid"))
        assertNull(cache.take("vid"))
        cache.offer("vid", "new")
        cache.invalidate("vid")
        assertNull(cache.take("vid"))
    }

    @Test fun staleResultsCannotBeReused() {
        var time = 500L
        val cache = PreparedModelHandoff<String>({ time })
        cache.offer("vid", "old")
        time += 12_001
        assertNull(cache.take("vid"))
    }
}
