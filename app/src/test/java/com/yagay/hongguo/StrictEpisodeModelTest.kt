package com.yagay.hongguo

import com.hongguotv.core.StrictEpisodeModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrictEpisodeModelTest {
    private val wanted = "7100000000000201"
    private val different = "7100000000000202"

    @Test fun findsDirectRequestedEpisode() {
        val root = JSONObject().put(wanted, JSONObject().put("video_model", "model"))
        assertEquals("model", StrictEpisodeModel.find(root, wanted)?.optString("video_model"))
    }

    @Test fun findsRequestedEpisodeUnderOfficialMultiVideoBucket() {
        val row = JSONObject().put("video_model", "bucket-model")
        val root = JSONObject().put("data", JSONObject().put("1004",
            JSONObject().put(wanted, row)))
        assertEquals("bucket-model", StrictEpisodeModel.find(root, wanted)?.optString("video_model"))
    }

    @Test fun findsRequestedEpisodeInVideoTabEnvelope() {
        val root = JSONObject().put("video_model_data",
            JSONObject().put(wanted, JSONObject().put("video_model", "tab-model")))
        assertEquals("tab-model", StrictEpisodeModel.find(root, wanted)?.optString("video_model"))
    }

    @Test fun rejectsOtherEpisodeWithoutSelectingFirstMapValue() {
        val root = JSONObject().put(different,
            JSONObject().put("video_model", "wrong-episode"))
        assertNull(StrictEpisodeModel.find(root, wanted))
    }

    @Test fun rejectsUnknownBucketsAndInvalidIds() {
        val root = JSONObject().put("arbitrary_bucket", JSONObject().put(wanted, JSONObject()))
        assertNull(StrictEpisodeModel.find(root, wanted))
        assertNull(StrictEpisodeModel.find(JSONObject(), "bad-id"))
    }
}
