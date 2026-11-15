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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: account validation can finish after app restores a series.
 * A subsequent swipe/finale request uses restart=false, and must not reuse
 * the anonymous feed that NativeHongguoSource built at construction time.
 */
class NativeReelAccountIdentityTest {
    private fun response(id: String): String {
        val video = JSONObject()
            .put("series_id", id)
            .put("series_title", "Recommendation fixture")
            .put("episode_cnt", 8)
        val cell = JSONObject().put("cell_data",
            JSONArray().put(JSONObject().put("video_data", JSONArray().put(video))))
        val tab = JSONObject().put("tab_type", 16)
            .put("session_id", "fixture-session")
            .put("cell_data", JSONArray().put(cell))
        return JSONObject().put("code", 0)
            .put("data", JSONObject().put("tab_item", JSONArray().put(tab)))
            .toString()
    }

    private data class Fixture(
        val source: NativeHongguoSource,
        val requests: List<Request>,
        val registrations: () -> Int,
    )

    private fun fixture(): Fixture {
        val requests = mutableListOf<Request>()
        var registrations = 0
        var count = 100
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(requests) { requests.add(request) }
            Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response((++count).toString()).toResponseBody()).build()
        }.build()
        val registered = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444",
                cookies = mapOf("install_id" to "444"))
        }
        return Fixture(
            NativeHongguoSource(repository = ContentRepository(http),
                recommendationDeviceSession = registered),
            requests, { registrations })
    }

    @Test fun loggedInContinuationRebindsGuestCursorWithoutRestart() = runBlocking {
        val f = fixture()
        val guest = f.source.officialReelsPage(restart = true, firstItemOnly = true)
        assertTrue(guest.items.isNotEmpty())
        assertNull(f.requests.last().header("Cookie"))

        f.source.setAccountRecommendations(FeedAccount("sessionid=account-cookie"))
        val account = f.source.officialReelsPage(restart = false, firstItemOnly = true)
        assertTrue(account.items.isNotEmpty())
        assertEquals(ReelRecommendationOrigin.ACCOUNT_DYNAMIC,
            account.itemAttributions[account.items.first().id]?.origin)
        assertEquals(1, f.registrations())
        assertEquals("sessionid=account-cookie", f.requests.last().header("Cookie"))
        assertNotNull(f.requests.last().header("x-medusa"))
        assertEquals("333", f.requests.last().url.queryParameter("device_id"))
    }

    @Test fun logoutContinuationDropsOldCookieAndSignatureWithoutRestart() = runBlocking {
        val f = fixture()
        f.source.setAccountRecommendations(FeedAccount("sessionid=account-cookie"))
        val account = f.source.officialReelsPage(restart = false, firstItemOnly = true)
        assertTrue(account.items.isNotEmpty())
        assertEquals("sessionid=account-cookie", f.requests.last().header("Cookie"))
        assertNotNull(f.requests.last().header("x-medusa"))

        f.source.setAccountRecommendations(null)
        val guest = f.source.officialReelsPage(restart = false, firstItemOnly = true)
        assertTrue(guest.items.isNotEmpty())
        assertEquals(ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_DYNAMIC,
            guest.itemAttributions[guest.items.first().id]?.origin)
        assertNull(f.requests.last().header("Cookie"))
        assertNull(f.requests.last().header("x-medusa"))
        assertNull(f.requests.last().url.queryParameter("device_id"))
    }

    @Test fun firstRequestAfterAccountOptInDoesNotNeedExplicitRestart() = runBlocking {
        val f = fixture()
        f.source.setAccountRecommendations(FeedAccount("sessionid=account-cookie"))
        val page = f.source.officialReelsPage(firstItemOnly = true)
        assertTrue(page.items.isNotEmpty())
        assertEquals("sessionid=account-cookie", f.requests.last().header("Cookie"))
        assertNotNull(f.requests.last().header("x-medusa"))
    }
}
