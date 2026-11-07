package com.hongguo.rewrite

/** Device-compatible renditions: a preference is a cap, not a guarantee. */
internal object QualityPolicy {
    fun rank(quality: Int, preferredMax: Int): Int {
        if (quality <= 0) return 30_000
        val max = if (preferredMax in setOf(360, 480, 720, 1080)) preferredMax else 1080
        return if (quality <= max) max - quality
            else 10_000 + quality - max
    }
}
