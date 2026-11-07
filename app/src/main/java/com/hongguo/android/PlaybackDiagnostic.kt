package com.hongguo.android

import com.hongguo.api.service.AppSource
import com.hongguo.api.service.PlaybackDevice
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * User-visible, credential-safe diagnostics. Values come exclusively from fixed constants:
 * never serialize Throwable messages, request URLs, headers, account data or media secrets.
 */
data class PlaybackDiagnostic(
    val code: String,
    val stage: String,
    val message: String,
    val attempts: Int = 0,
    val lastRoute: String? = null,
    val lastReason: String? = null,
    val httpStatus: Int = 0,
) {
    fun safeReport(): String = buildString {
        append("Hongguo Android playback diagnostic\ncode=").append(code)
        append("\nstage=").append(stage)
        if (attempts > 0) append("\nroute_attempts=").append(attempts)
        if (lastRoute != null) append("\nlast_route=").append(lastRoute)
        if (lastReason != null) append("\nlast_reason=").append(lastReason)
        if (httpStatus in 100..599) append("\nhttp_status=").append(httpStatus)
    }

    companion object {
        fun fromPreparationFailure(error: Throwable): PlaybackDiagnostic {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            val hint = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
            val trace = chain.asSequence().flatMap { it.suppressed.asSequence() }
                .filterIsInstance<AppSource.AttemptTrace>().firstOrNull()
            val registrationStatus = chain.filterIsInstance<PlaybackDevice.RegistrationHttpException>()
                .firstOrNull()?.status ?: 0
            fun issue(code: String, stage: String, message: String) = PlaybackDiagnostic(
                code, stage, message, trace?.attempts ?: 0, trace?.lastRoute, trace?.lastReason,
                if (registrationStatus != 0) registrationStatus else trace?.httpStatus ?: 0
            )
            val register = causes.any { it is PlaybackDevice.RegistrationException }
            // The registration exception retains a sanitized cause when transport has a
            // known category. HTTP and malformed-response errors remain generic here.
            if (register) return when {
                registrationStatus != 0 ->
                    issue("DEVICE_REGISTER_HTTP_FAILED", "device_register", "设备注册服务返回错误状态，请稍后重试。")
                causes.any { it is UnknownHostException } ->
                    issue("DEVICE_REGISTER_DNS_FAILED", "device_register", "设备注册域名解析失败，请检查网络和 DNS。")
                causes.any { it is SocketTimeoutException } ->
                    issue("DEVICE_REGISTER_TIMEOUT", "device_register", "设备注册连接超时，请稍后重试。")
                causes.any { it is ConnectException } ->
                    issue("DEVICE_REGISTER_CONNECT_FAILED", "device_register", "无法连接设备注册服务，请检查当前网络路由。")
                causes.any { it is SSLException } ->
                    issue("DEVICE_REGISTER_TLS_FAILED", "device_register", "设备注册安全连接失败，请检查设备时间和网络。")
                else -> issue("DEVICE_REGISTER_FAILED", "device_register", "设备注册未完成，请检查网络后重试。")
            }
            return when {
                hint.contains("bytevc2") || hint.contains("没有兼容") || hint.contains("暂未提供兼容") ->
                    issue("CODEC_UNSUPPORTED", "codec_select", "本集没有可用的兼容编码，请尝试其他剧集。")
                hint.contains("app 接口返回空响应") ->
                    issue("VIDEO_MODEL_EMPTY", "multi_video_model", "片源接口返回空数据，稍后重试。")
                hint.contains("app 接口未返回所选分集") || hint.contains("app 接口未返回 video_model") ||
                    hint.contains("video_tab 模型不完整") || hint.contains("app 接口未返回播放数据") ||
                    hint.contains("播放信息格式无效") ->
                    issue("VIDEO_MODEL_INVALID", "multi_video_model", "片源接口没有返回当前分集的完整模型。")
                hint.contains("app 接口 http") ->
                    issue("VIDEO_MODEL_HTTP", "multi_video_model", "片源接口没有完成请求，请稍后重试。")
                causes.any { it is UnknownHostException } ->
                    issue("PLAYBACK_DNS_FAILED", "network", "内容服务域名解析失败，请检查网络。")
                causes.any { it is SocketTimeoutException } ->
                    issue("PLAYBACK_TIMEOUT", "network", "播放请求超时，请稍后重试。")
                causes.any { it is ConnectException } ->
                    issue("PLAYBACK_CONNECT_FAILED", "network", "无法建立播放连接，请检查当前网络。")
                causes.any { it is SSLException } ->
                    issue("PLAYBACK_TLS_FAILED", "network", "播放安全连接失败，请检查设备时间。")
                hint.contains("enospc") || hint.contains("no space") ->
                    issue("MEDIA_STORAGE_FULL", "media_cache", "设备存储空间不足，请清理缓存或空间后重试。")
                hint.contains("cenc") || hint.contains("解密") || hint.contains("密钥") || hint.contains("封装") ->
                    issue("MEDIA_FORMAT_UNSUPPORTED", "media_prepare", "媒体格式或密钥暂不兼容，请选择其他剧集。")
                causes.any { it is IOException } ->
                    issue("MEDIA_PREPARE_IO_FAILED", "media_prepare", "播放准备过程中读取失败，请稍后重试。")
                else ->
                    issue("PLAYBACK_PREPARE_FAILED", "playback_prepare", "视频准备失败，请复制诊断信息后重试。")
            }
        }

        fun playerFailure(unsupported: Boolean): PlaybackDiagnostic =
            if (unsupported) PlaybackDiagnostic("PLAYER_DECODER_UNSUPPORTED", "media_decoder",
                "当前设备无法解码这集视频，可重试或选择其他集。")
            else PlaybackDiagnostic("PLAYER_PLAYBACK_FAILED", "media_player",
                "视频播放失败，可重试或选择其他集。")
    }
}
