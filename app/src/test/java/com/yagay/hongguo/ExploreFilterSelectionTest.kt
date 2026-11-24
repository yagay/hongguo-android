package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreFilterSelectionTest {
    @Test fun togglingSelectedFilterClearsOnlyItsOwnGroup() {
        val first = mapOf("genre" to listOf("urban"), "year" to listOf("2025"))
        val cleared = ExploreFilterSelection.toggle(first, "genre", "urban")
        assertEquals(mapOf("year" to listOf("2025")), cleared)
    }

    @Test fun togglingReplacesSingleChoiceWithinGroup() {
        val first = mapOf("genre" to listOf("urban"), "year" to listOf("2025"))
        val result = ExploreFilterSelection.toggle(first, "genre", "comedy")
        assertEquals(listOf("comedy"), result["genre"])
        assertEquals(listOf("2025"), result["year"])
        assertEquals(listOf("urban"), first["genre"])
    }

    @Test fun removingOneValuePreservesOtherChoices() {
        val selected = mapOf("tags" to listOf("one", "two"))
        val result = ExploreFilterSelection.removeValue(selected, "tags", "one")
        assertEquals(listOf("two"), result["tags"])
    }

    @Test fun ignoresBlankIdentifiers() {
        val data = mapOf("genre" to listOf("urban"))
        assertEquals(data, ExploreFilterSelection.toggle(data, "", "other"))
        assertEquals(data, ExploreFilterSelection.toggle(data, "genre", ""))
    }
}
