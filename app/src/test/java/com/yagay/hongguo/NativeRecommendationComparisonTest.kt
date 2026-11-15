package com.yagay.hongguo

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DeviceRegister
import com.hongguotv.core.FeedAccount
import com.hongguotv.core.PlaybackDeviceSession
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only recommendation A/B probes must use the live signed-account protocol
 * (not the older Cookie-only comparison), with anonymous controls isolated.
 */
class NativeRecommendationComparisonTest {
    private fun tab(vararg ids: String): String {
        val videos = JSONArray()
        ids.forEach { id ->
            videos.put(JSONObject().put("series_id", id)
                .put("series_title", "Fixture " + id).put("episode_cnt", 8))
        }
        val cell = JSONObject().put("cell_data",
            JSONArray().put(JSONObject().put("video_data", videos)))
        val tab = JSONObject().put("tab_type", 16)
            .put("session_id", "test-session")
            .put("cell_data", JSONArray().put(cell))
        return JSONObject().put("code", 0)
            .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
            .toString()
    }

    private fun response(request: Request, accountIndex: Int, guestIndex: Int): String =
        if (request.header("Cookie") == "sessionid=account-cookie") {
            if (accountIndex == 1) tab("101", "102", "103")
            else tab("101", "102", "106")
        } else {
            if (guestIndex == 1) tab("101", "104", "105")
            else tab("104", "105", "107")
        }

    @Test fun twoSignedAccountProbesUseSameDeviceWithoutLeakingCookiesToGuests() = runBlocking {
        val requests = mutableListOf<Request>()
        var accountIndex = 0
        var guestIndex = 0
        var registrations = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val content = if (request.header("Cookie") == "sessionid=account-cookie") {
                accountIndex++
                response(request, accountIndex, guestIndex)
            } else {
                guestIndex++
                response(request, accountIndex, guestIndex)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(content.toResponseBody()).build()
        }.build()
        val device = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444",
                cookies = mapOf("install_id" to "444"))
        }
        val source = NativeHongguoSource(repository = ContentRepository(http),
            recommendationDeviceSession = device)
        source.setAccountRecommendations(FeedAccount("sessionid=account-cookie"))
        val comparison = source.comparePersonalizationSamples()

        assertEquals(4, requests.size)
        assertEquals(1, registrations)
        assertEquals(2, comparison.signedAccountSamples)
        assertEquals(3, comparison.accountCount)
        assertEquals(3, comparison.secondAccountCount)
        assertEquals(1, comparison.overlapCount)
        assertEquals(0, comparison.secondAccountGuestOverlap)
        assertEquals(2, comparison.accountBaselineOverlap)
        assertEquals(2, comparison.anonymousBaselineOverlap)
        assertFalse(comparison.identicalOrder)
        assertFalse(comparison.secondAccountGuestIdenticalOrder)
        assertFalse(comparison.accountBaselineIdenticalOrder)
        assertFalse(comparison.anonymousBaselineIdenticalOrder)

        listOf(0, 2).forEach { index ->
            assertEquals("sessionid=account-cookie", requests[index].header("Cookie"))
            assertNotNull(requests[index].header("x-medusa"))
            assertEquals("333", requests[index].url.queryParameter("device_id"))
        }
        listOf(1, 3).forEach { index ->
            assertNull(requests[index].header("Cookie"))
            assertNull(requests[index].header("x-medusa"))
            assertNull(requests[index].url.queryParameter("device_id"))
        }
    }

    @Test fun explicit110FallbackIsCountedAsCookieOnlyInsteadOfSignedSuccess() = runBlocking {
        val requests = mutableListOf<Request>()
        var rejected = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val isAccount = request.header("Cookie") == "sessionid=account-cookie"
            val payload = if (isAccount && request.header("x-medusa") != null && !rejected) {
                rejected = true
                """{"code":110}"""
            } else if (isAccount) {
                tab("101", "102")
            } else {
                tab("103", "104")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        var registrations = 0
        val device = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444")
        }
        val source = NativeHongguoSource(repository = ContentRepository(http),
            recommendationDeviceSession = device)
        source.setAccountRecommendations(FeedAccount("sessionid=account-cookie"))
        val comparison = source.comparePersonalizationSamples()

        assertTrue(rejected)
        assertEquals(1, registrations)
        assertEquals(1, comparison.signedAccountSamples)
        assertEquals(5, requests.size)
        assertNotNull(requests[0].header("x-medusa"))
        assertNull(requests[1].header("x-medusa"))
        assertEquals("sessionid=account-cookie", requests[1].header("Cookie"))
        assertNull(requests[2].header("Cookie"))
        assertNotNull(requests[3].header("x-medusa"))
        assertNull(requests[4].header("Cookie"))
    }
}
