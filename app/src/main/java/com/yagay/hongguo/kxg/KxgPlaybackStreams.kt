package com.yagay.hongguo.kxg

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.yagay.hongguo.OpenHongguoSource
import com.yagay.hongguo.DiagnosticLog
import dev.openhongguo.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
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
    data class Prepared(
        val uri: String, val episodeId: String, val quality: Int,
        val validUntilMs: Long = 0L,
    )
    private val preloadLock = Any()
    private val prefetched = LinkedHashMap<String, Prepared>()
    private var preloadGeneration = 0L
    private fun cacheKey(episodeId: String, quality: Int) = "$episodeId:$quality"

    private fun takePrefetched(episodeId: String, quality: Int): Prepared? {
        val key = cacheKey(episodeId, quality)
        val cached = synchronized(preloadLock) { prefetched.remove(key) } ?: return null
        val now = System.currentTimeMillis()
        return if (KxgWarmupPolicy.reusable(cached.validUntilMs, now) &&
            streamRegistry?.get(cached.uri) != null) {
            diagnostics?.record("PRELOAD", "MODEL_HIT")
            cached
        } else {
            forget(cached.uri)
            diagnostics?.record("PRELOAD", "MODEL_EXPIRED")
            null
        }
    }

    /** Discard only unclaimed future models, never the current player's source. */
    fun clearPrefetched() {
        val stale = synchronized(preloadLock) {
            preloadGeneration++
            prefetched.values.toList().also { prefetched.clear() }
        }
        stale.forEach { forget(it.uri) }
    }

    @Volatile private var streamRegistry: StreamRegistry? = null
    @Volatile private var networkFactory: DataSource.Factory? = null
    @Volatile private var diagnostics: DiagnosticLog? = null

    /** Called by the service. Logs only safe transport timings and byte counts. */
    fun attachDiagnostics(log: DiagnosticLog) {
        diagnostics = log
    }

    private fun registry(context: Context): StreamRegistry {
        streamRegistry?.let { return it }
        return synchronized(this) {
            streamRegistry ?: run {
                val network = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(CdnStreamingPolicy.CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(CdnStreamingPolicy.READ_TIMEOUT_MS)
                    .setAllowCrossProtocolRedirects(true)
                networkFactory = network
                StreamRegistry(network) { event, duration, status, bytes ->
                    diagnostics?.record("CDN", event, duration, status, bytes)
                }.also { streamRegistry = it }
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
    ): Prepared {
        takePrefetched(episodeId, qualityLimit)?.let { return it }
        return resolveFresh(context, sdk, episodeId, qualityLimit)
    }

    /**
     * Foreground never waits for an in-flight speculative fetch; only completed
     * models are reusable. Publish the model before warming video bytes so an
     * early swipe can already reuse it.
     */
    suspend fun prefetch(
        context: Context,
        sdk: OpenHongguoSource,
        episodeId: String,
        qualityLimit: Int,
    ): Boolean {
        val key = cacheKey(episodeId, qualityLimit)
        val now = System.currentTimeMillis()
        val generation = synchronized(preloadLock) {
            if (prefetched[key]?.let { KxgWarmupPolicy.reusable(it.validUntilMs, now) } == true)
                return true
            preloadGeneration
        }
        val prepared = resolveFresh(context, sdk, episodeId, qualityLimit)
        if (!KxgWarmupPolicy.reusable(prepared.validUntilMs, System.currentTimeMillis())) {
            forget(prepared.uri)
            diagnostics?.record("PRELOAD", "MODEL_TOO_CLOSE_TO_EXPIRY")
            return false
        }
        val evicted = synchronized(preloadLock) {
            if (generation != preloadGeneration) null else {
                val previous = prefetched.put(key, prepared)
                buildList<Prepared> {
                    if (previous != null) add(previous)
                    while (prefetched.size > KxgWarmupPolicy.MAX_CACHED_MODELS) {
                        val oldestKey = prefetched.keys.first()
                        add(checkNotNull(prefetched.remove(oldestKey)))
                    }
                }
            }
        }
        if (evicted == null) {
            forget(prepared.uri)
            diagnostics?.record("PRELOAD", "STALE_SESSION_DISCARDED")
            return false
        }
        evicted.forEach { forget(it.uri) }
        diagnostics?.record("PRELOAD", "MODEL_READY")
        // Connection timeout or cancellation must never block a foreground swap.
        try {
            withTimeout(KxgWarmupPolicy.WARMUP_DEADLINE_MS) {
                runInterruptible(Dispatchers.IO) {
                    registry(context.applicationContext)[prepared.uri]
                        ?.warmEncryptedPrefix()
                }
            }
        } catch (e: TimeoutCancellationException) {
            diagnostics?.record("PRELOAD", "PREFIX_DEADLINE")
        } catch (e: CancellationException) {
            diagnostics?.record("PRELOAD", "CANCELLED")
            throw e
        } catch (e: Exception) {
            diagnostics?.record("PRELOAD", "PREFIX_UNAVAILABLE")
        }
        return true
    }

    private suspend fun resolveFresh(
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
        streams.record("SOURCE_REGISTERED_${urls.size}_HOSTS")
        val validUntil = KxgWarmupPolicy.cacheValidUntilMs(
            playback.expiresAt, System.currentTimeMillis())
        Prepared(uri, episodeId, qualityLimit, validUntil)
    }

    private fun lines(stream: Stream): Int {
        val label = stream.definition.takeWhile { it in '0'..'9' }.toIntOrNull()
        if (label != null && label > 0) return label
        val shorter = minOf(stream.width, stream.height)
        return if (shorter > 0) shorter else maxOf(stream.width, stream.height)
    }
}
