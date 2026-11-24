package com.hongguo.android

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never expose provider response bodies, signed URLs or device identifiers in UI. */
fun readableFailure(error: Throwable, action: String): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val hint = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    chain.firstNotNullOfOrNull { it as? NoNewRecommendationsException }?.let {
        return "推荐接口暂未返回新作品（最近已去重 ${it.remembered} 部），可稍后刷新，或切换其他分类继续发现。"
    }
    return when {
        chain.any { it is UnknownHostException } -> "网络连接不可用，请检查网络后重试。"
        chain.any { it is SocketTimeoutException } -> "连接超时，请稍后重试。"
        chain.any { it is SSLException } -> "安全连接失败，请检查网络和设备时间。"
        hint.contains("no space") || hint.contains("enospc") -> "设备存储空间不足，请清理空间后重试。"
        hint.contains("上游未返回数据") -> "内容服务暂未返回数据，请稍后重试。"
        hint.contains("empty_episodes") -> "暂时没有获取到可播放剧集，请稍后重试。"
        hint.contains("风控") || hint.contains("risk") || hint.contains("429") -> "片源服务暂时限制访问，请稍后重试。"
        hint.contains("无直链") || hint.contains("video_model") || hint.contains("片源") -> "该集片源暂时不可用，可重试或选择其他集。"
        hint.contains("解密") || hint.contains("cenc") || hint.contains("decrypt") ||
            hint.contains("封装") || hint.contains("媒体校验") || hint.contains("密钥格式") ||
            hint.contains("媒体索引") || hint.contains("媒体样本") -> "该集媒体格式暂不支持，可重试或选择其他集。"
        else -> "${action}失败，请检查网络后重试。"
    }
}
