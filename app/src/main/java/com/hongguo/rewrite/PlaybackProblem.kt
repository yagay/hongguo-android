package com.hongguo.rewrite

import com.hongguotv.core.PlaybackDeviceRegistrationException
import com.hongguotv.core.EmptyPlaybackResponseException
import com.hongguotv.core.ContentHttpException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Sanitized, stage-aware diagnostics based on hongguoTV's PlaybackSources
 * and PlaybackRequestTrace. Never include request URLs, signed query, cookies,
 * account information or a raw upstream response in the UI.
 */
object PlaybackProblem {
    /** A typed upstream failure, unlike network or decoder errors, is eligible
     * for one bounded playback-model request retry.
     */
    fun isEmptyPlaybackModel(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(8)
            .any { it is EmptyPlaybackResponseException }

    /**
     * Only transient transport errors may be retried during active playback.
     * Authorization, rate limits, invalid responses and unsupported codecs
     * must fail explicitly instead of hammering upstream endpoints.
     */
    fun isTransientTransport(failure: Throwable): Boolean {
        // Reuse the existing detailed classification, including HTTP status
        // and CODEC_NOT_SUPPORTED carried as IOException messages.
        // Otherwise a bad codec or rejected request might be retried as IO.
        return describe("播放模型", failure).substringBefore(" · ") in setOf(
            "NETWORK_DNS", "NETWORK_TIMEOUT", "NETWORK_CONNECT",
            "NETWORK_EOF", "NETWORK_IO",
        )
    }

    fun describe(stage: String, failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val messages = chain.mapNotNull { it.message }
        val code = when {
            chain.any { it is PlaybackDeviceRegistrationException } && chain.any { it is UnknownHostException } ->
                "DEVICE_REGISTER_DNS"
            chain.any { it is PlaybackDeviceRegistrationException } && chain.any { it is SocketTimeoutException } ->
                "DEVICE_REGISTER_TIMEOUT"
            chain.any { it is PlaybackDeviceRegistrationException } ->
                "DEVICE_REGISTER_FAILED"
            chain.any { it is EmptyPlaybackResponseException } -> "PLAYBACK_EMPTY_RESPONSE"
            chain.any { it is ContentHttpException && it.statusCode == 429 } -> "UPSTREAM_RATE_LIMIT"
            chain.any { it is ContentHttpException && it.statusCode == 403 } -> "UPSTREAM_FORBIDDEN"
            chain.any { it is ContentHttpException } -> "UPSTREAM_HTTP_ERROR"
            chain.any { it is UnknownHostException } -> "NETWORK_DNS"
            chain.any { it is SocketTimeoutException } -> "NETWORK_TIMEOUT"
            chain.any { it is ConnectException } -> "NETWORK_CONNECT"
            chain.any { it is EOFException } -> "NETWORK_EOF"
            messages.any { it.contains("WEBSITE_STRUCTURE_CHANGED") } -> "WEBSITE_STRUCTURE_CHANGED"
            messages.any { it.contains("EPISODES_INVALID") } -> "EPISODES_INVALID"
            messages.any { it.contains("EMPTY_RESPONSE") } -> "UPSTREAM_EMPTY_RESPONSE"
            messages.any { it.contains("INVALID_JSON") } -> "UPSTREAM_INVALID_JSON"
            messages.any { it.contains("WEBSITE_HTTP_403") || it.contains("HTTP 403") } -> "UPSTREAM_FORBIDDEN"
            messages.any { it.contains("WEBSITE_HTTP_401") || it.contains("HTTP 401") } -> "UPSTREAM_UNAUTHORIZED"
            messages.any { it.contains("WEBSITE_HTTP_") || it.contains("HTTP ") } -> "UPSTREAM_HTTP_ERROR"
            messages.any { it.contains("DEVICE_REGISTER", true) } -> "DEVICE_REGISTER_FAILED"
            messages.any { it.contains("CODEC_NOT_SUPPORTED", true) ||
                it.contains("bytevc2", true) || it.contains("VVC", true) } -> "CODEC_NOT_SUPPORTED"
            chain.any { it is UnsupportedOperationException } -> "SOURCE_NOT_SUPPORTED"
            chain.any { it is IOException } -> "NETWORK_IO"
            else -> "UNEXPECTED"
        }
        val hint = when (code) {
            "DEVICE_REGISTER_DNS" -> "设备注册阶段无法解析服务器域名"
            "DEVICE_REGISTER_TIMEOUT" -> "设备注册阶段请求超时"
            "PLAYBACK_EMPTY_RESPONSE" -> "播放模型接口返回空响应，上游暂不可用"
            "UPSTREAM_RATE_LIMIT" -> "上游请求频率受限"
            "NETWORK_DNS" -> "无法解析服务器域名"
            "NETWORK_TIMEOUT" -> "网络请求超时"
            "NETWORK_CONNECT" -> "无法连接服务器"
            "NETWORK_EOF" -> "视频或网络响应提前中断"
            "WEBSITE_STRUCTURE_CHANGED" -> "官网数据结构已变化"
            "EPISODES_INVALID" -> "剧集列表数据异常"
            "UPSTREAM_EMPTY_RESPONSE" -> "接口返回空响应"
            "UPSTREAM_INVALID_JSON" -> "接口响应格式错误"
            "UPSTREAM_FORBIDDEN" -> "内容接口拒绝访问"
            "UPSTREAM_UNAUTHORIZED" -> "会话认证已失效"
            "UPSTREAM_HTTP_ERROR" -> "接口请求失败"
            "DEVICE_REGISTER_FAILED" -> "匿名设备注册失败"
            "CODEC_NOT_SUPPORTED" -> "本机不支持该视频编码"
            "SOURCE_NOT_SUPPORTED" -> "当前数据源尚不支持此操作"
            "NETWORK_IO" -> "网络数据读取失败"
            else -> "处理失败"
        }
        return code + " · " + stage + "：" + hint
    }

    fun media(errorCode: String, causeClass: String?): String {
        val code = when {
            errorCode.contains("DECODING", true) || errorCode.contains("FORMAT_UNSUPPORTED", true) ->
                "MEDIA_CODEC_UNSUPPORTED"
            errorCode.contains("BAD_HTTP_STATUS", true) -> "MEDIA_HTTP_ERROR"
            errorCode.contains("NETWORK", true) || errorCode.contains("IO", true) -> "MEDIA_IO_ERROR"
            else -> "MEDIA_PLAYER_ERROR"
        }
        val suffix = causeClass?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) }
        return code + " · " + errorCode.take(80) +
            (if (suffix != null) " · " + suffix else "")
    }
}
