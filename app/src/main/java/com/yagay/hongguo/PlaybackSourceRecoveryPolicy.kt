package com.yagay.hongguo

import com.hongguotv.core.ContentHttpException
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import java.io.IOException

/**
 * Source-selection policy; no cookies are transferred between the anonymous
 * Kaixinguo SDK and the separate registered first-party playback device.
 * SDK internal retries/device resets run before any outer retry.
 */
internal object PlaybackSourceRecoveryPolicy {
    private const val MAX_OUTER_SDK_ATTEMPTS = 2

    fun retrySdk(error: Throwable, attempt: Int): Boolean {
        if (attempt !in 1 until MAX_OUTER_SDK_ATTEMPTS) return false
        val sdk = causes(error).filterIsInstance<OpenhongguoException>().firstOrNull()
        if (sdk != null) {
            if (sdk.code == ErrorCode.RATE_LIMITED) return false
            return sdk.retryable && sdk.code == ErrorCode.TRANSPORT
        }
        return PlaybackProblem.isTransientTransport(error) ||
            httpStatus(error) in setOf(500, 502, 503, 504)
    }

    fun retryDelayMs(error: Throwable): Long {
        val sdk = causes(error).filterIsInstance<OpenhongguoException>().firstOrNull()
        return (sdk?.retryAfterMs ?: 0L).coerceAtLeast(1_500L).coerceAtMost(8_000L)
    }

    /** Permission/denial/rate-limit errors must not be bypassed by another source. */
    fun nativeFallback(error: Throwable): Boolean {
        val sdk = causes(error).filterIsInstance<OpenhongguoException>().firstOrNull()
        // An empty HTTP-200 model is not proof of a service denial.
        // The separate same-identity native resolver may make one bounded
        // attempt using different supported model formats. Never retry
        // AUTHENTICATION_REQUIRED, ANONYMOUS_LIMITED, RATE_LIMITED, device
        // rejection, signatures or a protected/unsupported episode.
        if (sdk != null) return sdk.code in setOf(
            ErrorCode.TRANSPORT, ErrorCode.RESPONSE_EMPTY)
        val status = httpStatus(error)
        if (status != null && status !in setOf(500, 502, 503, 504)) return false
        if (PlaybackProblem.isEmptyPlaybackModel(error) ||
            PlaybackProblem.isTransientTransport(error) ||
            status in setOf(500, 502, 503, 504)) return true
        val messages = causes(error).mapNotNull { it.message }
        return messages.any {
            it.contains("没有开心果支持的单文件 MP4") ||
                it.contains("没有可播放的视频清晰度")
        }
    }

    /**
     * The legacy native model endpoint can return HTTP 200 with an empty body
     * after the primary V5 SDK has already given a precise typed failure.
     * Do not overwrite that evidence with an unhelpful legacy empty-response
     * error. Keep all other fallback failures authoritative.
     */
    fun preservePrimaryOnLegacyEmpty(primary: Exception?, fallback: Exception): Exception =
        if (primary != null && PlaybackProblem.isEmptyPlaybackModel(fallback)) primary else fallback

    private fun causes(t: Throwable): List<Throwable> =
        generateSequence(t) { it.cause }.take(8).toList()

    private fun httpStatus(t: Throwable): Int? =
        causes(t).filterIsInstance<ContentHttpException>().firstOrNull()?.statusCode
}
