package com.yagay.hongguo.kxg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KxgWarmupPolicyTest {
    @Test fun cacheExpiryKeepsSafetyMargin() {
        val now = 1_700_000_000_000L
        val expirySeconds = (now + 240_000L) / 1000L
        val valid = KxgWarmupPolicy.cacheValidUntilMs(expirySeconds, now)
        assertEquals(now + 120_000L, valid)
        assertTrue(KxgWarmupPolicy.reusable(valid, now))
        assertFalse(KxgWarmupPolicy.reusable(valid, valid - 4_000L))
        val expiring = KxgWarmupPolicy.cacheValidUntilMs((now + 30_000L) / 1000L, now)
        assertFalse(KxgWarmupPolicy.reusable(expiring, now))
    }

    @Test fun noExpiryHasShortBoundedLifetime() {
        val now = 1_700_000_000_000L
        assertEquals(now + 30_000L,
            KxgWarmupPolicy.cacheValidUntilMs(0L, now))
        assertTrue(KxgWarmupPolicy.reusable(now + 30_000L, now))
    }

    @Test fun neverStartSpeculativeIoDuringRebuffering() {
        assertFalse(KxgWarmupPolicy.bufferedEnough(false, true, 30_000))
        assertFalse(KxgWarmupPolicy.bufferedEnough(true, false, 30_000))
        assertFalse(KxgWarmupPolicy.bufferedEnough(true, true, 5_999))
        assertTrue(KxgWarmupPolicy.bufferedEnough(true, true, 6_000))
    }

    @Test fun encryptedPrefixReadsAtAbsoluteOffsetsWithoutDuplicatingBytes() {
        val bytes = byteArrayOf(7, 8, 9, 10, 11, 12)
        val cache = EncryptedPrefixMemory()
        cache.publish(bytes.copyOfRange(0, 3))
        val buffer = ByteArray(5)
        assertEquals(2, cache.copy(1, buffer, 1, 5))
        assertArrayEquals(byteArrayOf(0, 8, 9, 0, 0), buffer)
        cache.publish(bytes)
        cache.publish(byteArrayOf(99)) // shrinking is forbidden
        assertEquals(6, cache.size)
        assertEquals(3, cache.copy(3, buffer, 0, 3))
        assertArrayEquals(byteArrayOf(10, 11, 12, 0, 0), buffer)
        assertEquals(0, cache.copy(6, buffer, 0, 1))
    }

    @Test fun budgetsStayBounded() {
        assertTrue(KxgWarmupPolicy.PREFIX_BYTES <= 2 * 1024 * 1024)
        assertTrue(KxgWarmupPolicy.MAX_CACHED_MODELS <= 3)
        assertTrue(KxgWarmupPolicy.CHUNK_BYTES <= KxgWarmupPolicy.PREFIX_BYTES)
    }
}
