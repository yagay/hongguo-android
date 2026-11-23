// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * Strict episode identity gate shared by native video_tab / multi-video
 * responses and the ahead-of-time model cache.
 *
 * Never choose the first row in a JSON map just because the desired episode
 * is absent. Those rows can describe another episode or a content-type bucket.
 * Only the requested ID, or the requested ID under a recognized model bucket,
 * can produce playable data.
 */
internal object StrictEpisodeModel {
    private const val MAX_NESTING = 4

    fun find(container: JSONObject?, episodeId: String): JSONObject? {
        if (container == null || !episodeId.matches(Regex("[0-9]{1,30}"))) return null
        return findBounded(container, episodeId, 0)
    }

    private fun findBounded(node: JSONObject, id: String, depth: Int): JSONObject? {
        if (depth > MAX_NESTING) return null
        node.optJSONObject(id)?.let { return it }
        for (key in listOf("video_model_data", "1004", "data")) {
            val nested = node.optJSONObject(key) ?: continue
            findBounded(nested, id, depth + 1)?.let { return it }
        }
        return null
    }
}
