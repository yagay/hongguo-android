package com.yagay.hongguo

/**
 * Long-interval retries after the existing bounded source/network recovery is
 * exhausted. No additional requests are issued for permanent authorization,
 * rate-limit, decoding, or unsupported-content errors.
 *
 * This is an in-memory playback-session policy, not a background WorkManager
 * loop: all pending work ends with the ViewModel/session.
 */
internal object PeriodicPlaybackRetryPolicy {
    private val backoffMs = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    fun delayMs(attempt: Int): Long =
        backoffMs[(attempt.coerceAtLeast(1) - 1).coerceAtMost(backoffMs.lastIndex)]

    /** Browsing the catalog may retain a working mini-player, but must not
     * keep resurrecting a failed source. Lock-screen/background playback in
     * FEED or SERIES remains eligible, even without an Activity surface. */
    fun permitsSession(navigation: PlaybackNavigation, hasUsableSource: Boolean): Boolean =
        !navigation.isExplicitCatalog || hasUsableSource

    fun recoverableCode(code: String): Boolean =
        code.substringBefore(" · ").trim() in setOf(
            "NETWORK_DNS", "NETWORK_TIMEOUT", "NETWORK_CONNECT",
            "NETWORK_EOF", "NETWORK_IO",
            "DEVICE_REGISTER_DNS", "DEVICE_REGISTER_TIMEOUT",
            "PLAYBACK_MODEL_EMPTY", "PLAYBACK_EMPTY_RESPONSE",
            "UPSTREAM_EMPTY_RESPONSE", "MEDIA_IO_ERROR",
        )

    fun recoverableSource(error: Throwable): Boolean =
        recoverableCode(PlaybackProblem.describe("准备播放", error)) ||
            PlaybackSourceRecoveryPolicy.nativeFallback(error)

    fun status(attempt: Int, seconds: Int): String =
        "${seconds} 秒后自动重试第 ${attempt} 次（间隔逐步增加，成功后停止）"
}
