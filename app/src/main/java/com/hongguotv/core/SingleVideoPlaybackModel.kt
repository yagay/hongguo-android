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
            is String -> {
                // Some App builds return a JSON string, others base64-encode
                // the same model. Reject oversized strings before decoding.
                if (raw.length > 384 * 1024) null
                else runCatching { JSONObject(raw) }.getOrNull()
                    ?: runCatching {
                        val decoded = java.util.Base64.getDecoder().decode(raw)
                        if (decoded.size > 256 * 1024) null
                        else JSONObject(String(decoded, Charsets.UTF_8))
                    }.getOrNull()
            }
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
