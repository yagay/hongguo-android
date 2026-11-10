package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRoutesTest {
    @Test fun speculativePreloadUsesOnlyOneHealthyRoute() {
        val routes = PlaybackRoutes()
        assertEquals(1, routes.plan(100_000L, speculative = true).size)
        assertTrue(routes.plan(100_000L, speculative = false).size >= 2)
    }

    @Test fun successfulRouteIsPreferredForSubsequentEpisodes() {
        val routes = PlaybackRoutes()
        val original = routes.plan(100_000L, speculative = false)
        val preferred = original.last()
        routes.success(preferred, 100_100L)
        assertEquals(preferred, routes.plan(100_200L, speculative = true).first())
        assertEquals(preferred, routes.plan(100_200L, speculative = false).first())
    }

    @Test fun emptyRouteIsSkippedDuringCooldown() {
        val routes = PlaybackRoutes()
        val first = routes.plan(100_000L, speculative = true).single()
        routes.failed(first, 100_050L, empty = true)
        assertTrue(first !in routes.plan(100_100L, speculative = true))
    }
}
