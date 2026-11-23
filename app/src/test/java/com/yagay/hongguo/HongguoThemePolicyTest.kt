package com.yagay.hongguo

import org.junit.Assert.*
import org.junit.Test

class HongguoThemePolicyTest {
    @Test fun classicAndMaterialKeepIndependentDarkMode() {
        assertTrue(HongguoThemePolicy.isDark("system", true))
        assertFalse(HongguoThemePolicy.isDark("system", false))
        assertTrue(HongguoThemePolicy.isDark("dark", false))
        assertFalse(HongguoThemePolicy.isDark("light", true))
    }

    @Test fun dynamicColorRequiresExplicitOptInAndAndroid12() {
        assertTrue(HongguoThemePolicy.shouldUseDynamic("material3", "dynamic", 35))
        assertFalse(HongguoThemePolicy.shouldUseDynamic("material3", "brand", 35))
        assertFalse(HongguoThemePolicy.shouldUseDynamic("classic", "dynamic", 35))
        assertFalse(HongguoThemePolicy.shouldUseDynamic("material3", "dynamic", 30))
    }

    @Test fun posterLayoutsPreservePhoneAndAdaptLargeDisplays() {
        assertEquals(2, HongguoThemePolicy.catalogColumns(360))
        assertTrue(HongguoThemePolicy.catalogColumns(850) >= 4)
        assertEquals(3, HongguoThemePolicy.galleryColumns(390))
        assertEquals(5, HongguoThemePolicy.galleryColumns(760))
        assertEquals(6, HongguoThemePolicy.galleryColumns(1080))
        assertEquals(2, HongguoThemePolicy.galleryColumns(320))
        assertEquals(2, HongguoThemePolicy.galleryColumns(390, 1.5f))
        assertEquals(4, HongguoThemePolicy.galleryColumns(760, 1.5f))
        assertEquals(2, HongguoThemePolicy.catalogColumns(390, 1.5f))
        assertTrue(HongguoThemePolicy.catalogColumns(1080, 1f) >= 5)
    }

    @Test fun detailTwoPaneRequiresSufficientWidth() {
        assertFalse(HongguoThemePolicy.useTwoPaneDetail(390))
        assertFalse(HongguoThemePolicy.useTwoPaneDetail(600))
        assertTrue(HongguoThemePolicy.useTwoPaneDetail(840))
        assertTrue(HongguoThemePolicy.useTwoPaneDetail(1200))
    }

    @Test fun episodeGridPreservesFiveColumnsExceptSmallOrLargeFont() {
        assertEquals(5, HongguoThemePolicy.episodeColumns(390, false))
        assertEquals(4, HongguoThemePolicy.episodeColumns(320, false))
        assertEquals(4, HongguoThemePolicy.episodeColumns(390, true))
        assertTrue(HongguoThemePolicy.episodeColumns(900, false) <= 8)
    }
}
