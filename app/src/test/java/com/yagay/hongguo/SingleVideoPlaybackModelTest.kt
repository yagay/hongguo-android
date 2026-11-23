package com.yagay.hongguo

import com.hongguotv.core.SingleVideoPlaybackModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SingleVideoPlaybackModelTest {
    private val wanted = "7100000000000201"
    private val wrong = "7100000000000202"

    private fun body(dataId: String?, modelId: String?): String {
        val model = JSONObject().put("video_list", JSONArray().put(
            JSONObject().put("definition", "720p")))
        if (modelId != null) model.put("video_id", modelId)
        val data = JSONObject().put("video_model", model)
        if (dataId != null) data.put("video_id", dataId)
        return JSONObject().put("code", 0).put("data", data).toString()
    }

    @Test fun acceptsRequestedEpisode() {
        assertNotNull(SingleVideoPlaybackModel.parse(body(wanted, wanted), wanted))
    }

    @Test fun rejectsDataLevelMismatch() {
        assertNull(SingleVideoPlaybackModel.parse(body(wrong, wanted), wanted))
    }

    @Test fun rejectsNestedModelMismatch() {
        assertNull(SingleVideoPlaybackModel.parse(body(wanted, wrong), wanted))
    }

    @Test fun acceptsRequestScopedSingleResponseWithoutEchoedIds() {
        assertNotNull(SingleVideoPlaybackModel.parse(body(null, null), wanted))
    }

    @Test fun rejectsInvalidIdAndBusinessError() {
        assertNull(SingleVideoPlaybackModel.parse(body(null, null), "not-id"))
        val bad = JSONObject(body(wanted, wanted)).put("code", 401).toString()
        assertNull(SingleVideoPlaybackModel.parse(bad, wanted))
    }
}
