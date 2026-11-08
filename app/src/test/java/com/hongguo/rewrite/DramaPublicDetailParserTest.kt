package com.hongguo.rewrite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DramaPublicDetailParserTest {
    private val id = "7100000000000101"

    private fun router(series: String = id): JSONObject = JSONObject("""
        {
          "loaderData": {
            "player_(series_id)/page": {
              "series_id": "$series",
              "seriesDetail": {
                "series_id_str": "$series",
                "celebrities": [
                  {"celebrity_id":"7100000000000800", "nickname":"王演员",
                   "avatar":"https://p3-reading-sign.fqnovelpic.com/avatar.jpg",
                   "sub_title":"顾知南"},
                  {"name":"宋演员", "is_label":false},
                  {"name":"分类名称", "is_label":true}
                ]
              },
              "seriesSocialInfo": {
                "rating":8.6, "rating_count":3179,
                "reviews": {
                  "total_count":134,
                  "reviews": [
                    {"user_name":"测试用户", "user_avatar":"https://p3-reading-sign.fqnovelpic.com/user.jpg",
                     "content":"很棒的剧", "rating":9.0, "digg_count":2}
                  ]
                }
              }
            }
          }
        }
    """.trimIndent())

    @Test fun parsesRealActorPicturesRolesAndSeriesReviews() {
        val result = DramaPublicDetailParser.parse(router(), id)
        assertEquals(2, result.cast.size)
        assertEquals("王演员", result.cast[0].name)
        assertEquals("顾知南", result.cast[0].role)
        assertEquals("7100000000000800", result.cast[0].actorId)
        assertTrue(result.cast[0].avatar.startsWith("https://"))
        assertEquals(8.6, result.rating!!, 0.001)
        assertEquals(3179L, result.ratingCount)
        assertEquals(134L, result.reviewCount)
        assertEquals("测试用户", result.reviews.first().author)
        assertEquals(2L, result.reviews.first().likes)
        assertEquals(9.0, result.reviews.first().rating!!, 0.001)
    }

    @Test fun rejectsOtherSeriesRatherThanShowingWrongReviews() {
        assertThrows(IOException::class.java) {
            DramaPublicDetailParser.parse(router("7100000000000999"), id)
        }
    }

    @Test fun missingSocialRowsNeverInventRatingsOrCounts() {
        val json = router()
        val page = json.getJSONObject("loaderData")
            .getJSONObject("player_(series_id)/page")
        page.remove("seriesSocialInfo")
        val result = DramaPublicDetailParser.parse(json, id)
        assertEquals(null, result.rating)
        assertEquals(null, result.ratingCount)
        assertEquals(null, result.reviewCount)
        assertTrue(result.reviews.isEmpty())
    }

    @Test fun rejectsUntrustedAvatarSchemes() {
        val json = router()
        val actors = json.getJSONObject("loaderData")
            .getJSONObject("player_(series_id)/page")
            .getJSONObject("seriesDetail").getJSONArray("celebrities")
        actors.getJSONObject(0).put("avatar", "javascript:alert(1)")
        assertEquals("", DramaPublicDetailParser.parse(json, id).cast.first().avatar)
    }
}
