package com.yagay.hongguo.kxg

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.yagay.hongguo.OpenHongguoSource
import dev.openhongguo.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The rewrite app keeps its ONE service-owned ExoPlayer. This is the original
 * Kaixinguo transport/decrypting DataSource behind that player, not another
 * player instance. Only anonymous SDK playback models enter this registry.
 *
 * Based on kadaliao/kaixinguo-android (MIT); see THIRD_PARTY.md.
 */
internal object KxgPlaybackStreams {
    data class Prepared(val uri: String, val episodeId: String, val quality: Int)

    @Volatile private var streamRegistry: StreamRegistry? = null
    @Volatile private var networkFactory: DataSource.Factory? = null

    private fun registry(context: Context): StreamRegistry {
        streamRegistry?.let { return it }
        return synchronized(this) {
            streamRegistry ?: run {
                val network = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true)
                networkFactory = network
                StreamRegistry(network).also { streamRegistry = it }
            }
        }
    }

    fun factory(context: Context): DataSource.Factory {
        val streams = registry(context.applicationContext)
        val http = checkNotNull(networkFactory)
        return CencDataSource.Factory(streams, http)
    }

    fun forget(uri: String) {
        streamRegistry?.forget(uri)
    }

    suspend fun resolve(
        context: Context,
        sdk: OpenHongguoSource,
        episodeId: String,
        qualityLimit: Int,
    ): Prepared = withContext(Dispatchers.IO) {
        val resolved = sdk.playbackModel(episodeId)
        val playback = resolved.playback
        val usable = playback.streams.filter {
            it.url.startsWith("https://") && !it.hls && !it.dash
        }
        if (usable.isEmpty() || playback.audioUrl.isNotEmpty()) {
            throw IOException("该集没有开心果支持的单文件 MP4 视频")
        }
        // Same resolution preference as Kaixinguo: the best stream not
        // above the selected cap, or the lowest available if none fits.
        val capped = if (qualityLimit > 0)
            usable.filter { lines(it) <= qualityLimit } else usable
        val order = compareBy<Stream>({ lines(it) }, { it.bitrate })
        val chosen = (if (capped.isEmpty()) usable.minWithOrNull(order)
            else capped.maxWithOrNull(order))
            ?: throw IOException("没有可播放的视频清晰度")
        val key: ByteArray? = if (playback.protected || chosen.protected) {
            val secret = resolved.contentKey
                ?: throw IOException("当前播放模型没有可用的内容密钥")
            val hex = secret.key.trim()
            if (hex.length != 32 || !hex.all { it in "0123456789abcdefABCDEF" }) {
                throw IOException("内容密钥格式不受支持")
            }
            ByteArray(16) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } else null
        val urls = listOf(chosen.url.trim(), chosen.backupUrl.trim())
            .filter { it.startsWith("https://") }.distinct()
        // Like Kaixinguo upstream: register directly. CencDataSource.open()
        // resolves the header and decrypts on Media3's loader thread.
        val streams = registry(context.applicationContext)
        val uri = streams.register(urls, key)
        Prepared(uri, episodeId, qualityLimit)
    }

    private fun lines(stream: Stream): Int {
        val label = stream.definition.takeWhile { it in '0'..'9' }.toIntOrNull()
        if (label != null && label > 0) return label
        val shorter = minOf(stream.width, stream.height)
        return if (shorter > 0) shorter else maxOf(stream.width, stream.height)
    }
}
