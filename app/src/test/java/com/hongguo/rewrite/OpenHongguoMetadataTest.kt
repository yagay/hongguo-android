package com.hongguo.rewrite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OpenHongguoMetadataTest {
    private val id = "7100000000000101"

    @Test fun parsesOfficialInfoKindsAndOriginalNovel() {
        val json = JSONObject("""
            {
              "code":0,
              "data":{
                "video_data":{
                  "series_id_str":"7100000000000101",
                  "secondary_infos":[
                    {"data_type":3,"content":"古装"},
                    {"data_type":9,"content":"逆袭、穿越"},
                    {"data_type":33,"content":"演员·甲 乙"},
                    {"data_type":4,"content":"8.6分"},
                    {"data_type":11,"content":"已完结"}
                  ],
                  "related_album_id":"7100000000000901",
                  "season_index":2
                },
                "video_relate_book":{
                  "book_info":{
                    "book_id":"7100000000000801",
                    "book_name":"原著小说",
                    "thumb_url":"https://media.invalid/novel.jpg",
                    "creation_status":0,
                    "read_count":12345,
                    "tags":"都市,甜宠"
                  }
                }
              }
            }
        """.trimIndent())
        val got = OpenHongguoMetadataParser.parse(json, id)
        assertEquals(listOf("古装", "逆袭", "穿越"), got.tags)
        assertEquals(listOf("甲", "乙"), got.actors)
        assertEquals("8.6分", got.score)
        assertEquals("7100000000000901", got.albumId)
        assertEquals(2, got.season)
        assertEquals("已完结", got.statusText)
        assertEquals("7100000000000801", got.novel?.id)
        assertEquals("原著小说", got.novel?.title)
        assertEquals(12345L, got.novel?.reads)
    }

    @Test fun neverInterpretsRankingHeatAsUserRating() {
        val body = JSONObject("""
          {"code":0,"data":{"video_data":{
            "series_id_str":"7100000000000101",
            "hot_score":850000,
            "secondary_infos":[
              {"data_type":2,"content":"8.6万热度"},
              {"data_type":4,"content":"平台推荐"}
            ]
          }}}
        """)
        val result = OpenHongguoMetadataParser.parse(body, id)
        assertNull(result.score)
        assertTrue(OpenHongguoMetadataParser.isRating("8.6"))
        assertTrue(!OpenHongguoMetadataParser.isRating("850万热度"))
    }

    @Test fun onlyOfficialExplicitAuthorDeclarationShowsAiLabel() {
        val official = JSONObject("""{"code":0,"data":{"video_data":{
          "series_id_str":"7100000000000101",
          "secondary_infos":[
            {"data_type":3,"content":"AI短剧"},
            {"data_type":55,"content":"作者声明：内容由AI生成"}
          ]}}}""")
        val parsed = OpenHongguoMetadataParser.parse(official, id)
        assertEquals("作者声明：内容由AI生成", parsed.authorAiStatement)
        assertEquals("AI短剧", parsed.tags.single())

        val genreOnly = JSONObject("""{"code":0,"data":{"video_data":{
          "series_id_str":"7100000000000101",
          "secondary_infos":[
            {"data_type":3,"content":"AI短剧"},
            {"data_type":55,"content":"作品中含有AI元素"}
          ]}}}""")
        assertNull(OpenHongguoMetadataParser.parse(genreOnly, id).authorAiStatement)
        assertNull(OpenHongguoMetadataParser.confirmedAiAuthorStatement("AI短剧"))
        assertNull(OpenHongguoMetadataParser.confirmedAiAuthorStatement("疑似使用AI生成技术"))
        assertEquals("作者声明：内容由AI生成",
            OpenHongguoMetadataParser.confirmedAiAuthorStatement(
                "作者声明: 内容由 ai 生成"))
    }

    @Test fun onlyValidatedSeriesMaySupplyAiAuthorDeclaration() {
        val other = JSONObject("""{"code":0,"data":{"video_data":{
          "series_id_str":"7100000000009999",
          "author_statement":"作者声明：内容由AI生成"
        }}}""")
        assertThrows(IOException::class.java) {
            OpenHongguoMetadataParser.parse(other, id)
        }

        val first = OpenHongguoMetadata(id, tags = listOf("真人"))
        val second = OpenHongguoMetadata(id,
            authorAiStatement = "作者声明：内容由AI生成")
        assertEquals("作者声明：内容由AI生成",
            OpenHongguoMetadataParser.merge(first, second).authorAiStatement)
        assertNull(OpenHongguoMetadataParser.merge(first,
            OpenHongguoMetadata("7100000000009999",
                authorAiStatement = "作者声明：内容由AI生成")).authorAiStatement)
    }

    @Test fun rejectsCrossSeriesPayloadAndUnknownId() {
        val body = JSONObject("""{"code":0,"data":{"video_data":{
          "series_id_str":"7100000000009999"
        }}}""")
        assertThrows(IOException::class.java) {
            OpenHongguoMetadataParser.parse(body, id)
        }
    }

    @Test fun independentMetadataSourcesMergeWithoutErasingFields() {
        val first = OpenHongguoMetadata(id, tags = listOf("古装"),
            score = "9.1", actors = listOf("甲"))
        val second = OpenHongguoMetadata(id,
            tags = listOf("古装", "穿越"), actors = listOf("乙"),
            novel = OpenHongguoNovel("7100000000000801", "原著"))
        val got = OpenHongguoMetadataParser.merge(first, second)
        assertEquals(listOf("古装", "穿越"), got.tags)
        assertEquals(listOf("甲", "乙"), got.actors)
        assertEquals("9.1", got.score)
        assertEquals("原著", got.novel?.title)
    }
}
