package com.hongguo.rewrite

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.CloudAccountClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountProtocolTest {
    @Test fun sessionSerializationIsRedactedFromDefaultDebugStrings() {
        val credentials = AccountCredentials(
            cookie = "sessionid=TEST_ONLY_SECRET; csrf=TEST",
            deviceId = "12345", installId = "54321",
            device = mapOf("device_id" to "12345", "iid" to "54321"),
        )
        val restored = AccountCredentials.fromJson(credentials.toJson())
        assertEquals(credentials, restored)
        assertFalse(credentials.toString().contains("TEST_ONLY_SECRET"))
        assertFalse(restored.toString().contains("12345"))
    }

    @Test fun serverProgressEpisodeIndexIsZeroBasedAndDeduplicated() {
        val values = JSONArray()
            .put(JSONObject().put("series_id", "70001").put("vid", "80001")
                .put("vid_index", 3).put("current_play_time", 74000L)
                .put("current_total_time", 220000L).put("last_watch_time", 1700000000L))
            .put(JSONObject().put("series_id", "70001").put("vid", "80002")
                .put("vid_index", 4))
        val result = CloudAccountClient.parseProgress(values)
        assertEquals(1, result.size)
        assertEquals(2, result[0].episodeIndex)
        assertEquals("80001", result[0].episodeId)
        assertEquals(74000L, result[0].position)
    }

    @Test fun mainCloudLikesAreReadOnlyParsedAndDeduplicated() {
        val raw = JSONObject().put("data", JSONObject().put("mixed_data_list", JSONArray()
            .put(JSONObject().put("series_title", "示例短剧")
                .put("video_data", JSONObject().put("series_id", "70001")
                    .put("cover_url", "https://example.com/a.jpg")
                    .put("user_digg_timestamp_ms", 1700000001000L)
                    .put("episode_cnt", 24)))
            .put(JSONObject().put("series_title", "重复作品")
                .put("video_data", JSONObject().put("series_id", "70001")
                    .put("user_digg_timestamp_ms", 1700000000000L)))
            .put(JSONObject().put("series_title", "无效作品")
                .put("video_data", JSONObject().put("series_id", "invalid")))))
        val likes = CloudAccountClient.parseLikes(raw)
        assertEquals(1, likes.size)
        assertEquals("70001", likes[0].series.id)
        assertEquals("示例短剧", likes[0].series.title)
        assertEquals(1700000001000L, likes[0].likedAt)
    }

    @Test fun likeProtocolDoesNotMisreportMissingDataAsEmptyAccount() {
        val empty = JSONObject().put("data", JSONObject()
            .put("mixed_data_list", JSONArray()))
        assertTrue(CloudAccountClient.parseLikes(empty).isEmpty())
        var rejected = false
        try {
            CloudAccountClient.parseLikes(JSONObject().put("data", JSONObject()))
        } catch (_: java.io.IOException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun invalidProgressIdsCannotCreatePlayableRows() {
        val bad = JSONArray().put(JSONObject()
            .put("series_id", "not-an-id").put("vid", "not-a-vid"))
        assertTrue(CloudAccountClient.parseProgress(bad).isEmpty())
    }
}
