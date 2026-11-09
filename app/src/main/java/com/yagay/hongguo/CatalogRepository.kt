package com.yagay.hongguo

/**
 * One catalog boundary for the optional remote backend and native provider.
 *
 * This intentionally does not silently fallback after an explicitly configured
 * backend fails: mixing independent pagination cursors produces duplicates
 * and incorrect filter results. Feed playback uses a separate repository.
 */
internal class CatalogRepository(
    private val native: NativeHongguoSource,
    private val configuredBackend: () -> WebContractClient?,
) {
    suspend fun page(
        section: Section,
        offset: Int,
        genre: String,
        board: String,
        query: String,
        filters: Map<String, String>,
        alreadyShown: List<String> = emptyList(),
    ): CatalogPage {
        val backend = configuredBackend()
        return if (backend != null) backend.list(
            section, offset, genre, board, query, filters, alreadyShown
        ) else native.list(section, offset, genre, board, query, filters)
    }

    suspend fun filters(genre: String): List<FilterGroup> {
        val backend = configuredBackend()
        return if (backend != null) backend.filters(genre) else native.filters(genre)
    }

    companion object {
        fun mergePage(
            previous: List<Drama>,
            newPage: CatalogPage,
            previousOffset: Int,
        ): CatalogPage {
            val merged = (previous + newPage.items).distinctBy(Drama::id)
            val advanced = newPage.nextOffset > previousOffset &&
                merged.size > previous.size
            return newPage.copy(
                items = merged,
                hasMore = newPage.hasMore && advanced,
            )
        }
    }
}
