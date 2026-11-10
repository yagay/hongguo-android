package com.yagay.hongguo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.hongguotv.core.RemoteVideo
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Process-wide disk cache for the *decoded* byte stream returned by RemoteVideo.
 * CacheDataSource sits ABOVE NativeVideoDataSource, never on its encrypted HTTP URL.
 * Only ordinary non-DRM native videos are eligible; this is not a download library.
 *
 * Cache initialization and expiry scans are performed off the UI thread. A service
 * can fall back to the original native source while the cache is not initialized.
 */
@OptIn(UnstableApi::class)
internal object NativeDiskVideoCache {
    private const val ROOT = "native_stream_cache_v1"
    private const val PREFS = "web_rewrite_settings"
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private const val AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
    @Volatile private var lastPruneMs = 0L
    private val lock = Any()
    @Volatile private var cache: SimpleCache? = null
    // Avoid ever reusing stale bytes if a provider replaces a video while
    // preserving its ID and file length. Keys are owned by the video handle.
    private val registeredKeys = WeakHashMap<RemoteVideo, String>()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("disk_cache_enabled", true)

    fun wifiPreload(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("preload_wifi", true)

    fun mobilePreload(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("preload_mobile", false)

    /** Use unmetered Wi-Fi by default; never infer "Wi-Fi" from connectivity alone. */
    fun mayBackgroundPreload(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        // VALIDATED is a system captive-portal heuristic, not a guarantee that
        // the native API cannot stream. If the playback request works while
        // Android hasn't validated the network yet, prefetch must still run.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // An Android Wi-Fi network can be marked metered by the user or OEM.
        // It is still Wi-Fi: do not silently disable all reel prefetching.
        // Mobile/VPN-only transports retain their separate opt-in.
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return allowedTransport(wifi, wifiPreload(context), mobilePreload(context))
    }

    /** Wi-Fi is still Wi-Fi even when Android/OEM flags it as metered.
     * Pure policy kept separate from ConnectivityManager so it can be tested. */
    internal fun allowedTransport(
        isWifi: Boolean, wifiEnabled: Boolean, mobileEnabled: Boolean,
    ): Boolean = if (isWifi) wifiEnabled else mobileEnabled

    /** No secrets or identifiers are logged; helps distinguish a disabled setting
     * from an absent/temporarily unvalidated active transport. */
    fun preloadBlockReason(context: Context): String? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "CONNECTIVITY_UNAVAILABLE"
        val active = manager.activeNetwork ?: return "NETWORK_UNAVAILABLE"
        val caps = manager.getNetworkCapabilities(active) ?: return "NETWORK_CAPABILITIES_UNAVAILABLE"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            return "NO_INTERNET_CAPABILITY"
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            if (wifiPreload(context)) null else "WIFI_SETTING_DISABLED"
        } else if (mobilePreload(context)) null else "MOBILE_SETTING_DISABLED"
    }

