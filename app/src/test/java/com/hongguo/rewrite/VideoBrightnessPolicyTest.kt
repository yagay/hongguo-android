package com.hongguo.rewrite

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBrightnessPolicyTest {
    @Test fun systemBrightnessDoesNotForceWindowOverride() {
        assertFalse(VideoBrightnessPolicy.isOverride(-1))
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            VideoBrightnessPolicy.toWindowValue(-1), 0.0001f)
    }

    @Test fun validBrightnessOverridesRemainWithinAndroidWindowRange() {
        assertTrue(VideoBrightnessPolicy.isOverride(65))
        assertTrue(VideoBrightnessPolicy.isOverride(80))
        assertTrue(VideoBrightnessPolicy.isOverride(90))
        assertTrue(VideoBrightnessPolicy.isOverride(100))
        assertEquals(0.65f, VideoBrightnessPolicy.toWindowValue(65), 0.001f)
        assertEquals(1.00f, VideoBrightnessPolicy.toWindowValue(100), 0.001f)
    }

    @Test fun invalidOutOfBoundsValuesAreRejected() {
        assertFalse(VideoBrightnessPolicy.isOverride(0))
        assertFalse(VideoBrightnessPolicy.isOverride(39))
        assertFalse(VideoBrightnessPolicy.isOverride(101))
    }
}
