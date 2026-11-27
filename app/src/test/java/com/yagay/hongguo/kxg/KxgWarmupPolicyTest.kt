package com.yagay.hongguo.kxg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class KxgWarmupPolicyTest {
    @Test fun configurableBudgetsAreLargeButValidated() {
        assertTrue(KxgWarmupPolicy.validPrefixMb(512))
        assertTrue(KxgWarmupPolicy.validTotalMb(4096))
        assertTrue(KxgWarmupPolicy.validPreloadCount(5))
        assertTrue(KxgWarmupPolicy.validDeadlineSeconds(600))
        assertFalse(KxgWarmupPolicy.validPrefixMb(2048))
        assertFalse(KxgWarmupPolicy.validTotalMb(-1))
        assertEquals(512 * KxgWarmupPolicy.MIB,
            KxgWarmupPolicy.effectivePrefixBytes(512, 1024))
    }

    @Test fun expiryPreservesSafetyMarginAndUnknownLifetimes() {
        val now = 1_700_000_000_000L
        val valid = KxgWarmupPolicy.cacheValidUntilMs((now + 240_000L) / 1000L, now)
        assertEquals(now + 180_000L, valid)
        assertTrue(KxgWarmupPolicy.reusable(valid, now))
        assertFalse(KxgWarmupPolicy.reusable(valid, valid - 4_000L))
        assertEquals(now + 120_000L, KxgWarmupPolicy.cacheValidUntilMs(0L, now))
    }

    @Test fun hardExpiryIsIndependentOfConservativeReuseWindow() {
        val now = 1_700_000_000_000L
        assertTrue(KxgWarmupPolicy.hardExpired(now / 1000L, now))
        assertFalse(KxgWarmupPolicy.hardExpired(0L, now))
        assertFalse(KxgWarmupPolicy.hardExpired(now / 1000L + 1L, now))
    }

    @Test fun currentVideoBufferHasPriority() {
        assertFalse(KxgWarmupPolicy.bufferedEnough(false, true, 30_000))
        assertFalse(KxgWarmupPolicy.bufferedEnough(true, false, 30_000))
        assertFalse(KxgWarmupPolicy.bufferedEnough(true, true, 5_999, 6))
        assertTrue(KxgWarmupPolicy.bufferedEnough(true, true, 6_000, 6))
        assertTrue(KxgWarmupPolicy.bufferedEnough(true, true, 0, 0))
    }

    @Test fun diskCacheCanReadPartialChunksAndOverlappingReconnects() {
        val dir = Files.createTempDirectory("encrypted-video-cache").toFile()
        try {
            val cache = EncryptedPrefixFile(java.io.File(dir, "sample.enc"))
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
            assertEquals(3, cache.append(0L, bytes, 0, 3, 7))
            val out = ByteArray(4)
            assertEquals(2, cache.copy(1L, out, 1, 3))
            assertArrayEquals(byteArrayOf(0, 2, 3, 0), out)
            assertEquals(3, cache.append(2L, bytes, 2, 4, 7))
            assertEquals(6, cache.size)
            assertEquals(0, cache.append(10L, bytes, 0, 1, 7))
            assertEquals(1, cache.append(6L, bytes, 6, 1, 7))
            assertEquals(7, cache.size)
            assertEquals(3, cache.copy(4L, out, 0, 4))
            assertArrayEquals(byteArrayOf(5, 6, 7, 0), out)
            cache.evict()
            assertEquals(0, cache.size)
            assertEquals(0, cache.copy(0, out, 0, 4))
            cache.dispose()
        } finally { dir.deleteRecursively() }
    }
}