    /** Stable resource identity, discriminating byte length and requested rendition. */
    fun key(vid: String, quality: Int, bytes: Long): String {
        val input = "$vid|$quality|$bytes|native-v1".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input).joinToString("") { "%02x".format(it) }
    }

    internal fun fingerprintedKey(
        vid: String, quality: Int, bytes: Long, header: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fingerprint = digest.digest(header).joinToString("") {
            "%02x".format(it)
        }
        return digest.digest(
            (key(vid, quality, bytes) + "|" + fingerprint).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
    }

    suspend fun register(video: RemoteVideo, vid: String, quality: Int) =
        withContext(Dispatchers.IO) {
            synchronized(registeredKeys) {
                if (registeredKeys.containsKey(video)) return@withContext
            }
            val prefixLength = minOf(video.total, 1024L).toInt()
            if (prefixLength <= 0) return@withContext
            // A missing or unreadable prefix must NOT fall back to an unsafe
            // URL-only key. Media3 can still play via NativeVideoDataSource.
            val header = runCatching { video.read(0L, prefixLength) }
                .getOrNull()?.takeIf { it.size == prefixLength }
                ?: return@withContext
            synchronized(registeredKeys) {
                registeredKeys[video] =
                    fingerprintedKey(vid, quality, video.total, header)
            }
        }

    fun streamKey(video: RemoteVideo): String? =
        synchronized(registeredKeys) { registeredKeys[video] }

    /** Must only be called on Dispatchers.IO (or a dedicated worker). */
    fun prepare(context: Context, ignoreDisabled: Boolean = false): Boolean {
        if (!ignoreDisabled && !enabled(context)) return false
        if (cache != null) {
            maybePrune(cache!!)
            return true
        }
        synchronized(lock) {
            if (cache == null) {
                try {
                    val dir = File(context.applicationContext.cacheDir, ROOT)
                    if (!dir.exists() && !dir.mkdirs()) return false
                    val db = StandaloneDatabaseProvider(context.applicationContext)
                    val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), db)
                    cache = created
                    maybePrune(created)
                } catch (_: Exception) {
                    return false
                }
            }
        }
        return cache != null
    }

    private fun maybePrune(instance: SimpleCache) {
        val now = System.currentTimeMillis()
        if (lastPruneMs != 0L &&
            now - lastPruneMs in 0L until PRUNE_INTERVAL_MS) return
        synchronized(lock) {
            if (lastPruneMs != 0L &&
                now - lastPruneMs in 0L until PRUNE_INTERVAL_MS) return
            pruneExpired(instance, now)
            lastPruneMs = now
        }
    }

    internal fun expired(lastTouched: Long, now: Long): Boolean =
        lastTouched > now || now - lastTouched >= AGE_MS

    private fun pruneExpired(instance: SimpleCache, now: Long) {
        for (key in instance.keys.toList()) {
            val spans = instance.getCachedSpans(key).toList()
            for (span in spans) {
                if (span.isCached && expired(span.lastTouchTimestamp, now)) {
                    runCatching { instance.removeSpan(span) }
                }
            }
        }
    }

    fun isReady(context: Context): Boolean = enabled(context) && cache != null

    fun sizeBytes(): Long = cache?.cacheSpace ?: 0L

    fun factory(
        context: Context, video: RemoteVideo,
        onCacheRead: (Long) -> Unit = {},
    ): DataSource.Factory {
        val upstream = DataSource.Factory { NativeVideoDataSource(video) }
        val ready = cache
        return if (enabled(context) && ready != null && streamKey(video) != null)
            CacheDataSource.Factory().setCache(ready)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(
                        cacheSizeBytes: Long, cachedBytesRead: Long,
                    ) {
                        if (cachedBytesRead > 0L) onCacheRead(cachedBytesRead)
                    }

                    override fun onCacheIgnored(reason: Int) = Unit
                })
        else upstream
    }

    /**
     * Writes the exact same media bytes that playback would read into SimpleCache.
     * A warmup is bounded, cancellable, and never commits a different quality's
     * bytes under the same key. It may reuse the native core's RAM prefix.
     */
    suspend fun warm(
        context: Context, video: RemoteVideo, vid: String, quality: Int,
        requestedBytes: Int, useful: () -> Boolean = { true },
    ): Unit = withContext(Dispatchers.IO) {
        if (!enabled(context) || !mayBackgroundPreload(context) ||
            !useful() || !prepare(context)) return@withContext
        register(video, vid, quality)
        val safeKey = streamKey(video) ?: return@withContext
        val length = minOf(requestedBytes.toLong(), video.total)
        if (length <= 0) return@withContext
        val source = factory(context, video).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("native://episode/$vid"))
            .setKey(safeKey)
            .setPosition(0L).setLength(length).build()
        val buffer = ByteArray(64 * 1024)
        try {
            source.open(spec)
            var remaining = length
            while (remaining > 0 && useful() && enabled(context) &&
                mayBackgroundPreload(context)) {
                currentCoroutineContext().ensureActive()
                val amount = source.read(buffer, 0,
                    minOf(remaining, buffer.size.toLong()).toInt())
                if (amount <= 0) break
                remaining -= amount
            }
        } finally {
            source.close()
        }
    }

    /** Cache persists across ordinary playback close; clear only by user action. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        if (!prepare(context, ignoreDisabled = true)) return@withContext
        val existing = cache ?: return@withContext
        for (resource in existing.keys.toList()) {
            runCatching { existing.removeResource(resource) }
        }
    }
}
