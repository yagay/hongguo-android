package com.hongguo.rewrite

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DramaTagParserTest {
    private val id = "7012345678901234567"

    @Test fun acceptsOnlyVerifiedActorsAndCategories() {
        val series = JSONObject().put("series_id", id)
            .put("tags", JSONArray().put("都市").put("逆袭").put("都市"))
            .put("celebrities", JSONArray()
                .put(JSONObject().put("nickname", "演员甲"))
                .put(JSONObject().put("name", "演员乙").put("is_label", false))
                .put(JSONObject().put("nickname", "虚假标签").put("is_label", true)))
        val page = JSONObject().put("series_id", id).put("seriesDetail", series)
        val root = JSONObject().put("loaderData",
            JSONObject().put("player_(series_id)/page", page))
        assertEquals(listOf("演员甲", "演员乙"), DramaTagParser.website(root, id).actors)
        assertEquals(listOf("都市", "逆袭"), DramaTagParser.website(root, id).categories)
        assertThrows(IOException::class.java) {
            DramaTagParser.website(root, "7012345678901234568")
        }
    }

    @Test fun missingTagsDoNotProduceFakePlaceholders() {
        val root = JSONObject().put("loaderData", JSONObject()
            .put("player_(series_id)/page", JSONObject()
                .put("seriesDetail", JSONObject().put("series_id", id))))
        assertEquals(DramaLabels(), DramaTagParser.website(root, id))
        assertEquals(listOf("古装", "悬疑"),
            DramaTagParser.categoriesFromCore("古装 · 悬疑 · 古装"))
    }
}
