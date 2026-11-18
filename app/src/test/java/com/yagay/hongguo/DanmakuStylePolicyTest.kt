package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuStylePolicyTest {
    @Test fun weightAndSpacingPresetsAreBounded() {
        assertTrue(DanmakuStylePolicy.validFontWeight(300))
        assertTrue(DanmakuStylePolicy.validFontWeight(600))
        assertFalse(DanmakuStylePolicy.validFontWeight(350))
        assertTrue(DanmakuStylePolicy.validLineSpacing(0))
        assertTrue(DanmakuStylePolicy.validLineSpacing(8))
        assertFalse(DanmakuStylePolicy.validLineSpacing(16))
        assertTrue(DanmakuStylePolicy.validOutline(0))
        assertTrue(DanmakuStylePolicy.validOutline(2))
        assertFalse(DanmakuStylePolicy.validOutline(3))
        assertTrue(DanmakuStylePolicy.validTextColor(3))
        assertFalse(DanmakuStylePolicy.validTextColor(4))
    }

    @Test fun laneSpacingActuallyIncreasesVerticalSeparation() {
        val tight = DanmakuStylePolicy.laneHeightPx(16f, 19f, 0f, 0f)
        val wide = DanmakuStylePolicy.laneHeightPx(16f, 19f, 8f, 0f)
        assertEquals(8f, wide - tight, 0.001f)
    }

    @Test fun outlinedLettersNeverOverlapNeighboringLanes() {
        val height = DanmakuStylePolicy.laneHeightPx(18f, 23f, 3f, 2f)
        assertTrue(height >= 23f + 4f + 3f)
    }
}
