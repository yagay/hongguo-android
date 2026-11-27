package com.yagay.hongguo.kxg

internal object EpisodeDirectoryPolicy {
    fun validId(id: String): Boolean = id.length in 1..32 &&
        id.all { it in '0'..'9' } && id.any { it != '0' }

    fun contiguous(indices: List<Int>): Boolean = indices.isNotEmpty() &&
        indices.withIndex().all { (i, value) -> value == i + 1 }
}
