package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPosterMetadataTest {
    @Test fun addsVerifiedGenreAndActorLabelsWithoutDuplicatingThem() {
        val original = Drama("123", "测试短剧", categories = listOf("都市"))
        val meta = OpenHongguoMetadata(
            seriesId = "123",
            tags = listOf("都市", "逆袭", "甜宠"),
            actors = listOf("演员甲"),
            rows = listOf(OpenHongguoInfoRow(9, "45.8万推荐")),
        )
        val enriched = CatalogPosterMetadata.merge(original, meta)
        assertEquals(listOf("都市", "逆袭", "甜宠"), enriched.categories)
        assertEquals("45.8万推荐", enriched.recommendationLabel)
        assertEquals(listOf("都市", "逆袭", "甜宠", "演员 演员甲"),
            CatalogPosterMetadata.tags(enriched))
    }

    @Test fun wrongSeriesMetadataCannotLeakToAnotherCard() {
        val original = Drama("123", "原剧")
        val unrelated = OpenHongguoMetadata(
            seriesId = "456", tags = listOf("玄幻"),
            rows = listOf(OpenHongguoInfoRow(1, "998万推荐")),
        )
        assertEquals(original, CatalogPosterMetadata.merge(original, unrelated))
    }

    @Test fun heatOrPlaysNeverBecomeRecommendationPeople() {
        val original = Drama("123", "原剧")
        val metadata = OpenHongguoMetadata(
            seriesId = "123",
            rows = listOf(OpenHongguoInfoRow(1, "880万热度"),
                OpenHongguoInfoRow(2, "2000万播放")),
        )
        assertNull(CatalogPosterMetadata.merge(original, metadata).recommendationLabel)
    }

    @Test fun existingSourceLabelWinsOverSecondaryMetadata() {
        val original = Drama("123", "原剧", recommendationLabel = "990万推荐")
        val meta = OpenHongguoMetadata("123",
            rows = listOf(OpenHongguoInfoRow(1, "500万推荐")))
        assertEquals("990万推荐",
            CatalogPosterMetadata.merge(original, meta).recommendationLabel)
    }
}
