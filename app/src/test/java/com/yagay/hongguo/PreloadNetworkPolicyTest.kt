package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadNetworkPolicyTest {
    @Test fun wifiStillPrefetchesWhenMobilePrefetchIsOff() {
        assertTrue(NativeDiskVideoCache.allowedTransport(
            isWifi = true, wifiEnabled = true, mobileEnabled = false))
    }

    @Test fun mobileNeedsSeparateOptIn() {
        assertFalse(NativeDiskVideoCache.allowedTransport(
            isWifi = false, wifiEnabled = true, mobileEnabled = false))
        assertTrue(NativeDiskVideoCache.allowedTransport(
            isWifi = false, wifiEnabled = true, mobileEnabled = true))
    }

    @Test fun wifiUserToggleIsHonored() {
        assertFalse(NativeDiskVideoCache.allowedTransport(
            isWifi = true, wifiEnabled = false, mobileEnabled = true))
    }
}
