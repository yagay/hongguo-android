package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialCountFormatterTest {
    @Test fun compactActualCommentTotals() {
        assertEquals("0", SocialCountFormatter.format(0))
        assertEquals("1075", SocialCountFormatter.format(1075))
        assertEquals("1.0万", SocialCountFormatter.format(10_000))
        assertEquals("37.8万", SocialCountFormatter.format(378_000))
        assertEquals("1.2亿", SocialCountFormatter.format(123_400_000))
    }

    @Test fun unavailableIsNotZero() {
        assertEquals("—", SocialCountFormatter.format(-1))
        val unknown: Long? = null
        assertNull(unknown)
    }

    @Test fun parserPreservesAppReportedTotal() {
        val page = CommentsPage(emptyList(), total = 12345)
        assertEquals(12345L, page.total)
    }
}
