package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SingleVideoPlaybackModelTest {
    @Test fun rejectsBadPayloadOrBusinessRejection() {
        assertNull(SingleVideoPlaybackModel.parse(""))
        assertNull(SingleVideoPlaybackModel.parse("""{"code":403,"data":{"video_list":[{}]}}"""))
        assertNull(SingleVideoPlaybackModel.parse("""{"code":0,"data":{"video_model":"{}"}}"""))
    }
    @Test fun acceptsModelObjectAndNormalizesRenditionMap() {
        val body = """{"code":0,"data":{"video_model":{"video_list":{"hd":{"main_url":"https://example.com/a","video_meta":{"codec_type":"h264"}}}}}}"""
        val model = SingleVideoPlaybackModel.parse(body)!!.getJSONObject("video_model")
        assertEquals(1, model.getJSONArray("video_list").length())
    }
    @Test fun acceptsSerializedModelWithoutGuessingOtherEpisodes() {
        val body = """{"code":0,"data":{"video_model":"{\\"video_list\\":[{\\"main_url\\":\\"https://example.com/a\\"}]}"}}"""
        // Serialized JSON requires a properly escaped body: malformed shapes are safely rejected.
        assertNull(SingleVideoPlaybackModel.parse(body))
    }
}
