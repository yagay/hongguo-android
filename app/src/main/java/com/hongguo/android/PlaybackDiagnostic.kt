package com.hongguo.android

import com.hongguo.api.service.AppSource
import com.hongguo.api.service.PlaybackDevice
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * Only fixed, reviewable diagnostic fields reach the screen or clipboard.
 * Never include a raw Throwable, signed URL, response body, Cookie, device ID or media key.
 */
data class PlaybackDiagnostic(
    val code: String,
    val stage: String,
    val stageLabel: String,
    val message: String,
    val attempts: Int? = null,
    val httpStatus: Int? = null,
    val responseBytes: Int? = null,
    val primaryTransportFailure: String? = null,
    val fallbackTransportFailure: String? = null,
) {
    /** A privacy-safe support report. No episode, account, signature or provider data. */
    fun safeReport(): String = buildString {
        append("Hongguo Android playback diagnostic\n")
        append("code=").append(code).append('\n')
        append("stage=").append(stage).append('\n')
        attempts?.takeIf { it > 0 }?.let { append("attempts=").append(it).append('\n') }
        httpStatus?.takeIf { it in 100..599 }?.let { append("http_status=").append(it).append('\n') }
        responseBytes?.takeIf { it >= 0 }?.let { append("response_bytes=").append(it).append('\n') }
        // Only fixed allowlisted diagnostic identifiers; never append exception messages.
        val codePattern = Regex("^DEVICE_REGISTER_[A-Z0-9_]{1,48}$")
        primaryTransportFailure?.takeIf { codePattern.matches(it) }?.let {
            append("primary_transport=").append(it).append('\n')
        }
        fallbackTransportFailure?.takeIf { codePattern.matches(it) }?.let {
            append("fallback_transport=").append(it).append('\n')
        }
    }

    companion object {
        fun fromPreparationFailure(error: Throwable): PlaybackDiagnostic {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            val registration = causes.filterIsInstance<PlaybackDevice.RegistrationException>().firstOrNull()
            val http = causes.filterIsInstance<AppSource.PlaybackHttpException>().firstOrNull()
            val empty = causes.filterIsInstance<AppSource.EmptyPlaybackResponseException>().firstOrNull()
            val unavailable = causes.filterIsInstance<AppSource.PlaybackModelUnavailableException>().firstOrNull()
            val hint = causes.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
            fun issue(code: String, stage: String, label: String, message: String, attempts: Int? = null,
                      httpStatus: Int? = null, responseBytes: Int? = null) =
                PlaybackDiagnostic(code, stage, label, message, attempts, httpStatus, responseBytes)

            return when {
                registration != null ->
                    fromRegistrationFailure(registration)
                hint.contains("匿名播放设备注册失败") ->
                    issue("DEVICE_REGISTER_FAILED", "device_register", "设备注册",
                        "播放设备初始化失败，但未取得更具体的原因，请稍后重试。")
                // Unsupported codecs must not be represented as a network error.
                hint.contains("bytevc2") || hint.contains("本集暂未提供兼容的") ->
                    issue("CODEC_UNSUPPORTED", "codec_select", "视频编码",
                        "这一集没有客户端支持的视频编码。可尝试其他剧集或官方播放器。")
                empty != null ->
                    issue("VIDEO_MODEL_EMPTY", "multi_video_model", "播放接口",
                        "播放接口返回了空数据。稍后重试，若持续发生请复制诊断信息。",
                        empty.attempts.takeIf { it > 0 }, responseBytes = 0)
                http != null ->
                    issue("VIDEO_MODEL_HTTP", "multi_video_model", "播放接口",
                        "播放接口暂时拒绝或无法完成请求（HTTP ${http.status}）。稍后重试。",
                        http.attempts.takeIf { it > 0 }, httpStatus = http.status)
                unavailable != null ->
                    issue("VIDEO_MODEL_INVALID", "multi_video_model", "播放接口",
                        "播放接口没有返回可用的剧集信息，请尝试其他剧集。",
                        unavailable.attempts.takeIf { it > 0 })
                hint.contains("视频模型") || hint.contains("video_model") ||
                    hint.contains("播放信息格式无效") || hint.contains("app 接口未返回") ||
                    hint.contains("app 接口暂未提供") ->
                    issue("VIDEO_MODEL_INVALID", "multi_video_model", "播放接口",
                        "播放接口返回的剧集信息无法解析，请稍后重试。")
                hint.contains("no space") || hint.contains("enospc") ->
                    issue("MEDIA_STORAGE_FULL", "media_cache", "视频缓存",
                        "本机存储空间不足，请清理空间后重试。")
                hint.contains("密钥") || hint.contains("解密") || hint.contains("cenc") ||
                    hint.contains("媒体校验") || hint.contains("媒体样本") || hint.contains("媒体索引") ||
                    hint.contains("封装") ->
                    issue("MEDIA_FORMAT_UNSUPPORTED", "media_prepare", "媒体准备",
                        "媒体格式或解密数据不兼容，换一集重试。")
                hint.contains("片源") || hint.contains("不受支持的服务地址") ||
                    hint.contains("无直链") ->
                    issue("MEDIA_SOURCE_INVALID", "media_source", "片源解析",
                        "当前剧集的片源不可用，请重试或选择其他剧集。")
                causes.any { it is UnknownHostException } ->
                    issue("PLAYBACK_DNS_FAILED", "network", "网络连接",
                        "无法连接到内容服务，请检查网络和 DNS 设置。")
                causes.any { it is SocketTimeoutException } ->
                    issue("PLAYBACK_TIMEOUT", "network", "网络连接",
                        "获取播放信息超时，请稍后重试。")
                causes.any { it is SSLException } ->
                    issue("PLAYBACK_TLS_FAILED", "network", "安全连接",
                        "安全连接失败，请检查网络和设备时间。")
                hint.contains("网络请求失败") || hint.contains("网络请求地址无效") ->
                    issue("PLAYBACK_NETWORK_FAILED", "network", "网络连接",
                        "播放请求未完成，请检查网络后重试。")
                causes.any { it is IOException } ->
                    issue("MEDIA_PREPARE_IO_FAILED", "media_prepare", "媒体准备",
                        "视频读取或准备失败，请稍后重试。")
                else ->
                    issue("PLAYBACK_PREPARE_FAILED", "playback_prepare", "播放准备",
                        "视频准备失败，请复制诊断信息并重试。")
            }
        }

        private fun fromRegistrationFailure(error: PlaybackDevice.RegistrationException): PlaybackDiagnostic {
            val status = error.httpStatus.takeIf { it in 100..599 }
            val bytes = error.responseBytes.takeIf { it >= 0 }
            val message = when (error.code) {
                "DEVICE_REGISTER_DNS_FAILED" -> "无法解析注册服务域名，请检查当前网络或 DNS 设置。"
                "DEVICE_REGISTER_TIMEOUT" -> "注册服务响应超时，请切换网络或稍后重试。"
                "DEVICE_REGISTER_TLS_FAILED" -> "注册服务安全连接失败，请检查网络和设备时间。"
                "DEVICE_REGISTER_HTTP" -> "注册服务返回 HTTP ${status ?: "错误"}，请稍后重试。"
                "DEVICE_REGISTER_EMPTY" -> "注册服务返回空响应，暂时无法取得播放身份。"
                "DEVICE_REGISTER_INVALID_JSON" -> "注册服务响应格式无效，暂时无法初始化播放。"
                "DEVICE_REGISTER_INVALID_IDS" -> "注册服务未提供有效设备编号，无法获取播放信息。"
                "DEVICE_REGISTER_REJECTED" -> "注册服务未接受本次请求，暂时无法初始化播放。"
                "DEVICE_REGISTER_RESPONSE_TOO_LARGE" -> "注册服务响应不符合客户端处理限制。"
                "DEVICE_REGISTER_NETWORK_FAILED" -> "无法完成注册请求，请检查网络、代理或服务连接后重试。"
                "DEVICE_REGISTER_CONNECT_FAILED" -> "注册服务无法建立 TCP 连接，请尝试其他网络。"
                "DEVICE_REGISTER_SOCKET_FAILED" -> "注册连接被中断，请检查网络、代理及防火墙设置。"
                "DEVICE_REGISTER_UNREACHABLE" -> "当前网络无法到达注册服务，请尝试其他网络。"
                "DEVICE_REGISTER_EOF" -> "注册服务提前关闭连接，请尝试其他网络或稍后重试。"
                "DEVICE_REGISTER_PROTOCOL_FAILED" -> "注册服务传输协议不兼容，请稍后重试。"
                else -> "播放设备初始化失败，请稍后重试。"
            }
            return PlaybackDiagnostic(
                code = error.code,
                stage = "device_register",
                stageLabel = "设备注册",
                message = message,
                httpStatus = status,
                responseBytes = bytes,
                primaryTransportFailure = error.primaryTransportFailure,
                fallbackTransportFailure = error.fallbackTransportFailure
            )
        }

        fun playerFailure(unsupportedCodec: Boolean): PlaybackDiagnostic =
            if (unsupportedCodec) PlaybackDiagnostic(
                "PLAYER_DECODER_UNSUPPORTED", "media_decoder", "视频解码",
                "当前设备无法解码这集视频，可重试或选择其他集。"
            ) else PlaybackDiagnostic(
                "PLAYER_PLAYBACK_FAILED", "media_player", "视频播放器",
                "播放器无法完成视频加载，可重试或选择其他集。"
            )
    }
}
