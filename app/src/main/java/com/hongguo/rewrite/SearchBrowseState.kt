package com.hongguo.rewrite

/** Search discovery is independent of the homepage catalog and the live reel queue. */
internal data class SearchBrowseState(
    val section: Section? = null,
    val board: String = "all",
    val genre: String = "short_play",
    val activeFilters: Map<String, String> = emptyMap(),
    val filters: List<FilterGroup> = emptyList(),
    val items: List<Drama> = emptyList(),
    val loading: Boolean = false,
    val moreLoading: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val error: String? = null,
)
