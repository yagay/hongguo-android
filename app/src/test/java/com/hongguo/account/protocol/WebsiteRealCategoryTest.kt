// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All fixtures are synthetic. Network tests use only an in-process MockWebServer. */
class WebsiteRealCategoryTest {
    private fun siteHtml(topic: String = "", page: Int = 1, totalPages: Int = 3,
                         contentType: String = "real-drama", enabled: Boolean = true): String {
        val route = "/category/real-drama" + if (topic.isBlank()) "" else "/" + topic
        val topics = JSONArray()
            .put(JSONObject().put("selector_item_id", "romance").put("show_name", "爱情"))
            .put(JSONObject().put("selector_item_id", "urban").put("show_name", "都市"))
            .put(JSONObject().put("selector_item_id", "../invalid").put("show_name", "不安全路径"))
        val rows = JSONArray()
            .put(JSONObject().put("series_id", (100 + page).toString())
                .put("series_name", "第" + page + "页真人剧")
                .put("series_cover", "https://example.invalid/cover.png")
                .put("series_intro", "都市 {爱情}")
                .put("episode_cnt", 24))
            .put(JSONObject().put("video_data",
                JSONObject().put("series_id", (200 + page).toString())
                    .put("series_title", "另一部")
                    .put("series_episode_info", JSONObject().put("episode_cnt", 66))))
            .put(JSONObject().put("series_id", "not-a-number").put("series_name", "无效作品"))
        val section = JSONObject()
            .put("isSuccess", true)
            .put("categoryRoute", JSONObject()
                .put("contentType", contentType).put("enabled", enabled)
                .put("canonicalPath", route))
            .put("pagination", JSONObject()
                .put("pageNum", page).put("totalPages", totalPages)
                .put("pageSize", 24).put("total", totalPages * 24))
            .put("selectorList", JSONArray().put(JSONObject().put("items", topics)))
            .put("recommendList", rows)
        return "<script>window._ROUTER_DATA=" +
            JSONObject().put("loaderData", JSONObject().put("category_$", section)).toString() +
            ";</script>"
    }

    @Test fun pageResultsUseActualWebsitePaginationAndTopicIds() {
        val first = WebsiteRealCategory.parse(siteHtml(), "", 1)
        assertEquals(listOf("101", "201"), first.entries.map { it.series.id })
        assertEquals("都市 {爱情}", first.entries.first().series.description)
        assertEquals(24, first.entries[0].episodeCount)
        assertEquals(66, first.entries[1].episodeCount)
        assertEquals(listOf("romance", "urban"), first.topics.map { it.id })
        assertTrue(first.hasMore)
        val last = WebsiteRealCategory.parse(siteHtml(topic = "romance", page = 3), "romance", 3)
        assertEquals("第3页真人剧", last.entries.first().series.title)
        assertFalse(last.hasMore)
    }

    @Test fun rejectsUnconfirmedGenreTopicAndPageNumber() {
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse(siteHtml(contentType = "comic-drama"), "", 1)
        }
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse(siteHtml(enabled = false), "", 1)
        }
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse(siteHtml(topic = "romance"), "urban", 1)
        }
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse(siteHtml(page = 2), "", 1)
        }
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse(siteHtml(totalPages = 1, page = 2), "", 2)
        }
        assertThrows(AccountProtocolException::class.java) {
            WebsiteRealCategory.parse("<html>no data</html>", "", 1)
        }
        assertThrows(IllegalArgumentException::class.java) { WebsiteRealCategory.path("../rank") }
        assertThrows(IllegalArgumentException::class.java) { WebsiteRealCategory.path("romance?sort=1") }
    }

    @Test fun categoryRequestDoesNotSendCookiesAndUsesServerPageNumbers() {
        val server = MockWebServer()
        val http = OkHttpClient()
        server.start()
        try {
            val client = WebsiteCatalogClient(http, server.url("/"))
            server.enqueue(MockResponse().setBody(siteHtml()))
            val first = client.realCategory("", 1)
            assertEquals(1, first.page)
            val firstRequest = server.takeRequest()
            assertEquals("/category/real-drama", firstRequest.path)
            assertNull(firstRequest.getHeader("Cookie"))

            server.enqueue(MockResponse().setBody(siteHtml(topic = "romance", page = 2)))
            val next = client.realCategory("romance", 2)
            assertEquals(2, next.page)
            val nextRequest = server.takeRequest()
            assertEquals("/category/real-drama/romance?page=2", nextRequest.path)
            assertNull(nextRequest.getHeader("Cookie"))
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    @Test fun quotedTextAndBracesCannotTruncateTheEmbeddedRouterJson() {
        val raw = siteHtml().replace("都市 {爱情}", "看到 {测试} 后续")
        val parsed = WebsiteRealCategory.parse(raw, "", 1)
        assertTrue(parsed.entries.first().series.description.contains("测试"))
    }
}
