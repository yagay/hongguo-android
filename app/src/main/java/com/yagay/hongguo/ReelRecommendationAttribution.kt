package com.yagay.hongguo

import com.hongguotv.core.PersonalizationEvidence

/** A recommendation's provenance is attached to the returned title, not a global batch. */
enum class ReelRecommendationOrigin {
    ACCOUNT_DYNAMIC,
    ACCOUNT_CATEGORY,
    ANONYMOUS_OFFICIAL_DYNAMIC,
    ANONYMOUS_OFFICIAL_CATEGORY,
    ANONYMOUS_KAIXINGUO,
}

data class ReelItemAttribution(
    val origin: ReelRecommendationOrigin,
    val evidence: PersonalizationEvidence = PersonalizationEvidence.UNDECLARED,
)

internal object ReelRecommendationAttributionPolicy {
    fun statusFor(
        seriesId: String?,
        sources: Map<String, ReelItemAttribution>,
        requestStatus: ReelRecommendationStatus,
    ): ReelRecommendationStatus {
        if (seriesId == null) return requestStatus
        val item = sources[seriesId] ?: return ReelRecommendationStatus.NOT_REQUESTED
        return when (item.origin) {
            ReelRecommendationOrigin.ACCOUNT_DYNAMIC ->
                ReelRecommendationStatusPolicy.received(true, true, item.evidence)
            ReelRecommendationOrigin.ACCOUNT_CATEGORY ->
                ReelRecommendationStatus.ACCOUNT_CATEGORY_FALLBACK
            ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_DYNAMIC ->
                ReelRecommendationStatus.ANONYMOUS_OFFICIAL_RECEIVED
            ReelRecommendationOrigin.ANONYMOUS_OFFICIAL_CATEGORY ->
                ReelRecommendationStatus.ANONYMOUS_OFFICIAL_CATEGORY
            ReelRecommendationOrigin.ANONYMOUS_KAIXINGUO ->
                ReelRecommendationStatus.ANONYMOUS_RECEIVED
        }
    }

    /** Preserve old provenance for repeated items and prune items no longer queued. */
    fun merge(
        previous: Map<String, ReelItemAttribution>,
        previousItems: List<Drama>,
        mergedItems: List<Drama>,
        incoming: Map<String, ReelItemAttribution>,
    ): Map<String, ReelItemAttribution> {
        val oldIds = previousItems.mapTo(hashSetOf()) { it.id }
        return buildMap {
            for (item in mergedItems) {
                val source = if (item.id in oldIds) previous[item.id] else incoming[item.id]
                if (source != null) put(item.id, source)
            }
        }
    }

    fun retained(
        previous: Map<String, ReelItemAttribution>,
        items: List<Drama>,
    ): Map<String, ReelItemAttribution> = previous.filterKeys { key ->
        items.any { it.id == key }
    }
}
