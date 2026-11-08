package com.hongguo.rewrite

import com.hongguotv.core.NoNewRecommendationsException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing feed diagnostics are stable codes only. Never publish raw
 * upstream responses, exception messages, URLs or account credentials.
 */
internal object OfficialReelsFailure {
    fun code(failure: Throwable): String {
        val causes = generateSequence(failure) { it.cause }.take(8).toList()
        val messages = causes.mapNotNull { it.message }
        return when {
            causes.any { it is NoNewRecommendationsException } -> "NO_NEW"
            messages.any { it == "REELS_ACCOUNT_ACCESS_REJECTED" ||
                it.contains("错误码 110") } -> "ACCOUNT_ACCESS_REJECTED"
            messages.any { it.contains("HTTP 401") || it.contains("HTTP 403") } ->
                "HTTP_ACCESS_DENIED"
            messages.any { it.contains("HTTP 429") } -> "RATE_LIMIT"
            messages.any { it.contains("HTTP 500") || it.contains("HTTP 502") ||
                it.contains("HTTP 503") || it.contains("HTTP 504") } ->
                "SERVER_UNAVAILABLE"
            causes.any { it is UnknownHostException } -> "NETWORK_DNS"
            causes.any { it is SocketTimeoutException } -> "NETWORK_TIMEOUT"
            causes.any { it is ConnectException } -> "NETWORK_CONNECT"
            causes.any { it is EOFException } -> "NETWORK_EOF"
            messages.any { it.contains("推荐接口返回空响应") } -> "EMPTY_RESPONSE"
            messages.any { it.contains("推荐接口响应过大") } -> "RESPONSE_TOO_LARGE"
            messages.any { it.contains("推荐未返回可播放的剧集") } -> "NO_PLAYABLE"
            messages.any { it.contains("推荐数据格式已变化") ||
                it.contains("分类推荐数据格式已变化") ||
                it.contains("推荐续刷数据格式已变化") } -> "INVALID_RESPONSE"
            causes.any { it is IOException } -> "NETWORK_IO"
            else -> "UNEXPECTED"
        }
    }

    fun userMessage(failure: Throwable): String = when (code(failure)) {
        "NO_NEW", "NO_PLAYABLE" ->
            "官方本次没有返回新的可播放短剧，可稍后重试或切换推荐类型"
        "ACCOUNT_ACCESS_REJECTED", "HTTP_ACCESS_DENIED" ->
            "官方推荐接口暂未接受当前登录会话，请稍后重试；也可在设置中关闭账号推荐后测试匿名模式"
        "RATE_LIMIT" -> "官方推荐请求暂时受限，请稍后再试"
        "SERVER_UNAVAILABLE", "EMPTY_RESPONSE" ->
            "官方推荐服务暂时没有正常响应，请稍后重试"
        "NETWORK_DNS", "NETWORK_CONNECT", "NETWORK_TIMEOUT", "NETWORK_EOF" ->
            "官方推荐连接失败，请检查网络并重试"
        "RESPONSE_TOO_LARGE", "INVALID_RESPONSE" ->
            "官方推荐响应格式异常，请更新应用或稍后重试"
        else -> "官方刷剧推荐获取失败，可重试；详情见诊断日志中的 REELS 错误码"
    }
}
