package com.yagay.hongguo

import org.junit.Assert.*
import org.junit.Test

class CdnStartupHealthTest {
    @Test fun healthySourcePreferredOnlyTemporarily() {
        var time = 100_000L
        val health = CdnStartupHealth { time }
        val a = "https://a.example/path?token=secret"
        val b = "https://b.example/path"
        assertEquals(0, health.score(a))
        health.success(a, 1_200)
        assertTrue(health.score(a) < health.score(b))
        time += 16 * 60_000
        assertEquals(0, health.score(a))
    }
    @Test fun failedHostIsTemporarilyDemotedAndRecovers() {
        var time = 100_000L
        val health = CdnStartupHealth { time }
        val url = "https://a.example/other-path?token=not-stored"
        health.failure(url)
        assertTrue(health.score("https://a.example/different") > 0)
        time += 31_000
        assertEquals(0, health.score(url))
        health.success(url, 50)
        assertTrue(health.score(url) < 0)
    }
}
