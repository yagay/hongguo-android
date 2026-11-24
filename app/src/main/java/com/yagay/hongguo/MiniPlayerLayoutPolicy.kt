package com.yagay.hongguo

/** Pure positioning policy: 1f is the default right/bottom edge. */
internal object MiniPlayerLayoutPolicy {
    fun move(fraction: Float, deltaPx: Float, travelPx: Float): Float {
        if (!travelPx.isFinite() || travelPx <= 0f) return 1f
        return (fraction + deltaPx / travelPx).coerceIn(0f, 1f)
    }

    fun snapX(fraction: Float): Float = if (fraction < .5f) 0f else 1f

    fun offsetPx(travelPx: Float, fraction: Float): Float =
        -travelPx.coerceAtLeast(0f) * (1f - fraction.coerceIn(0f, 1f))
}
