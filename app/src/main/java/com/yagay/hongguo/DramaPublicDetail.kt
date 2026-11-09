package com.yagay.hongguo

import org.json.JSONObject
import java.io.IOException

/** Public series-only detail fields. Episode comments are deliberately separate. */
data class PublicCastMember(
    val name: String,
    val avatar: String = "",
    val role: String = "",
    val actorId: String = "",
)

data class PublicSeriesReview(
    val id: String,
    val author: String,
    val avatar: String,
    val content: String,
    val rating: Double?,
    val likes: Long,
)

data class DramaPublicDetail(
    val seriesId: String,
    val cast: List<PublicCastMember> = emptyList(),
    val rating: Double? = null,
    val ratingCount: Long? = null,
    val reviewCount: Long? = null,
    val reviews: List<PublicSeriesReview> = emptyList(),
)

/** Adapted from public-data approaches in hongguo-open-player and hongguoTV. */
object DramaPublicDetailParser {
    private val idPattern = Regex("[0-9]{1,30}")

    fun parse(router: JSONObject, expectedId: String): DramaPublicDetail {
        require(idPattern.matches(expectedId))
        val loader = router.optJSONObject("loaderData")
            ?: throw IOException("DETAIL_PUBLIC_STRUCTURE")
        val page = loader.optJSONObject("player_(series_id)/page")
            ?: loader.optJSONObject("player_(series_id)/(vid)/page")
            ?: loader.optJSONObject("detail_page")
            ?: throw IOException("DETAIL_PUBLIC_STRUCTURE")
        val detail = page.optJSONObject("seriesDetail")
            ?: throw IOException("DETAIL_PUBLIC_SERIES")
        val returned = detail.optString("series_id_str")
            .ifBlank { detail.optString("series_id") }
        val pageId = page.optString("series_id")
        if (returned != expectedId || (pageId.isNotBlank() && pageId != expectedId))
            throw IOException("DETAIL_PUBLIC_ID_MISMATCH")

        val actors = detail.optJSONArray("celebrities")
        val cast = buildList {
            if (actors != null) for (i in 0 until minOf(actors.length(), 40)) {
                val row = actors.optJSONObject(i) ?: continue
                if (row.optBoolean("is_label")) continue
                val name = row.optString("nickname").ifBlank { row.optString("name") }
                    .trim().take(40)
                if (name.isBlank()) continue
                val portrait = row.optString("avatar")
                    .take(2048).takeIf { it.startsWith("https://") }.orEmpty()
                add(PublicCastMember(
                    name = name,
                    avatar = portrait,
                    role = row.optString("sub_title")
                        .ifBlank { row.optString("role") }.trim().take(60),
                    actorId = row.optString("celebrity_id")
                        .ifBlank { row.optString("actor_id") }
                        .takeIf { idPattern.matches(it) }.orEmpty(),
                ))
            }
        }.distinctBy { it.name }.take(20)

        val social = page.optJSONObject("seriesSocialInfo")
        val rating = social?.optDouble("rating", Double.NaN)
            ?.takeIf { it.isFinite() && it in 0.0..10.0 && it > 0 }
        val ratingCount = social?.let {
            SocialStatsParser.count(it, "rating_count")
        }
        val reviewBoard = social?.optJSONObject("reviews")
        val reviewCount = reviewBoard?.let {
            SocialStatsParser.count(it, "total_count")
        }
        val entries = reviewBoard?.optJSONArray("reviews")
        val reviews = buildList {
            if (entries != null) for (i in 0 until minOf(entries.length(), 30)) {
                val row = entries.optJSONObject(i) ?: continue
                val body = row.optString("content").trim().take(2000)
                if (body.isBlank()) continue
                val who = row.optString("user_name").ifBlank { "用户" }.take(80)
                val created = row.optLong("create_time", 0)
                val avatar = row.optString("user_avatar").take(2048)
                    .takeIf { it.startsWith("https://") }.orEmpty()
                val score = row.optDouble("rating", Double.NaN)
                    .takeIf { it.isFinite() && it > 0.0 && it <= 10.0 }
                add(PublicSeriesReview(
                    id = listOf(who, created, body).joinToString("|").hashCode().toString(),
                    author = who,
                    avatar = avatar,
                    content = body,
                    rating = score,
                    likes = row.optLong("digg_count", 0).coerceAtLeast(0),
                ))
            }
        }.distinctBy { it.id }
        return DramaPublicDetail(
            seriesId = expectedId,
            cast = cast,
            rating = rating,
            ratingCount = ratingCount,
            reviewCount = reviewCount,
            reviews = reviews,
        )
    }
}
