package com.hongguo.rewrite

/** The swipe queue is independent from the manually loaded home recommendation catalog. */
internal object OfficialReelsPolicy {
    /** An account-mode switch discards stale anonymous recommendations, but
     * never closes or replaces the drama currently playing in Media3.
     */
    fun retainCurrentOnAccountChange(
        current: List<Drama>, position: Int, playing: Drama?,
    ): List<Drama> = listOfNotNull(current.getOrNull(position) ?: playing)

    fun append(current: List<Drama>, incoming: List<Drama>, hidden: Set<String>): List<Drama> {
        val seen = current.mapTo(hashSetOf()) { it.id }
        return current + incoming.filter { it.id !in seen && it.id !in hidden }
            .distinctBy { it.id }
    }

    /** Read ahead when fewer than three visible titles remain after the current. */
    fun needsMore(
        items: List<Drama>, position: Int, hidden: Set<String>,
        hasMore: Boolean, loading: Boolean,
    ): Boolean = hasMore && !loading &&
        items.drop(position + 1).count { it.id !in hidden } < 3

    fun nextIndex(items: List<Drama>, current: Int, direction: Int, hidden: Set<String>): Int? {
        if (direction !in setOf(-1, 1)) return null
        val range = if (direction > 0) (current + 1 until items.size)
            else (current - 1 downTo 0)
        return range.firstOrNull { items[it].id !in hidden }
    }
}
