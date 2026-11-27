package com.yagay.hongguo.kxg

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.yagay.hongguo.OpenHongguoSource
import com.yagay.hongguo.DiagnosticLog
import dev.openhongguo.OpenhongguoException
import dev.openhongguo.ErrorCode
import com.yagay.hongguo.PlaybackProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.File
import java.util.UUID

/**
 * The rewrite app keeps its ONE service-owned ExoPlayer. The ONLY playback
 * model API is official beilunyang/openhongguo 0.4.0 resolvePlayback().
 * This file merely adapts the official CDN URLs and CENC key to Media3
 * encrypted-range cache/decryption, not a Kaixinguo playback API or player.
 *
 * Transport implementation adapted from kadaliao/kaixinguo-android (MIT);
 * see THIRD_PARTY.md. The signed upstream model comes directly from openhongguo.
 */
internal object KxgPlaybackStreams {
    data class Prepared(
        val uri: String, val episodeId: String, val quality: Int,
        val validUntilMs: Long = 0L,
    )
    private val preloadLock = Any()
    private val prefetched = LinkedHashMap<String, Prepared>()
    /** Recent source handoff keeps partially/fully downloaded bytes useful
     * when the user swipes backward. Signed expiry still applies. */
    private val recentPlayed = LinkedHashMap<String, Prepared>()
    private var preloadGeneration = 0L

    private fun rememberPlayed(prepared: Prepared) = synchronized(preloadLock) {
        val key = cacheKey(prepared.episodeId, prepared.quality)
        recentPlayed.remove(key)
        recentPlayed[key] = prepared
        while (recentPlayed.size > 8)
            recentPlayed.remove(recentPlayed.keys.first())
    }

    private fun recentSource(episodeId: String, quality: Int): Prepared? {
        val key = cacheKey(episodeId, quality)
        val found = synchronized(preloadLock) { recentPlayed[key] } ?: return null
        val current = KxgWarmupPolicy.reusable(
            found.validUntilMs, System.currentTimeMillis()) &&
            streamRegistry?.get(found.uri) != null
        if (!current) {
            synchronized(preloadLock) {
                if (recentPlayed[key] == found) recentPlayed.remove(key)
            }
            diagnostics?.record("CACHE", "RECENT_MODEL_EXPIRED")
            return null
        }
        synchronized(preloadLock) {
            recentPlayed.remove(key)
            recentPlayed[key] = found
        }
        diagnostics?.record("CACHE", "RECENT_VIDEO_MODEL_HIT")
        return found
    }
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
    @Volatile private var cacheSessionRoot: File? = null
    @Volatile private var networkFactory: DataSource.Factory? = null
    @Volatile private var diagnostics: DiagnosticLog? = null
    // The upstream may reject anonymous video models independently of its
    // catalog. Do not keep spending identities on speculative video models.
    // Foreground playback always remains eligible to probe again.
    private val speculativeHealth = SpeculativeModelHealth()

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
                val base = File(context.applicationContext.cacheDir,
                    "kxg_encrypted_stream_cache_v2")
                val session = File(base, UUID.randomUUID().toString())
                cacheSessionRoot = session
                StreamRegistry(
                    network,
                    { event, duration, status, bytes ->
                        diagnostics?.record("CDN", event, duration, status, bytes)
                    },
                    session,
                ).also { streamRegistry = it }
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

    /**
     * Call from Dispatchers.IO after registry creation. A crashed or killed
     * process leaves no key, so its old encrypted session files are unusable.
     * Only stale siblings are removed, never the current active session.
     */
    fun purgeOldSessionCache(context: Context) {
        val parent = File(context.applicationContext.cacheDir,
            "kxg_encrypted_stream_cache_v2")
        val current = cacheSessionRoot?.absolutePath
        val removed = parent.listFiles()?.count { entry ->
            entry.absolutePath != current && entry.deleteRecursively()
        } ?: 0
        if (removed > 0) diagnostics?.record("CACHE", "STALE_SESSIONS_REMOVED",
            bytes = removed)
    }

