package com.yagay.hongguo

/**
 * Pure presentation policy for the Canvas danmaku renderer.
 * These settings must never change playback timing or network requests.
 */
internal object DanmakuStylePolicy {
    fun validFontWeight(value: Int): Boolean = value in setOf(200, 300, 400, 500, 600)
    fun validLineSpacing(value: Int): Boolean = value in setOf(0, 2, 4, 8)
    fun validOutline(value: Int): Boolean = value in setOf(0, 1, 2)
    fun validTextColor(value: Int): Boolean = value in 0..3

    /** Prevent neighboring lanes from touching, even with bold fonts/outline. */
    fun laneHeightPx(
        textSizePx: Float,
        glyphHeightPx: Float,
        gapPx: Float,
        outlineWidthPx: Float,
    ): Float = maxOf(textSizePx * 1.16f,
        glyphHeightPx + outlineWidthPx * 2f) + gapPx.coerceAtLeast(0f)
}
