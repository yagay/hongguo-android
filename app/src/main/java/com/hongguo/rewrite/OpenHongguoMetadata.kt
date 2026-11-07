package com.hongguo.rewrite

import org.json.JSONObject
import java.io.IOException

/**
 * Read-only detail fields documented by beilunyang/openhongguo:
 * sdk/model/catalog.go (InfoKind / Series / Novel) and protocol.ParseSeries.
 * Reuses the existing signed video_detail/v1 response; never issues a second
 * registration or guesses ratings from ranking heat.
 */
data class OpenHongguoNovel(
    val id: String,
    val title: String,
    val cover: String = "",
    val status: String = "",
    val reads: Long? = null,
    val tags: List<String> = emptyList(),
)

data class OpenHongguoInfoRow(
    val kind: Int,
    val text: String,
)

data class OpenHongguoMetadata(
    val seriesId: String,
    val tags: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val score: String? = null,
    val novel: OpenHongguoNovel? = null,
    val albumId: String? = null,
    val season: Int? = null,
    val statusText: String? = null,
    val rows: List<OpenHongguoInfoRow> = emptyList(),
)

object OpenHongguoMetadataParser {
    private val idPattern = Regex("[1-9][0-9]{0,29}")
    // An InfoScore row is rating evidence; hot_score / rank_score are NOT.
    private val scorePattern = Regex("^(?:10(?:\\.0)?|[0-9](?:\\.[0-9])?)(?:分)?$")

    fun parse(response: JSONObject, expectedId: String): OpenHongguoMetadata {
        require(idPattern.matches(expectedId))
        if (response.opt("code")?.toString() != "0") throw IOException("METADATA_APP_CODE")
        val data = response.optJSONObject("data") ?: throw IOException("METADATA_NO_DATA")
        val video = data.optJSONObject("video_data") ?: throw IOException("METADATA_NO_VIDEO")
        val got = video.optString("series_id_str")
            .ifBlank { video.optString("series_id") }
        if (got != expectedId) throw IOException("METADATA_SERIES_MISMATCH")
        val rows = buildList {
            val entries = video.optJSONArray("secondary_infos")
            if (entries != null) for (i in 0 until minOf(entries.length(), 80)) {
                val row = entries.optJSONObject(i) ?: continue
                val value = row.optString("content").trim().take(200)
                if (value.isNotBlank()) add(OpenHongguoInfoRow(row.optInt("data_type", -1), value))
            }
        }
        val tags = rows.filter { it.kind in setOf(3, 9, 40) }
            .flatMap { it.text.split('、', '，', ',', '/', '|').map(String::trim) }
            .filter { it.isNotBlank() }.distinct().take(16)
        val actors = rows.filter { it.kind == 33 }
            .flatMap { it.text.replace(Regex("^(?:演员|主演|领衔主演)[：:·\\s]*"), "")
                .split('、', '，', ',', ' ', '·').map(String::trim) }
            .filter(String::isNotBlank).distinct().take(20)
        val score = rows.firstOrNull { it.kind == 4 }
            ?.text?.trim()?.takeIf { scorePattern.matches(it) }
        val related = data.optJSONObject("video_relate_book")
        val book = related?.optJSONObject("book")
        val novelId = book?.optString("book_id").orEmpty().ifBlank {
            book?.optString("id").orEmpty()
        }
        val novel = if (idPattern.matches(novelId)) {
            OpenHongguoNovel(
                id = novelId,
                title = book?.optString("book_name").orEmpty().ifBlank {
                    book?.optString("name").orEmpty()
                }.take(160),
                cover = book?.optString("cover").orEmpty(),
                status = when (book?.optString("creation_status").orEmpty().ifBlank {
                    book?.optString("status").orEmpty()
                }) {
                    "0" -> "已完结"
                    "1" -> "连载中"
                    "3" -> "今日更新"
                    else -> ""
                },
                reads = book?.let { SocialStatsParser.count(it, "read_count")
                    ?: SocialStatsParser.count(it, "reads") },
                tags = book?.optString("tags").orEmpty().split(',')
                    .map(String::trim).filter(String::isNotBlank).take(8),
            )
        } else null
        val album = video.optString("related_album_id")
            .ifBlank { video.optString("related_album_id_str") }
            .takeIf { idPattern.matches(it) }
        return OpenHongguoMetadata(
            seriesId = expectedId,
            tags = tags,
            actors = actors,
            score = score,
            novel = novel,
            albumId = album,
            season = video.optInt("season_index", 0).takeIf { it > 0 },
            statusText = rows.firstOrNull { it.kind == 11 }?.text,
            rows = rows,
        )
    }
}
