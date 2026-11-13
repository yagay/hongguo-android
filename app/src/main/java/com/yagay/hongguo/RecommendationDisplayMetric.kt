package com.yagay.hongguo

/**
 * Accept only a numeric recommendation label explicitly reported by a content
 * source. The backend's "N万推荐" is an engagement/ranking display value:
 * it is NOT evidence of N distinct people.
 *
 * Do not turn play, like, follow or heat counts into recommendations.
 */
internal object RecommendationDisplayMetric {
    private val explicitRecommendation =
        Regex("""^(?:[0-9]{1,12}(?:\.[0-9]{1,2})?(?:万|亿)?\s*(?:人)?推荐|推荐(?:人数|量)?[：:\s]*[0-9]{1,12}(?:\.[0-9]{1,2})?(?:万|亿)?)$""")

    fun fromSource(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.length in 1..32 } ?: return null
        return value.takeIf(explicitRecommendation::matches)
    }

    fun fromSourceRows(rows: List<String>): String? =
        rows.firstNotNullOfOrNull(::fromSource)
}
