package com.yagay.hongguo.kxg

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide disk cache of the CLEAR bytes after the Kaixinguo CENC layer.
 * Cache keys include a digest of the remote MP4 header so replacing an episode
 * cannot silently reuse an incompatible decrypted file from a previous model.
 * The cache stays above CencDataSource and never stores content keys or cookies.
 */
@OptIn(UnstableApi::class)
internal object KxgDiskCache {
    private const val ROOT = "kaixinguo_cenc_cache_v1"
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private val lock = Any()
    @Volatile private var cache: SimpleCache? = null

    private fun dir(context: Context) = File(context.cacheDir, ROOT)
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("web_rewrite_settings", Context.MODE_PRIVATE)
            .getBoolean("disk_cache_enabled", true)

    suspend fun prepare(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!enabled(context)) return@withContext false
        synchronized(lock) {
            if (cache != null) return@synchronized true
            runCatching {
                val folder = dir(context)
                folder.mkdirs()
                cache = SimpleCache(folder,
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext))
                true
            }.getOrDefault(false)
        }
    }

    fun factory(context: Context): DataSource.Factory {
        val upstream = KxgPlaybackStreams.factory(context)
        val current = cache ?: return upstream
        if (!enabled(context)) return upstream
        return CacheDataSource.Factory()
            .setCache(current)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun sizeBytes(): Long = synchronized(lock) {
        cache?.cacheSpace ?: 0L
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cache?.release()
            cache = null
            dir(context).deleteRecursively()
        }
        if (enabled(context)) prepare(context)
    }
}
