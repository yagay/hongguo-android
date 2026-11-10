package com.yagay.hongguo

import org.junit.Assert.assertEquals
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

    @Test fun selectionDefaultsAndLegacyMigration() {
        assertEquals(PreloadNetworkPolicy.WIFI, PreloadNetworkPolicy.normalized(null))
        assertEquals(PreloadNetworkPolicy.WIFI,
            PreloadNetworkPolicy.fromLegacy(true, false))
        assertEquals(PreloadNetworkPolicy.ALL,
            PreloadNetworkPolicy.fromLegacy(false, true))
        assertEquals(PreloadNetworkPolicy.OFF,
            PreloadNetworkPolicy.fromLegacy(false, false))
    }

    @Test fun offDisablesEveryPreloadLane() {
        assertFalse(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.OFF, true))
        assertFalse(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.OFF, false))
    }

    @Test fun wifiOnlyDoesNotPrefetchOnMobile() {
        assertTrue(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.WIFI, true))
        assertFalse(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.WIFI, false))
    }

    @Test fun allNetworksIncludesMobileData() {
        assertTrue(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.ALL, true))
        assertTrue(PreloadNetworkPolicy.allows(PreloadNetworkPolicy.ALL, false))
    }
}
