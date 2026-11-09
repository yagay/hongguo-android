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
    private val lock = Any()
    @Volatile private var cache: SimpleCache? = null

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
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        val unmeteredWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (unmeteredWifi) wifiPreload(context) else mobilePreload(context)
    }

    /** Stable resource identity, discriminating byte length and requested rendition. */
    fun key(vid: String, quality: Int, bytes: Long): String {
        val input = "$vid|$quality|$bytes|native-v1".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(input).joinToString("") { "%02x".format(it) }
    }

    /** Must only be called on Dispatchers.IO (or a dedicated worker). */
    fun prepare(context: Context): Boolean {
        if (!enabled(context)) return false
        if (cache != null) return true
        synchronized(lock) {
            if (cache == null) {
                try {
                    val dir = File(context.applicationContext.cacheDir, ROOT)
                    if (!dir.exists() && !dir.mkdirs()) return false
                    val db = StandaloneDatabaseProvider(context.applicationContext)
                    val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), db)
                    cache = created
                    pruneExpired(created)
                } catch (_: Exception) {
                    return false
                }
            }
        }
        return cache != null
    }

    private fun pruneExpired(instance: SimpleCache) {
        val now = System.currentTimeMillis()
        for (key in instance.keys.toList()) {
            val spans = instance.getCachedSpans(key).toList()
            for (span in spans) {
                if (span.isCached &&
                    (span.lastTouchTimestamp > now ||
                        now - span.lastTouchTimestamp >= AGE_MS)) {
                    runCatching { instance.removeSpan(span) }
                }
            }
        }
    }

    fun sizeBytes(): Long = cache?.cacheSpace ?: 0L

    fun factory(context: Context, video: RemoteVideo): DataSource.Factory {
        val upstream = DataSource.Factory { NativeVideoDataSource(video) }
        val ready = cache
        return if (enabled(context) && ready != null)
            CacheDataSource.Factory().setCache(ready)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
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
            !prepare(context)) return@withContext
        val length = minOf(requestedBytes.toLong(), video.total)
        if (length <= 0) return@withContext
        val source = factory(context, video).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("native://episode/$vid"))
            .setKey(key(vid, quality, video.total))
            .setPosition(0L).setLength(length).build()
        val buffer = ByteArray(64 * 1024)
        try {
            source.open(spec)
            var remaining = length
            while (remaining > 0 && useful()) {
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
        val existing = cache ?: run {
            if (!prepare(context)) return@withContext
            cache ?: return@withContext
        }
        for (resource in existing.keys.toList()) {
            runCatching { existing.removeResource(resource) }
        }
    }
}
