package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TheaterFilterLayoutPolicyTest {
    private val options = listOf(
        FilterOption("", "全部"),
        FilterOption("a", "动作"),
        FilterOption("b", "冒险"),
        FilterOption("c", "奇幻"),
        FilterOption("d", "爱情"),
        FilterOption("e", "家庭"),
        FilterOption("f", "历史"),
        FilterOption("g", "都市"),
    )

    @Test fun initialPreviewIsTwoThreeColumnRows() {
        assertEquals(options.take(6),
            TheaterFilterLayoutPolicy.visibleOptions(options, "", false))
        assertTrue(TheaterFilterLayoutPolicy.hasMore(options))
    }

    @Test fun selectedOptionBeyondPreviewRemainsVisible() {
        val preview = TheaterFilterLayoutPolicy.visibleOptions(options, "g", false)
        assertEquals(6, preview.size)
        assertEquals(options.first(), preview.first())
        assertEquals(options.last(), preview.last())
    }

    @Test fun expandedShowsEveryOptionWithoutChangingBackendOrder() {
        assertEquals(options,
            TheaterFilterLayoutPolicy.visibleOptions(options, "g", true))
    }

    @Test fun shortGroupsHaveNoUnnecessaryExpansion() {
        assertFalse(TheaterFilterLayoutPolicy.hasMore(options.take(4)))
        assertEquals(options.take(4),
            TheaterFilterLayoutPolicy.visibleOptions(options.take(4), "b", false))
    }
}
