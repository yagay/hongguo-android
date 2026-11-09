package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldFastForwardPolicyTest {
    @Test
    fun bothEdgesTriggerTheSameTemporaryForwardSpeed() {
        assertEquals(2f, HoldFastForwardPolicy.SPEED, 0f)
        assertEquals(-1, HoldFastForwardPolicy.sideForTouch(5f, 100f))
        assertEquals(1, HoldFastForwardPolicy.sideForTouch(95f, 100f))
    }

    @Test
    fun centerStillUsesOriginalClearScreenGesture() {
        assertNull(HoldFastForwardPolicy.sideForTouch(50f, 100f))
        assertNull(HoldFastForwardPolicy.sideForTouch(22f, 100f))
        assertNull(HoldFastForwardPolicy.sideForTouch(78f, 100f))
    }

    @Test
    fun invalidTouchCoordinatesCannotTriggerSpeed() {
        assertNull(HoldFastForwardPolicy.sideForTouch(-1f, 100f))
        assertNull(HoldFastForwardPolicy.sideForTouch(101f, 100f))
        assertNull(HoldFastForwardPolicy.sideForTouch(0f, 0f))
        assertNull(HoldFastForwardPolicy.sideForTouch(Float.NaN, 100f))
    }
}
