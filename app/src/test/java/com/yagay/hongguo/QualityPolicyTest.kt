package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPolicyTest {
    @Test fun upperLimitAndFallback() {
        val qualities = listOf(1080, 720, 480, 360)
        assertEquals(1080, qualities.minBy { QualityPolicy.rank(it, 0) })
        assertEquals(720, qualities.minBy { QualityPolicy.rank(it, 720) })
        assertEquals(480, listOf(1080, 480).minBy { QualityPolicy.rank(it, 720) })
        assertTrue(QualityPolicy.rank(1080, 720) > QualityPolicy.rank(480, 720))
    }
}
