package com.yagay.hongguo

import com.hongguotv.core.EmptyPlaybackResponseException
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicEpisodePlaybackSourceTest {
    private fun page() = JSONObject("""
        {"loaderData":{"player_(series_id)/(vid)/page":{
          "isSuccess":true,"series_id":"123","vid":"456",
          "seriesDetail":{"series_id":"123","vid_list":["456","789"],"accessible_episode_cnt":1},
          "req":{"pcLoginRequired":false,"pcAuthenticatedPlayback":false},
          "publicVideoSeo":{"available":true,"seriesId":"123","itemId":"456","durationSeconds":60}
        }}}
    """.trimIndent())
    private fun episode(root: JSONObject) = root.getJSONObject("loaderData")
        .getJSONObject("player_(series_id)/(vid)/page")

    @Test fun acceptsExplicitFullPublicEpisodeOnly() {
        PublicEpisodePlaybackSource.requirePublicEpisode(page(), "123", "456")
        val login = page()
        episode(login).getJSONObject("req").put("pcLoginRequired", true)
        assertThrows(PublicEpisodePlaybackSource.Unavailable::class.java) {
            PublicEpisodePlaybackSource.requirePublicEpisode(login, "123", "456")
        }
        val missing = page()
        episode(missing).remove("req")
        assertThrows(PublicEpisodePlaybackSource.Unavailable::class.java) {
            PublicEpisodePlaybackSource.requirePublicEpisode(missing, "123", "456")
        }
        val limited = page()
        episode(limited).put("vid", "789")
        assertThrows(PublicEpisodePlaybackSource.Unavailable::class.java) {
            PublicEpisodePlaybackSource.requirePublicEpisode(limited, "123", "789")
        }
        assertThrows(PublicEpisodePlaybackSource.Invalid::class.java) {
            PublicEpisodePlaybackSource.requirePublicEpisode(page(), "999", "456")
        }
    }

    @Test fun validatesPlainMediaAndExpiry() {
        val good = "https://cdn.qznovelvod.com/video.mp4?public=1"
        val model = JSONObject().put("code", 0).put("main_url", good).put("url_expire", 2000)
        assertEquals(good, PublicEpisodePlaybackSource.checkedMedia(model, 1000))
        assertThrows(PublicEpisodePlaybackSource.Invalid::class.java) {
            PublicEpisodePlaybackSource.checkedMedia(model, 1998)
        }
        for (bad in listOf("http://cdn.qznovelvod.com/a", "https://qznovelvod.com.evil.invalid/a",
            "https://user@qznovelvod.com/a", "https://qznovelvod.com:444/a",
            "https://qznovelvod.com/a#x")) {
            assertFalse(PublicEpisodePlaybackSource.validMediaUrl(bad))
        }
        model.put("protected", true)
        assertThrows(PublicEpisodePlaybackSource.Unavailable::class.java) {
            PublicEpisodePlaybackSource.checkedMedia(model, 1000)
        }
    }

    @Test fun onlyEmptyModelsMayStartPublicFallback() {
        val empty = OpenhongguoException(ErrorCode.RESPONSE_EMPTY, "empty")
        val legacy = EmptyPlaybackResponseException("empty")
        assertTrue(PlaybackSourceRecoveryPolicy.publicWebsiteFallback(empty, legacy))
        for (code in listOf(ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.RATE_LIMITED,
            ErrorCode.DRM_PROTECTED, ErrorCode.DEVICE_REJECTED, ErrorCode.SIGNATURE_REJECTED)) {
            val refusal = OpenhongguoException(code, "denied")
            assertFalse(PlaybackSourceRecoveryPolicy.publicWebsiteFallback(refusal, legacy))
            assertFalse(PlaybackSourceRecoveryPolicy.publicWebsiteFallback(empty, refusal))
        }
        assertFalse(PeriodicPlaybackRetryPolicy.recoverableSource(PublicEpisodePlaybackSource.Unavailable()))
        assertFalse(PeriodicPlaybackRetryPolicy.recoverableSource(PublicEpisodePlaybackSource.Limited()))
    }
}
