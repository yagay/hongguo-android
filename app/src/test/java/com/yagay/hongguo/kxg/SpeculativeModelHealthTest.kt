package com.yagay.hongguo.kxg

import dev.openhongguo.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeModelHealthTest {
    @Test fun repeatedEmptyResponsesExtendCooldownUntilSuccess() {
        var now = 0L
        val health = SpeculativeModelHealth { now }
        assertFalse(health.isCoolingDown())
        for (expected in listOf(300_000L, 600_000L, 1_200_000L, 1_800_000L, 1_800_000L)) {
            assertEquals(expected, health.onFailure(ErrorCode.RESPONSE_EMPTY))
            assertTrue(health.isCoolingDown())
        }
        now = 1_800_000L
        assertFalse(health.isCoolingDown())
        health.onSuccess()
        assertEquals(300_000L, health.onFailure(ErrorCode.RESPONSE_EMPTY))
        health.onSuccess()
        assertFalse(health.isCoolingDown())
    }

    @Test fun successDoesNotEraseExplicitServerBackoff() {
        var now = 0L
        val health = SpeculativeModelHealth { now }
        assertEquals(900_000L, health.onFailure(ErrorCode.RATE_LIMITED, 900_000L))
        health.onSuccess()
        now = 899_999L
        assertTrue(health.isCoolingDown())
        now++
        assertFalse(health.isCoolingDown())
    }

    @Test fun signatureFailureStopsSpeculationButEpisodeUnavailableDoesNot() {
        val health = SpeculativeModelHealth { 0L }
        assertEquals(0L, health.onFailure(ErrorCode.CONTENT_UNAVAILABLE))
        assertFalse(health.isCoolingDown())
        assertEquals(300_000L, health.onFailure(ErrorCode.SIGNATURE_REJECTED))
        assertTrue(health.isCoolingDown())
    }
}
