package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelModeSlidePolicyTest {
    @Test fun enterSeriesComesFromRightOnlyWhenLeavingReel() {
        assertEquals(ReelModeSlidePolicy.Motion.ENTER_FROM_RIGHT,
            ReelModeSlidePolicy.motion(true, false, true))
        assertEquals(ReelModeSlidePolicy.Motion.NONE,
            ReelModeSlidePolicy.motion(true, false, false))
    }
    @Test fun returningFromSeriesExitsToTheRight() {
        assertEquals(ReelModeSlidePolicy.Motion.EXIT_TO_RIGHT,
            ReelModeSlidePolicy.motion(false, true, true))
    }
    @Test fun initialMountAndUnchangedModeNeverAnimate() {
        assertEquals(ReelModeSlidePolicy.Motion.NONE,
            ReelModeSlidePolicy.motion(null, true, true))
        assertEquals(ReelModeSlidePolicy.Motion.NONE,
            ReelModeSlidePolicy.motion(false, false, true))
        assertEquals(ReelModeSlidePolicy.Motion.NONE,
            ReelModeSlidePolicy.motion(true, true, true))
    }
}
