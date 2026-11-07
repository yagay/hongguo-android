package com.hongguo.rewrite

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWebsiteSourceTest {
    private fun html(json: String) =
        "<html><script>window._ROUTER_DATA = " + json + ";</script></html>"

    @Test fun scannerHandlesNestedQuotesAndEscapes() {
        val root = NativeWebsiteSource.extractRouter(html(
            """{"loaderData":{"searchList":[{"series_id":"123","series_title":"包含 } 文字","series_cover":""}]}}"""
        ))
        assertEquals(1, (NativeWebsiteSource.find(root, "searchList")
            as org.json.JSONArray).length())
    }

    @Test fun websiteSearchWithoutNode() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(html(
                """{"loaderData":{"search_page":{"searchList":[{"video_data":{"series_id":"123","series_title":"测试搜索","series_cover":"","episode_cnt":12}}]}}}"""
            )))
            val source = NativeWebsiteSource(server.url("/"))
            val items = source.search("测试").items
            assertEquals("123", items.single().id)
            assertEquals(12, items.single().total)
            assertEquals("/search/%E6%B5%8B%E8%AF%95", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun websiteEpisodesPreserveOrder() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(html(
                """{"loaderData":{"detail_page":{"seriesDetail":{"series_name":"测试","vid_list":["111","222","333"]}}}}"""
            )))
            val detail = NativeWebsiteSource(server.url("/")).detail("999")
            assertEquals(listOf("111", "222", "333"), detail.episodes.map { it.vid })
            assertEquals(listOf(1, 2, 3), detail.episodes.map { it.index })
        } finally { server.shutdown() }
    }

    @Test fun websiteUnsupportedSectionsFailLoudly() = runBlocking {
        val source = NativeWebsiteSource()
        val error = runCatching { source.list(Section.BROWSE, 0, "") }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
    }

    @Test fun websiteHasNoPlaybackUrls() {
        val source = NativeWebsiteSource()
        assertFalse(source.coverUrl("http://example.com/c.jpg").isNotEmpty())
        assertTrue(source.coverUrl("https://cdn.byteimg.com/x.png").isNotEmpty())
    }
}
