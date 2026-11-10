package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelDrawerPolicyTest {
    @Test
    fun alreadyOpenDrawerAlwaysAcceptsDismissGesture() {
        assertTrue(ReelDrawerPolicy.gesturesEnabled(isOpen = true,
            feedMode = false, searchOverlayOpen = false))
        assertTrue(ReelDrawerPolicy.gesturesEnabled(isOpen = true,
            feedMode = true, searchOverlayOpen = true))
    }

    @Test
    fun closedDrawerNeverOpensFromRightSwipeInAnyMode() {
        assertFalse(ReelDrawerPolicy.gesturesEnabled(isOpen = false,
            feedMode = false, searchOverlayOpen = false))
        assertFalse(ReelDrawerPolicy.gesturesEnabled(isOpen = false,
            feedMode = true, searchOverlayOpen = true))
        assertFalse(ReelDrawerPolicy.gesturesEnabled(isOpen = false,
            feedMode = true, searchOverlayOpen = false))
    }
}
