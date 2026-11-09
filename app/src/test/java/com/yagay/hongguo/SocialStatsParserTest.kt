package com.yagay.hongguo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SocialStatsParserTest {
    private val sid = "7200000000000000001"
    private val vid = "7300000000000000002"

    @Test fun websiteCountsUseOnlyMatchedSeries() {
        val data = JSONObject().put("series_id", sid)
        val page = JSONObject()
            .put("series_id", sid)
            .put("seriesDetail", data)
            .put("seriesSocialInfo", JSONObject()
                .put("series_favorite_count", "37.8万")
                .put("series_like_count", 73000))
        val root = JSONObject().put("loaderData",
            JSONObject().put("player_(series_id)/page", page))
        val result = SocialStatsParser.website(root, sid)
        assertEquals(378000L, result.websiteFavorites)
        assertEquals(73000L, result.websiteLikes)
        assertThrows(IOException::class.java) {
            SocialStatsParser.website(root, "7300000000000000000")
        }
    }

    @Test fun signedAppDetailSeparatesSeriesAndEpisodeStats() {
        val ep = JSONObject().put("vid", vid)
            .put("series_id", sid)
            .put("comment_count", 1075)
            .put("digged_count", 73000)
        val invalid = JSONObject().put("vid", "7300000000000000999")
            .put("series_id", "7400000000000000000")
            .put("digged_count", 999999)
        val root = JSONObject().put("code", 0)
            .put("data", JSONObject().put("video_data", JSONObject()
                .put("series_id_str", sid)
                .put("followed_cnt", 378000L)
                .put("digg_cnt", 100000L)
                .put("series_play_cnt", 1500000L)
                .put("series_status", 1)
                .put("video_list", JSONArray().put(ep).put(invalid))))
        val result = SocialStatsParser.application(root, sid)
        assertEquals(378000L, result.appFollows)
        assertEquals(1500000L, result.playCount)
        assertEquals("已完结", result.completionStatus)
        assertEquals(1075L, result.episodes[vid]?.comments)
        assertEquals(73000L, result.episodes[vid]?.likes)
        assertEquals(1, result.episodes.size)
        assertEquals("本集", result.likeMetric(vid)?.second)
        assertEquals("全剧", result.likeMetric("7400000000000000001")?.second)
    }

    @Test fun missingNumbersNeverBecomeFakeZero() {
        val empty = JSONObject()
        assertNull(SocialStatsParser.count(empty, "digg_cnt"))
        assertNull(SocialStatsParser.count(JSONObject().put("digg_cnt", "bad"), "digg_cnt"))
        assertNull(SocialStatsParser.count(JSONObject().put("digg_cnt", -42), "digg_cnt"))
        assertEquals(0L, SocialStatsParser.count(
            JSONObject().put("digg_cnt", 0), "digg_cnt"))
        assertEquals(73000L, SocialStatsParser.count(
            JSONObject().put("digg_cnt", "7.3万"), "digg_cnt"))
        assertEquals(120000000L, SocialStatsParser.count(
            JSONObject().put("digg_cnt", "1.2亿"), "digg_cnt"))
    }

    @Test fun mergeIsOrderIndependentAndDoesNotConfuseFollowsWithFavorites() {
        val website = SocialStats(sid, websiteFavorites = 240L, websiteLikes = 1500L)
        val app = SocialStats(sid, appFollows = 280L, appLikes = 1800L,
            episodes = mapOf(vid to EpisodeSocial(likes = 42L)))
        assertEquals(website.merge(app), app.merge(website))
        val merged = website.merge(app)
        assertEquals(240L, merged.favoriteMetric()?.first)
        assertEquals("收藏", merged.favoriteMetric()?.second)
        assertEquals(42L, merged.likeMetric(vid)?.first)
        assertEquals("本集", merged.likeMetric(vid)?.second)
        assertThrows(IllegalArgumentException::class.java) {
            website.merge(SocialStats("7300000000000000000"))
        }
    }
}
