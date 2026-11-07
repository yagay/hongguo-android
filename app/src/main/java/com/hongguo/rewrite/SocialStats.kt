package com.hongguo.rewrite

import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal

/** Public read-only social totals, scoped by series and episode.
 * Missing values remain null. Local button state must NEVER change these.
 */
data class EpisodeSocial(
    val comments: Long? = null,
    val likes: Long? = null,
)

data class SocialStats(
    val seriesId: String,
    val websiteFavorites: Long? = null,
    val websiteLikes: Long? = null,
    val appFollows: Long? = null,
    val appLikes: Long? = null,
    val playCount: Long? = null,
    val episodes: Map<String, EpisodeSocial> = emptyMap(),
) {
    fun merge(other: SocialStats): SocialStats {
        require(seriesId == other.seriesId)
        return copy(
            websiteFavorites = other.websiteFavorites ?: websiteFavorites,
            websiteLikes = other.websiteLikes ?: websiteLikes,
            appFollows = other.appFollows ?: appFollows,
            appLikes = other.appLikes ?: appLikes,
            playCount = other.playCount ?: playCount,
            episodes = episodes + other.episodes,
        )
    }

    /** The website favorite count and App follow count are distinct metrics. */
    fun favoriteMetric(): Pair<Long, String>? =
        websiteFavorites?.let { it to "收藏" }
            ?: appFollows?.let { it to "追剧" }

    /** Prefer this episode's digg count. Only an explicitly labelled fallback
     * may display series-wide likes as an episode-like replacement.
     */
    fun likeMetric(vid: String): Pair<Long, String>? =
        episodes[vid]?.likes?.let { it to "本集" }
            ?: websiteLikes?.let { it to "全剧" }
            ?: appLikes?.let { it to "全剧" }
}

/** Parsing is independent of the network client to support fixture-based tests. */
object SocialStatsParser {
    private val id = Regex("[0-9]{1,30}")
    private val short = Regex("""^([0-9]{1,15})(?:\.([0-9]{1,2}))?([万亿])$""")

    fun website(router: JSONObject, requestedId: String): SocialStats {
        require(id.matches(requestedId))
        val loader = router.optJSONObject("loaderData")
            ?: throw IOException("SOCIAL_WEB_STRUCTURE")
        val page = loader.optJSONObject("player_(series_id)/page")
            ?: loader.optJSONObject("player_(series_id)/(vid)/page")
            ?: loader.optJSONObject("detail_page")
            ?: throw IOException("SOCIAL_WEB_STRUCTURE")
        val detail = page.optJSONObject("seriesDetail")
            ?: throw IOException("SOCIAL_WEB_STRUCTURE")
        val returned = detail.optString("series_id_str")
            .ifBlank { detail.optString("series_id") }
        if (returned != requestedId) throw IOException("SOCIAL_WEB_ID_MISMATCH")
        val pageId = page.optString("series_id")
        if (pageId.isNotBlank() && pageId != requestedId)
            throw IOException("SOCIAL_WEB_ID_MISMATCH")
        val info = page.optJSONObject("seriesSocialInfo")
            ?: throw IOException("SOCIAL_WEB_EMPTY")
        return SocialStats(
            seriesId = requestedId,
            websiteFavorites = count(info, "series_favorite_count"),
            websiteLikes = count(info, "series_like_count"),
        )
    }

    fun application(response: JSONObject, requestedId: String): SocialStats {
        require(id.matches(requestedId))
        if (response.opt("code")?.toString() != "0") throw IOException("SOCIAL_APP_CODE")
        val row = response.optJSONObject("data")?.optJSONObject("video_data")
            ?: throw IOException("SOCIAL_APP_STRUCTURE")
        val returned = row.optString("series_id_str")
            .ifBlank { row.optString("series_id") }
        if (returned != requestedId) throw IOException("SOCIAL_APP_ID_MISMATCH")
        val items = linkedMapOf<String, EpisodeSocial>()
        val episodes = row.optJSONArray("video_list")
        if (episodes != null) for (i in 0 until minOf(episodes.length(), 600)) {
            val ep = episodes.optJSONObject(i) ?: continue
            val vid = ep.optString("vid")
            if (!id.matches(vid)) continue
            val episodeSeries = ep.optString("series_id")
            if (episodeSeries.isNotBlank() && episodeSeries != requestedId) continue
            val comment = count(ep, "comment_count")
            val likes = count(ep, "digged_count")
            if (comment != null || likes != null) {
                items[vid] = EpisodeSocial(comment, likes)
            }
        }
        return SocialStats(
            seriesId = requestedId,
            appFollows = count(row, "followed_cnt"),
            appLikes = count(row, "digg_cnt"),
            playCount = count(row, "series_play_cnt"),
            episodes = items,
        )
    }

    /** Distinguish absent, zero, formatted and malformed metrics. */
    fun count(obj: JSONObject, name: String): Long? {
        if (!obj.has(name) || obj.isNull(name)) return null
        val raw = obj.opt(name)
        val str = when (raw) {
            is String -> raw.trim()
            is Byte, is Short, is Int, is Long -> raw.toString()
            else -> return null
        }
        str.toLongOrNull()?.let { return it.takeIf { value -> value >= 0L } }
        val match = short.matchEntire(str) ?: return null
        return try {
            val number = BigDecimal(match.groupValues[1] +
                if (match.groupValues[2].isBlank()) "" else "." + match.groupValues[2])
            val factor = if (match.groupValues[3] == "亿") 100_000_000L else 10_000L
            number.multiply(BigDecimal.valueOf(factor)).longValueExact()
                .takeIf { it >= 0L }
        } catch (_: ArithmeticException) {
            null
        }
    }
}
