package com.yagay.hongguo

/** Retains surplus recommendations, deduplicated across feed and website. */
internal class RecommendationAccumulator<T>(private val idOf: (T) -> String) {
    private val seen = LinkedHashSet<String>()
    private val pending = ArrayDeque<T>()

    val available: Int get() = pending.size

    fun add(rows: List<T>): Int {
        var n = 0
        rows.forEach { item ->
            val key = idOf(item)
            if (key.isNotBlank() && seen.add(key)) {
                pending.addLast(item)
                n++
            }
        }
        return n
    }

    fun take(limit: Int): List<T> = buildList {
        repeat(minOf(limit.coerceAtLeast(0), pending.size)) {
            add(pending.removeFirst())
        }
    }
}
