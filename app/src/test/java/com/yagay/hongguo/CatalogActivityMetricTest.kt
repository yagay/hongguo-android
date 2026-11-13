package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogActivityMetricTest {
    @Test fun compactCountUsesChineseUnits() {
        assertNull(CatalogActivityMetric.compact(0))
        assertEquals("9999", CatalogActivityMetric.compact(9999))
        assertEquals("43万", CatalogActivityMetric.compact(437000))
        assertEquals("1亿", CatalogActivityMetric.compact(100_000_000L))
        assertEquals("1.2亿", CatalogActivityMetric.compact(120_000_000L))
    }

    @Test fun neverMislabelHeatOrFollowsAsRecommendationPeople() {
        assertEquals("43万追剧",
            CatalogActivityMetric.label(437000, 9980000, 20000000))
        assertEquals("998万热度",
            CatalogActivityMetric.label(0, 9980000, 20000000))
        assertEquals("2000万播放",
            CatalogActivityMetric.label(0, 0, 20000000))
        assertNull(CatalogActivityMetric.label(0, 0, 0))
    }
}
