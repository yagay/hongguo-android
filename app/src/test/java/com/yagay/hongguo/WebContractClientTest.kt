package com.yagay.hongguo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebContractClientTest {
    @Test fun rejectPublicHttp() {
        val failure = runCatching { WebContractClient.normalizedBase("http://example.org") }
        assertTrue(failure.isFailure)
    }

    @Test fun rejectCredentials() {
        val failure = runCatching { WebContractClient.normalizedBase("https://user:pass@example.org") }
        assertTrue(failure.isFailure)
    }

    @Test fun rankMapsToUnifiedDrama() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"api_key":"hg_testing"}"""))
            server.enqueue(MockResponse().setBody(
                """{"items":[{"series_id":"12345","title":"测试短剧","cover":"", "episode_cnt":30}]}"""
            ))
            val client = WebContractClient(server.url("/").toString())
            val result = client.list(Section.RECOMMEND, 0, "short_play", "hot",
                "", emptyMap(), emptyList())
            assertEquals(1, result.items.size)
            assertEquals("12345", result.items.first().id)
            assertEquals(30, result.items.first().total)
            assertFalse(result.hasMore)
            assertEquals("/api-key", server.takeRequest().path)
            val request = server.takeRequest()
            assertTrue(request.path!!.startsWith("/rank?"))
            assertEquals("hg_testing", request.getHeader("x-api-key"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun episodesPreserveActualEpisodeNumbers() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"api_key":"hg_testing"}"""))
            server.enqueue(MockResponse().setBody(
                """{"meta":{"title":"试验","cover":""},"episodes":[{"vid":"9876","index":42},{"vid":"9877","index":43}]}"""
            ))
            val result = WebContractClient(server.url("/").toString()).detail("12345")
            assertEquals(listOf(42, 43), result.episodes.map { it.index })
            assertEquals("9876", result.episodes.first().vid)
        } finally {
            server.shutdown()
        }
    }

    @Test fun emptyHttpBodyIsAnError() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"api_key":"hg_testing"}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
            val error = runCatching {
                WebContractClient(server.url("/").toString()).detail("12345")
            }.exceptionOrNull()
            assertTrue(error?.message?.startsWith("EMPTY_RESPONSE") == true)
        } finally {
            server.shutdown()
        }
    }
}
