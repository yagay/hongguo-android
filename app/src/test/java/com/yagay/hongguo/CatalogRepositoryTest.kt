package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryTest {
    private fun drama(id: String) = Drama(id, "短剧 $id")

    @Test fun mergesOverlappingPagesWithoutDuplicatingItems() {
        val previous = listOf(drama("1"), drama("2"), drama("3"))
        val next = CatalogPage(
            items = listOf(drama("3"), drama("4")),
            hasMore = true,
            nextOffset = 12,
        )
        val merged = CatalogRepository.mergePage(previous, next, 6)
        assertEquals(listOf("1", "2", "3", "4"), merged.items.map { it.id })
        assertEquals(12, merged.nextOffset)
        assertTrue(merged.hasMore)
    }

    @Test fun refusesDuplicateOnlyOrStaleCursorContinuation() {
        val previous = listOf(drama("1"), drama("2"))
        val duplicateOnly = CatalogPage(listOf(drama("2")), true, 20)
        val stagnantCursor = CatalogPage(listOf(drama("3")), true, 10)
        assertFalse(CatalogRepository.mergePage(previous, duplicateOnly, 10).hasMore)
        assertFalse(CatalogRepository.mergePage(previous, stagnantCursor, 10).hasMore)
    }

    @Test fun upstreamFinalPageRemainsFinalEvenIfNewItemsArrive() {
        val previous = listOf(drama("1"))
        val last = CatalogPage(listOf(drama("2")), false, 20)
        assertFalse(CatalogRepository.mergePage(previous, last, 10).hasMore)
    }
}
