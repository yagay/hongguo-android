package com.yagay.hongguo

/**
 * Only explicit server type codes can verify real-person content.
 * Story themes (都市/古装/言情) and titles never prove the medium.
 */
internal object OfficialReelsType {
    private val real = setOf("short_play", "real", "real_drama", "live_action",
        "live-action", "真人", "真人短剧")
    private val nonReal = setOf("comic_series", "ai_series", "comic", "animation",
        "anime", "ai", "漫剧", "动画", "动漫", "ai短剧", "ai漫剧")

    fun isReal(type: String): Boolean {
        val tokens = type.lowercase().split(Regex("[,;|\\s]+")).filter(String::isNotBlank)
        if (tokens.any { it in nonReal }) return false
        return tokens.any { it in real }
    }
}
