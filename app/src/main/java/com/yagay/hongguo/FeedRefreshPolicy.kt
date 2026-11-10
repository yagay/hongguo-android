package com.yagay.hongguo

/** A single failed video model says nothing about other official cards.
 * Preserve the current screen as an anchor during a fresh cursor request. */
internal object FeedRefreshPolicy {
    fun anchor(queue: List<Drama>, index: Int, playing: Drama?): Drama? =
        playing ?: queue.getOrNull(index)
}
