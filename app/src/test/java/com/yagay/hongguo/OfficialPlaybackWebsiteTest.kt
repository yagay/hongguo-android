package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialPlaybackWebsiteTest {
    @Test fun onlyValidSeriesIdsCreateOfficialLinks() {
        assertEquals("https://hongguoduanju.com/player/1234567890123456789",
            OfficialPlaybackWebsite.seriesUrl("1234567890123456789"))
        for (bad in listOf("", "../login", "https://example.org", "123?token=x", "1".repeat(31))) {
            assertNull(OfficialPlaybackWebsite.seriesUrl(bad))
        }
    }
}