    /** Runtime resizing applies to active and upcoming encrypted disk prefixes. */
    fun configureCache(context: Context, perVideoMb: Int, totalMb: Int) {
        registry(context.applicationContext).configureCache(perVideoMb, totalMb)
        diagnostics?.record("CACHE", "DISK_LIMIT_MB", bytes = totalMb)
        diagnostics?.record("CACHE", "PER_VIDEO_MB", bytes = perVideoMb)
    }

    private fun active(uri: String) {
        streamRegistry?.markActive(uri)
    }

    fun currentCacheBytes(): Long = streamRegistry?.cachedBytes() ?: 0L

    /** User action: reclaim the other videos, keep foreground cache usable. */
    fun clearOtherVideoCache() {
        clearPrefetched()
        streamRegistry?.clearOtherCachedBytes()
        diagnostics?.record("CACHE", "USER_CLEARED_OTHERS")
    }

    suspend fun resolve(
        context: Context,
        sdk: OpenHongguoSource,
        episodeId: String,
        qualityLimit: Int,
        forceRefresh: Boolean = false,
    ): Prepared {
        if (!forceRefresh) {
            takePrefetched(episodeId, qualityLimit)?.let {
                rememberPlayed(it)
                active(it.uri)
                return it
            }
            recentSource(episodeId, qualityLimit)?.let {
                rememberPlayed(it)
                active(it.uri)
                return it
            }
        } else {
            // Evict metadata, not the active player's registry entry/cached bytes.
            // A prefetched entry may have become active while its warmup ran.
            synchronized(preloadLock) {
                val key = cacheKey(episodeId, qualityLimit)
                prefetched.remove(key)
                recentPlayed.remove(key)
                preloadGeneration++
            }
            diagnostics?.record("CACHE", "NETWORK_FORCE_FRESH_MODEL")
        }
        return checkNotNull(resolveFresh(context, sdk, episodeId, qualityLimit,
            speculative = false)) {
            "Foreground playback-model lookup unexpectedly skipped"
        }.also {
            rememberPlayed(it)
            active(it.uri)
        }
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
        targetMegabytes: Int = KxgWarmupPolicy.DEFAULT_PREFIX_MB,
        deadlineSeconds: Int = KxgWarmupPolicy.DEFAULT_DEADLINE_SECONDS,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        require(KxgWarmupPolicy.validPrefixMb(targetMegabytes))
        require(KxgWarmupPolicy.validDeadlineSeconds(deadlineSeconds))
        val key = cacheKey(episodeId, qualityLimit)
        val now = System.currentTimeMillis()
        val generation = synchronized(preloadLock) { preloadGeneration }
        val existing = synchronized(preloadLock) {
            (prefetched[key] ?: recentPlayed[key])?.takeIf {
                KxgWarmupPolicy.reusable(it.validUntilMs, now) &&
                    streamRegistry?.get(it.uri) != null
            }
        }
        if (existing == null && speculativeHealth.isCoolingDown()) {
            diagnostics?.record("PRELOAD", "MODEL_LIMIT_COOLDOWN")
            return false
        }
        val prepared = existing ?: resolveFresh(context, sdk, episodeId, qualityLimit,
            speculative = true) ?: run {
                diagnostics?.record("PRELOAD", "MODEL_GATE_SKIPPED")
                return false
            }
        if (!KxgWarmupPolicy.reusable(prepared.validUntilMs, System.currentTimeMillis())) {
            if (existing == null) forget(prepared.uri)
            diagnostics?.record("PRELOAD", "MODEL_TOO_CLOSE_TO_EXPIRY")
            return false
        }
        if (existing == null) {
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
        } else {
            diagnostics?.record("PRELOAD", "MODEL_REUSED_FOR_MORE_BYTES")
        }
        // Keep a bounded single-file download worker. The same source can be
        // claimed by foreground at any time; completed ciphertext remains.
        try {
            withTimeout(deadlineSeconds * 1000L) {
                runInterruptible(Dispatchers.IO) {
                    registry(context.applicationContext)[prepared.uri]
                        ?.warmEncryptedPrefix(
                            bytes = targetMegabytes * KxgWarmupPolicy.MIB,
                            shouldContinue = {
                                shouldContinue() &&
                                    KxgWarmupPolicy.reusable(
                                        prepared.validUntilMs, System.currentTimeMillis())
                            },
                        )
                }
            }
        } catch (_: TimeoutCancellationException) {
            diagnostics?.record("PRELOAD", "PREFIX_DEADLINE")
        } catch (cancel: CancellationException) {
            diagnostics?.record("PRELOAD", "CANCELLED")
            throw cancel
        } catch (_: Exception) {
            diagnostics?.record("PRELOAD", "PREFIX_PARTIAL_UNAVAILABLE")
        }
        return true
    }

