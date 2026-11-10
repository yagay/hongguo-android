package com.yagay.hongguo

/** One explicit user setting for all optional episode and reel prefetching.
 * Active playback and playback-driven disk-cache writes are unaffected. */
internal object PreloadNetworkPolicy {
    const val OFF = "off"
    const val WIFI = "wifi"
    const val ALL = "all"

    fun normalized(value: String?): String = when (value) {
        OFF, WIFI, ALL -> value
        else -> WIFI
    }

    fun fromLegacy(wifiEnabled: Boolean, mobileEnabled: Boolean): String = when {
        mobileEnabled -> ALL
        wifiEnabled -> WIFI
        else -> OFF
    }

    fun allows(mode: String, isWifi: Boolean): Boolean = when (normalized(mode)) {
        ALL -> true
        WIFI -> isWifi
        else -> false
    }
}
