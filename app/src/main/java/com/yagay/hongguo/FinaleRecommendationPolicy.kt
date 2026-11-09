package com.yagay.hongguo

/**
 * A transient duplicate/empty recommendation must not permanently stop
 * autoplay after a full-series finale. The number of retries is bounded.
 */
internal object FinaleRecommendationPolicy {
    const val MAX_RETRIES = 2

    fun canRetry(attemptsAlreadyMade: Int): Boolean =
        attemptsAlreadyMade in 0 until MAX_RETRIES

    /** The second pass can revisit a previously swiped recommendation. */
    fun relaxRecentExclusions(attemptNumber: Int): Boolean = attemptNumber >= 1
}
