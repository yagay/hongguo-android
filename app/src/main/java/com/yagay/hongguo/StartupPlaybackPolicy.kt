package com.yagay.hongguo

/** Pick confirmed playback history; browsing never changes this record. */
internal object StartupPlaybackPolicy {
    fun lastConfirmed(
        history: List<Watched>,
        hiddenDramaIds: Set<String> = emptySet(),
    ): Watched? = history.asSequence()
        .filter { it.drama.id.matches(Regex("[0-9]{1,30}")) }
        .filter { it.drama.id !in hiddenDramaIds && it.episode > 0 }
        .maxByOrNull { it.updated }
}
