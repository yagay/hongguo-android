package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationAccumulatorTest {
    @Test fun givesTwelveAndKeepsExtraRows() {
        val q = RecommendationAccumulator<Int> { it.toString() }
        q.add((1..17).toList())
        assertEquals((1..12).toList(), q.take(12))
        assertEquals(listOf(13, 14, 15, 16, 17), q.take(12))
    }

    @Test fun repeatedIdsAcrossSourcesOnlyShowOnce() {
        val q = RecommendationAccumulator<String> { it }
        assertEquals(3, q.add(listOf("A", "B", "C")))
        assertEquals(2, q.add(listOf("A", "C", "D", "E")))
        assertEquals(listOf("A", "B", "C", "D", "E"), q.take(12))
        assertEquals(0, q.add(listOf("A", "E")))
    }
}
