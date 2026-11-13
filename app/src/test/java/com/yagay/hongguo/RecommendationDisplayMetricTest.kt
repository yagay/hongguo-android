package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationDisplayMetricTest {
    @Test fun acceptsOnlyExplicitNumericRecommendationLabels() {
        assertEquals("998万推荐", RecommendationDisplayMetric.fromSource(" 998万推荐 "))
        assertEquals("3.5万人推荐", RecommendationDisplayMetric.fromSource("3.5万人推荐"))
        assertEquals("推荐人数：1234", RecommendationDisplayMetric.fromSource("推荐人数：1234"))
        assertEquals("32推荐", RecommendationDisplayMetric.fromSource("32推荐"))
    }

    @Test fun neverInventRecommendationPeopleFromRankOrPopularity() {
        listOf("平台推荐", "2550万热度", "1000万播放", "100点赞",
            "已有500人追剧", "推荐榜第1", "排名第一", "", "推荐")
            .forEach { assertNull(it, RecommendationDisplayMetric.fromSource(it)) }
        assertNull(RecommendationDisplayMetric.fromSource(null))
    }

    @Test fun sourceRowSelectionIsStrict() {
        assertEquals("1.2亿推荐",
            RecommendationDisplayMetric.fromSourceRows(
                listOf("已完结", "999万热度", "1.2亿推荐")))
        assertNull(RecommendationDisplayMetric.fromSourceRows(
            listOf("都市", "追剧人数1000", "万赞")))
    }
}
