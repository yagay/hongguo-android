package com.hongguo.rewrite

import com.hongguotv.core.DramaCategory
import com.hongguotv.core.RankBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHongguoSourceTest {
    @Test fun nativeBoardsMapToRealRankNotComicAlias() {
        assertEquals(RankBoard.ALL, NativeHongguoSource.boardFor("all"))
        assertEquals(RankBoard.REAL, NativeHongguoSource.boardFor("real"))
        assertEquals(RankBoard.COMIC, NativeHongguoSource.boardFor("comic"))
        assertEquals(RankBoard.AI, NativeHongguoSource.boardFor("ai"))
    }

    @Test fun nativeGenresRemainSeparate() {
        assertEquals(DramaCategory.REAL, NativeHongguoSource.categoryFor("short_play"))
        assertEquals(DramaCategory.COMIC, NativeHongguoSource.categoryFor("comic_series"))
        assertEquals(DramaCategory.AI, NativeHongguoSource.categoryFor("ai_series"))
    }

    @Test fun shareResolverDoesNotAcceptForeignDomains() {
        val source = NativeHongguoSource()
        val result = source.resolveShare("红果 https://hongguoduanju.com/player/123456789/2")
        assertEquals("123456789", result.first)
        assertEquals(2, result.second)
        val error = runCatching {
            source.resolveShare("https://evil.example.com/player/123456789")
        }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }
}
