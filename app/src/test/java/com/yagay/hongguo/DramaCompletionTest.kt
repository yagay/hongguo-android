package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DramaCompletionTest {
    @Test fun doesNotInferCompletionFromEpisodeCount() {
        assertEquals("全80集 · 第8集", DramaCompletion.episodeLabel(80, null, 8))
        assertNull(DramaCompletion.fromOfficialText("全80集"))
        assertNull(DramaCompletion.fromOfficialText("未完结"))
    }

    @Test fun exactOrderRequestedByPlayer() {
        assertEquals("全80集 · 已完结 · 第8集",
            DramaCompletion.episodeLabel(80, "已完结", 8))
        assertEquals("全26集 · 更新中 · 第6集",
            DramaCompletion.episodeLabel(26, "更新中", 6))
        assertEquals("全80集", DramaCompletion.episodeLabel(80, null, null))
    }

    @Test fun officialAppSeriesStatusIsDistinctFromNovelCreationStatus() {
        assertEquals("已完结", DramaCompletion.fromSeriesStatus(1))
        assertEquals("更新中", DramaCompletion.fromSeriesStatus(0))
        assertEquals("已完结", DramaCompletion.fromSeriesStatus("1"))
        assertNull(DramaCompletion.fromSeriesStatus(3))
        assertNull(DramaCompletion.fromSeriesStatus(null))
    }

    @Test fun onlyOfficialTextProvidesStatus() {
        assertEquals("已完结", DramaCompletion.fromOfficialText("全80集 · 已完结"))
        assertEquals("已完结", DramaCompletion.fromOfficialText("完结"))
        assertEquals("更新中", DramaCompletion.fromOfficialText("连载中"))
        assertNull(DramaCompletion.fromOfficialText(""))
    }
}
