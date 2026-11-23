package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SingleVideoPlaybackModelTest {
    private val requestedId = "7100000000000201"
    private fun parse(body: String) = SingleVideoPlaybackModel.parse(body, requestedId)

    @Test fun rejectsBadPayloadOrBusinessRejection() {
        assertNull(parse(""))
        assertNull(parse("""{"code":403,"data":{"video_list":[{}]}}"""))
        assertNull(parse("""{"code":0,"data":{"video_model":"{}"}}"""))
    }
    @Test fun acceptsModelObjectAndNormalizesRenditionMap() {
        val body = """{"code":0,"data":{"video_model":{"video_list":{"hd":{"main_url":"https://example.com/a","video_meta":{"codec_type":"h264"}}}}}}"""
        val model = parse(body)!!.getJSONObject("video_model")
        assertEquals(1, model.getJSONArray("video_list").length())
    }
    @Test fun acceptsSerializedAndBase64AppModels() {
        val model = org.json.JSONObject().put("video_list", org.json.JSONArray()
            .put(org.json.JSONObject().put("main_url", "https://example.com/video")))
        val data = org.json.JSONObject().put("video_model", model.toString())
        val root = org.json.JSONObject().put("code", 0).put("data", data)
        assertEquals(1, parse(root.toString())!!
            .getJSONObject("video_model").getJSONArray("video_list").length())
        data.put("video_model", java.util.Base64.getEncoder().encodeToString(
            model.toString().toByteArray(Charsets.UTF_8)))
        assertEquals(1, parse(root.toString())!!
            .getJSONObject("video_model").getJSONArray("video_list").length())
    }

    @Test fun rejectsInvalidSerializedModel() {
        val body = """{"code":0,"data":{"video_model":"garbled"}}"""
        assertNull(parse(body))
    }
}
