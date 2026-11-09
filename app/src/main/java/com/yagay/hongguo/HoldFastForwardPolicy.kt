package com.yagay.hongguo

/**
 * Left and right edge holds have identical behavior: temporary 2x forward
 * playback. The center is reserved for the existing clear-screen menu.
 */
internal object HoldFastForwardPolicy {
    const val SPEED = 2f
    private const val EDGE_FRACTION = 0.22f

    /** Returns -1 for left, +1 for right and null outside either edge. */
    fun sideForTouch(x: Float, width: Float): Int? {
        if (!width.isFinite() || width <= 0f ||
            !x.isFinite() || x < 0f || x > width) return null
        val edge = width * EDGE_FRACTION
        return when {
            x < edge -> -1
            x > width - edge -> 1
            else -> null
        }
    }
}
