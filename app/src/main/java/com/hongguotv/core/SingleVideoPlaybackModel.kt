// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, shape-validated alternative to the multi_video_model response.
 * The single-video App endpoint is a last-resort fallback, not a substitute
 * for the preferred signed video_tab request. Never select another VID.
 */
internal object SingleVideoPlaybackModel {
    fun parse(body: String): JSONObject? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("code", root.optInt("Code", -1)) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val raw = data.opt("video_model")
        val model = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: if (data.has("video_list")) data else return null
        val list = model.opt("video_list")
        if (list is JSONObject) {
            val normalized = JSONArray()
            list.keys().asSequence().forEach { key ->
                model.optJSONObject("video_list")?.optJSONObject(key)
                    ?.let { normalized.put(it) }
            }
            model.put("video_list", normalized)
        }
        val hasRenditions = model.optJSONArray("video_list")?.length()?.let { it > 0 } == true
        if (!hasRenditions && !model.has("fallback_api")) return null
        return JSONObject().put("video_model", model)
    }
}
