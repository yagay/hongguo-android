package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDiskVideoCacheTest {
    @Test fun cacheKeyIsStableAndDoesNotExposeVideoId() {
        val a = NativeDiskVideoCache.key("12345678", 720, 25_000_000L)
        assertEquals(a, NativeDiskVideoCache.key("12345678", 720, 25_000_000L))
        assertEquals(64, a.length)
        assertFalse(a.contains("12345678"))
    }

    @Test fun differentQualityAndSizeNeverShareCache() {
        val base = NativeDiskVideoCache.key("12345678", 720, 25_000_000L)
        assertNotEquals(base, NativeDiskVideoCache.key("12345678", 1080, 25_000_000L))
        assertNotEquals(base, NativeDiskVideoCache.key("12345678", 720, 24_999_999L))
        assertNotEquals(base, NativeDiskVideoCache.key("87654321", 720, 25_000_000L))
    }

    @Test fun contentFingerprintPreventsStaleSameSizeReuse() {
        val original = NativeDiskVideoCache.fingerprintedKey(
            "12345678", 720, 25_000_000L, byteArrayOf(0, 1, 2, 3))
        val changed = NativeDiskVideoCache.fingerprintedKey(
            "12345678", 720, 25_000_000L, byteArrayOf(0, 1, 2, 4))
        assertEquals(original, NativeDiskVideoCache.fingerprintedKey(
            "12345678", 720, 25_000_000L, byteArrayOf(0, 1, 2, 3)))
        assertNotEquals(original, changed)
    }

    @Test fun sevenDayExpiryIsInclusiveAndClockSkewInvalidates() {
        val day = 24L * 60 * 60 * 1000
        val now = 300L * day
        assertFalse(NativeDiskVideoCache.expired(now - 6 * day, now))
        assertTrue(NativeDiskVideoCache.expired(now - 7 * day, now))
        assertTrue(NativeDiskVideoCache.expired(now + 1, now))
    }
}
