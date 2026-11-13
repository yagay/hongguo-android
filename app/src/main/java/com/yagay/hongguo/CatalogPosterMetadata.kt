package com.yagay.hongguo

/**
 * Catalog-only metadata decoration. Never turn ranking heat, plays, follows,
 * actor counts or local favorites into a recommendation figure.
 */
internal object CatalogPosterMetadata {
    fun merge(drama: Drama, metadata: OpenHongguoMetadata): Drama {
        if (drama.id != metadata.seriesId) return drama
        val tags = (drama.categories + metadata.tags)
            .map(String::trim).filter(String::isNotBlank).distinct().take(6)
        val actors = (drama.actors + metadata.actors)
            .map(String::trim).filter(String::isNotBlank).distinct().take(3)
        return drama.copy(
            categories = tags,
            actors = actors,
            recommendationLabel = drama.recommendationLabel
                ?: RecommendationDisplayMetric.fromSourceRows(
                    metadata.rows.map(OpenHongguoInfoRow::text)),
            activityLabel = drama.activityLabel ?: CatalogActivityMetric.label(
                metadata.followCount, metadata.heat, metadata.playCount),
        )
    }

    fun tags(drama: Drama): List<String> =
        (drama.categories.take(4) +
            drama.actors.take(1).map { "演员 $it" })
            .filter(String::isNotBlank).distinct().take(5)
}
