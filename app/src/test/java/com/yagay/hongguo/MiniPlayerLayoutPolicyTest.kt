package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerLayoutPolicyTest {
    @Test fun movementClampsToBounds() {
        assertEquals(0f, MiniPlayerLayoutPolicy.move(.1f, -200f, 100f))
        assertEquals(1f, MiniPlayerLayoutPolicy.move(.9f, 200f, 100f))
        assertEquals(1f, MiniPlayerLayoutPolicy.move(.3f, 20f, 0f))
    }

    @Test fun snapsToNearestHorizontalEdge() {
        assertEquals(0f, MiniPlayerLayoutPolicy.snapX(.49f))
        assertEquals(1f, MiniPlayerLayoutPolicy.snapX(.50f))
        assertEquals(1f, MiniPlayerLayoutPolicy.snapX(.9f))
    }

    @Test fun rightEdgeUsesZeroTranslationAndLeftUsesNegativeTravel() {
        assertEquals(0f, MiniPlayerLayoutPolicy.offsetPx(250f, 1f))
        assertEquals(-250f, MiniPlayerLayoutPolicy.offsetPx(250f, 0f))
        assertEquals(-125f, MiniPlayerLayoutPolicy.offsetPx(250f, .5f))
    }
}
