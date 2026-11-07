package com.hongguo.rewrite

import java.util.Locale

/** Public, upstream-reported engagement totals only, never local estimates. */
internal object SocialCountFormatter {
    fun format(value: Long): String = when {
        value < 0L -> "—"
        value < 10_000L -> value.toString()
        value < 100_000_000L -> "%.1f万".format(Locale.CHINA, value / 10_000.0)
        else -> "%.1f亿".format(Locale.CHINA, value / 100_000_000.0)
    }
}
