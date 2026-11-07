package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialReelsTypeTest {
    @Test fun acceptsOnlyExplicitRealPersonMediaTypes() {
        assertTrue(OfficialReelsType.isReal("short_play"))
        assertTrue(OfficialReelsType.isReal("live_action"))
        assertTrue(OfficialReelsType.isReal("genre=unknown,real_drama"))
    }

    @Test fun unknownOrStoryGenresDoNotBecomeRealPersonProof() {
        assertFalse(OfficialReelsType.isReal(""))
        assertFalse(OfficialReelsType.isReal("都市,爱情,古装"))
        assertFalse(OfficialReelsType.isReal("2"))
        assertFalse(OfficialReelsType.isReal("ai_series"))
        assertFalse(OfficialReelsType.isReal("comic_series,short_play"))
    }
}
