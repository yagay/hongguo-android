package com.hongguo.rewrite

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
    fun describe(stage: String, failure: Throwable): String {
        val chain = generateSequence(failure) { it.cause }.take(8).toList()
        val messages = chain.mapNotNull { it.message }
        val code = when {
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
            messages.any { it.contains("bytevc2", true) || it.contains("VVC", true) } -> "CODEC_NOT_SUPPORTED"
            chain.any { it is UnsupportedOperationException } -> "SOURCE_NOT_SUPPORTED"
            chain.any { it is IOException } -> "NETWORK_IO"
            else -> "UNEXPECTED"
        }
        val hint = when (code) {
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
