package com.yagay.hongguo

/**
 * Pure planning for the bounded upcoming-drama window. The same ordered IDs
 * drive metadata/model fetching and byte prewarming; hidden, repeated and
 * currently playing titles never take a cache slot.
 */
internal object SwipePrefetchPolicy {
    const val MAX_NEXT_DRAMAS = 2

    fun candidates(
        ids: List<String>,
        currentIndex: Int,
        hidden: Set<String>,
        activeId: String?,
    ): List<String> {
        if (currentIndex !in ids.indices) return emptyList()
        return ids.drop(currentIndex + 1)
            .filter { id -> id.isNotBlank() && id !in hidden && id != activeId }
            .distinct()
            .take(MAX_NEXT_DRAMAS)
    }
}
