package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipePrefetchPolicyTest {
    @Test fun nextTwoCandidatesSkipHiddenCurrentAndDuplicates() {
        assertEquals(listOf("B", "C"), SwipePrefetchPolicy.candidates(
            ids = listOf("A", "A", "B", "B", "hidden", "C", "D"),
            currentIndex = 0, hidden = setOf("hidden"), activeId = "A"
        ))
    }

    @Test fun lastDramaAndInvalidIndexHaveNoCandidates() {
        assertTrue(SwipePrefetchPolicy.candidates(
            listOf("A", "B"), 1, emptySet(), "B").isEmpty())
        assertTrue(SwipePrefetchPolicy.candidates(
            listOf("A", "B"), -1, emptySet(), "A").isEmpty())
    }

    @Test fun rapidForwardSwipeRecomputesNextWindow() {
        val ids = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "C"), SwipePrefetchPolicy.candidates(
            ids, 0, emptySet(), "A"))
        assertEquals(listOf("C", "D"), SwipePrefetchPolicy.candidates(
            ids, 1, emptySet(), "B"))
    }
}
