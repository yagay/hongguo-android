package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account-cookie and registered-device identities are different things:
 * signed dynamic requests use both, and only code 110 / unavailable registration
 * may downgrade to the account's original Cookie-only request.
 */
class SignedAccountRecommendationTest {
    private fun officialTab(id: String): String {
        val video = JSONObject().put("series_id", id)
            .put("series_title", "Fixture title").put("episode_cnt", 8)
        val cell = JSONObject().put("cell_data",
            JSONArray().put(JSONObject().put("video_data", JSONArray().put(video))))
        val tab = JSONObject().put("tab_type", 16)
            .put("session_id", "fixture-session")
            .put("cell_data", JSONArray().put(cell))
        return JSONObject().put("code", 0).put("data",
            JSONObject().put("tab_item", JSONArray().put(tab))).toString()
    }

    private fun client(
        requests: MutableList<Request>,
        response: (Int) -> String,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.add(request)
        Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(response(requests.size).toResponseBody()).build()
    }.build()

    @Test fun signedAccountFeedUsesSharedDeviceIdentityWithoutReplacingAccountCookie() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444",
                cookies = mapOf("install_id" to "444"))
        }
        val requests = mutableListOf<Request>()
        val summaries = mutableListOf<FeedResponseSummary>()
        val feed = RecommendationFeed(client(requests) { officialTab("101") },
            account = FeedAccount("sessionid=test-account"),
            playbackSession = session, onSummary = summaries::add)
        assertEquals("101", feed.page(1).items.single().id)
        assertEquals(1, registrations)
        assertEquals(1, requests.size)
        assertNotNull(requests[0].header("x-medusa"))
        assertEquals("333", requests[0].url.queryParameter("device_id"))
        assertEquals("444", requests[0].url.queryParameter("iid"))
        assertEquals("74332", requests[0].url.queryParameter("version_code"))
        assertEquals("sessionid=test-account", requests[0].header("Cookie"))
        assertTrue(summaries.any { it.signedDeviceIdentity && it.hadAccountCookie &&
            it.businessCode == 0 && it.freshCount == 1 })
    }

    @Test fun onlyExplicitCode110CanDowngradeToCookieOnlyAccountFeed() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444")
        }
        val requests = mutableListOf<Request>()
        val http = client(requests) { count ->
            if (count == 1) """{"code":110}""" else officialTab("102")
        }
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(http, account = FeedAccount("sessionid=test-account"),
            playbackSession = session, cursor = cursor)
        assertEquals("102", feed.page(1).items.single().id)
        assertEquals(1, registrations)
        assertEquals(2, requests.size)
        assertNotNull(requests[0].header("x-medusa"))
        assertNull(requests[1].header("x-medusa"))
        assertNull(requests[1].url.queryParameter("device_id"))
        assertTrue(cursor.unsignedFallback)
        requests.forEach { assertEquals("sessionid=test-account", it.header("Cookie")) }
    }

    @Test fun unavailableRegistrationStillReadsWithAccountCookieWithoutGuestSubstitution() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            null
        }
        val requests = mutableListOf<Request>()
        val cursor = RecommendationCursor()
        val feed = RecommendationFeed(client(requests) { officialTab("103") },
            account = FeedAccount("sessionid=test-account"), cursor = cursor,
            playbackSession = session)
        assertEquals("103", feed.page(1).items.single().id)
        assertEquals(1, registrations)
        assertEquals(1, requests.size)
        assertNull(requests[0].header("x-medusa"))
        assertEquals("sessionid=test-account", requests[0].header("Cookie"))
        assertTrue(cursor.unsignedFallback)
    }

    @Test fun categoryRequestsNeverStartAnonymousPlaybackRegistration() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444")
        }
        val requests = mutableListOf<Request>()
        val category = JSONObject().put("code", 0).put("data",
            JSONObject().put("video_data", JSONArray().put(JSONObject()
                .put("series_id", "104").put("series_title", "Category title"))))
            .toString()
        val feed = RecommendationFeed(client(requests) { category },
            account = FeedAccount("sessionid=test-account"),
            playbackSession = session, category = DramaCategory.REAL)
        assertEquals("104", feed.page(1).items.single().id)
        assertEquals(0, registrations)
        assertEquals("sessionid=test-account", requests.single().header("Cookie"))
        assertNull(requests.single().url.queryParameter("device_id"))
    }
}
