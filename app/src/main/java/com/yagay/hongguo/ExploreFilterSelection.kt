package com.yagay.hongguo

/**
 * Immutable filter-panel state. The SDK browse request observes applied values only.
 * Each group is single-choice in the provider contract; tapping its selected value clears it.
 */
internal object ExploreFilterSelection {
    fun toggle(current: Map<String, List<String>>, key: String, value: String):
        Map<String, List<String>> {
        if (key.isBlank() || value.isBlank()) return current
        val previous = current[key].orEmpty()
        return if (value in previous) current - key else current + (key to listOf(value))
    }

    fun removeValue(current: Map<String, List<String>>, key: String, value: String):
        Map<String, List<String>> {
        val remaining = current[key].orEmpty().filterNot { it == value }
        return if (remaining.isEmpty()) current - key else current + (key to remaining)
    }
}