    private suspend fun resolveFresh(
        context: Context,
        sdk: OpenHongguoSource,
        episodeId: String,
        qualityLimit: Int,
        speculative: Boolean,
    ): Prepared? = withContext(Dispatchers.IO) {
        // Update health inside the serialized model gate: a queued request
        // must see the preceding failure before starting speculative work.
        suspend fun requestModel(): dev.openhongguo.PlaybackResult {
            return try {
                sdk.resolvePlayback(episodeId).also { speculativeHealth.onSuccess() }
            } catch (error: OpenhongguoException) {
                val code = PlaybackProblem.describe("获取播放模型", error)
                    .substringBefore(" · ")
                diagnostics?.record("PLAYBACK", "SDK_MODEL_" + code)
                val cooldown = speculativeHealth.onFailure(error.code, error.retryAfterMs)
                if (cooldown > 0L) {
                    diagnostics?.record("PRELOAD", "MODEL_COOLDOWN_" + error.code.name)
                    diagnostics?.record("PRELOAD", "MODEL_COOLDOWN_MINUTES_" +
                        ((cooldown + 59_999L) / 60_000L))
                }
                throw error
            }
        }
        val resolved = if (speculative) {
            PlaybackModelRequestGate.speculative {
                if (speculativeHealth.isCoolingDown()) return@speculative null
                diagnostics?.record("PRELOAD", "OFFICIAL_OPENHONGGUO_MODEL_REQUEST")
                requestModel()
            } ?: return@withContext null
        } else {
            PlaybackModelRequestGate.foreground {
                diagnostics?.record("PLAYBACK", "OFFICIAL_OPENHONGGUO_V5_REQUEST")
                requestModel()
            }
        }
        diagnostics?.record("PLAYBACK", "OFFICIAL_OPENHONGGUO_MODEL_RECEIVED")
        val playback = resolved.playback
        val nowMs = System.currentTimeMillis()
        if (KxgWarmupPolicy.hardExpired(playback.expiresAt, nowMs)) {
            diagnostics?.record("CACHE", "FRESH_MODEL_ALREADY_EXPIRED")
            throw IOException("PLAYBACK_MODEL_EXPIRED")
        }
        val selection = CompatibleStreamSelector.select(
            playback.streams, playback.audioUrl.isNotEmpty(), qualityLimit,
            AndroidDecoderCapability::rank)
        val chosen = when (selection) {
            is CompatibleStreamSelector.Result.Ready -> {
                if (selection.excludedCodecCount > 0) {
                    diagnostics?.record("PLAYBACK", "UNSUPPORTED_CODEC_FILTERED")
                }
                diagnostics?.record("PLAYBACK", "COMPATIBLE_STREAM_SELECTED")
                diagnostics?.record("PLAYBACK", if (AndroidDecoderCapability.rank(selection.stream) == 2)
                    "HARDWARE_DECODER_SELECTED" else "SOFTWARE_OR_UNKNOWN_DECODER_SELECTED")
                selection.stream
            }
            CompatibleStreamSelector.Result.UnsupportedCodec ->
                throw IOException("CODEC_NOT_SUPPORTED")
            CompatibleStreamSelector.Result.UnsupportedContainer ->
                throw IOException("PLAYBACK_CONTAINER_UNSUPPORTED")
            CompatibleStreamSelector.Result.Empty ->
                throw IOException("PLAYBACK_NO_USABLE_STREAM")
        }
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
        // Register official SDK URLs directly. CencDataSource.open()
        // resolves the header and decrypts on Media3's loader thread.
        val streams = registry(context.applicationContext)
        val uri = streams.register(urls, key)
        streams.record("SOURCE_REGISTERED_${urls.size}_HOSTS")
        val validUntil = KxgWarmupPolicy.cacheValidUntilMs(
            playback.expiresAt, System.currentTimeMillis())
        Prepared(uri, episodeId, qualityLimit, validUntil)
    }
}
