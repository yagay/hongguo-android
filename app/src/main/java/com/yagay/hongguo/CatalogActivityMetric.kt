package com.yagay.hongguo

import java.util.Locale

/** Source-authored statistics, explicitly NOT a distinct recommendation count. */
internal object CatalogActivityMetric {
    fun compact(value: Long): String? = when {
        value <= 0L -> null
        value >= 100_000_000L ->
            String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
                .replace(".0亿", "亿")
        value >= 10_000L -> "${value / 10_000L}万"
        else -> value.toString()
    }

    fun label(follows: Long, heat: Long, plays: Long): String? =
        compact(follows)?.let { "${it}追剧" }
            ?: compact(heat)?.let { "${it}热度" }
            ?: compact(plays)?.let { "${it}播放" }
}
