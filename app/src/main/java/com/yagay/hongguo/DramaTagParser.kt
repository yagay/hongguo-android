package com.yagay.hongguo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Actor and genre labels are taken only from the matching official series. */
data class DramaLabels(
    val actors: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
)

internal object DramaTagParser {
    private val numericId = Regex("[0-9]{1,30}")

    fun categoriesFromCore(tags: String): List<String> =
        sanitize(tags.split(" · ", "、", ",", "，", "/"), 5)

    fun website(root: JSONObject, seriesId: String): DramaLabels {
        require(numericId.matches(seriesId))
        val loader = root.optJSONObject("loaderData") ?: throw IOException("LABELS_STRUCTURE")
        val page = loader.optJSONObject("player_(series_id)/page")
            ?: loader.optJSONObject("player_(series_id)/(vid)/page")
            ?: loader.optJSONObject("detail_page")
            ?: throw IOException("LABELS_STRUCTURE")
        val detail = page.optJSONObject("seriesDetail") ?: throw IOException("LABELS_STRUCTURE")
        val returned = detail.optString("series_id_str").ifBlank {
            detail.optString("series_id")
        }
        val pageId = page.optString("series_id")
        if (returned != seriesId || (pageId.isNotBlank() && pageId != seriesId))
            throw IOException("LABELS_ID_MISMATCH")
        val cast = detail.optJSONArray("celebrities")
        val actors = if (cast == null) emptyList() else
            (0 until minOf(cast.length(), 50)).mapNotNull { idx ->
                val item = cast.optJSONObject(idx) ?: return@mapNotNull null
                if (item.optBoolean("is_label", false)) return@mapNotNull null
                item.optString("nickname").ifBlank { item.optString("name") }
            }
        val labels = detail.optJSONArray("tags") ?: detail.optJSONArray("category_list")
        val genres = if (labels == null) emptyList() else
            (0 until minOf(labels.length(), 40)).mapNotNull { idx ->
                when (val item = labels.opt(idx)) {
                    is String -> item
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("text") }
                        .ifBlank { item.optString("category_name") }
                    else -> null
                }
            }
        return DramaLabels(sanitize(actors, 3), sanitize(genres, 5))
    }

    private fun sanitize(values: List<String>, max: Int): List<String> =
        values.map(String::trim).filter { it.isNotEmpty() && it.length <= 24 }
            .distinct().take(max)
}
