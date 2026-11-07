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

    @Test fun invalidProgressIdsCannotCreatePlayableRows() {
        val bad = JSONArray().put(JSONObject()
            .put("series_id", "not-an-id").put("vid", "not-a-vid"))
        assertTrue(CloudAccountClient.parseProgress(bad).isEmpty())
    }
}
