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
        FilterOption("h", "悬疑"),
        FilterOption("i", "喜剧"),
        FilterOption("j", "科幻"),
        FilterOption("k", "青春"),
    )

    @Test fun initialPreviewIsTwoFiveColumnRows() {
        assertEquals(5, TheaterFilterLayoutPolicy.COLUMNS)
        assertEquals(10, TheaterFilterLayoutPolicy.PREVIEW_COUNT)
        assertEquals(options.take(10),
            TheaterFilterLayoutPolicy.visibleOptions(options, "", false))
        assertTrue(TheaterFilterLayoutPolicy.hasMore(options))
    }

    @Test fun selectedOptionBeyondPreviewRemainsVisible() {
        val preview = TheaterFilterLayoutPolicy.visibleOptions(options, "k", false)
        assertEquals(10, preview.size)
        assertEquals(options.first(), preview.first())
        assertEquals(options.last(), preview.last())
    }

    @Test fun expandedShowsEveryOptionWithoutChangingBackendOrder() {
        assertEquals(options,
            TheaterFilterLayoutPolicy.visibleOptions(options, "g", true))
    }

    @Test fun shortGroupsHaveNoUnnecessaryExpansion() {
        assertFalse(TheaterFilterLayoutPolicy.hasMore(options.take(9)))
        assertEquals(options.take(9),
            TheaterFilterLayoutPolicy.visibleOptions(options.take(9), "b", false))
        assertFalse(TheaterFilterLayoutPolicy.hasMore(options.take(10)))
        assertEquals(options.take(10),
            TheaterFilterLayoutPolicy.visibleOptions(options.take(10), "i", false))
    }
}
