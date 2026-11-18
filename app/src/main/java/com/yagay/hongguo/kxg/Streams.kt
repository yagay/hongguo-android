package com.yagay.hongguo.kxg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.UUID

/**
 * StreamRegistry keeps the episodes' files the player may read, by the URI
 * it plays each at. It keeps the last few: the one playing, and one the
 * player may still read.
 */
class StreamRegistry(
    private val http: DataSource.Factory,
    private val diagnostics: (String, Long?, Int?, Int?) -> Unit = { _, _, _, _ -> },
    private val cacheRoot: File? = null,
) {
    private val streams = LinkedHashMap<String, StreamSource>()
    @Volatile private var activeUri: String? = null
    @Volatile private var perVideoMb = KxgWarmupPolicy.DEFAULT_PREFIX_MB
    @Volatile private var totalCacheMb = KxgWarmupPolicy.DEFAULT_TOTAL_MB

    init {
        // Session-scoped encrypted bytes: model keys and signed URLs are never
        // persisted. Unclaimed files from a killed process must not accumulate.
        if (cacheRoot != null) {
            cacheRoot.deleteRecursively()
            cacheRoot.mkdirs()
        }
    }

    @Synchronized
    fun configureCache(perVideo: Int, total: Int) {
        require(KxgWarmupPolicy.validPrefixMb(perVideo))
        require(KxgWarmupPolicy.validTotalMb(total))
        perVideoMb = perVideo
        totalCacheMb = total
        trimCache()
    }

    @Synchronized
    fun markActive(uri: String) {
        if (streams.containsKey(uri)) activeUri = uri
    }

    private fun itemLimitBytes(): Int =
        KxgWarmupPolicy.effectivePrefixBytes(perVideoMb, totalCacheMb)

    /** Evict least-recent old prefixes; keep the active source if possible. */
    @Synchronized
    fun trimCache() {
        val limit = totalCacheMb.toLong() * KxgWarmupPolicy.MIB
        var total = streams.values.sumOf { it.warmBytes().toLong() }
        if (total <= limit) return
        for ((uri, src) in streams) {
            if (uri == activeUri || total <= limit) continue
            val reclaimed = src.warmBytes()
            if (reclaimed == 0) continue
            src.evictCachedBytes()
            total -= reclaimed
            record("CACHE_LRU_EVICT", bytes = reclaimed)
        }
        if (total > limit) {
            activeUri?.let(streams::get)?.let { active ->
                val reclaimed = active.warmBytes()
                active.evictCachedBytes()
                record("CACHE_ACTIVE_LIMIT_EVICT", bytes = reclaimed)
            }
        }
    }

    fun record(event: String, ms: Long? = null, httpStatus: Int? = null, bytes: Int? = null) {
        diagnostics(event, ms, httpStatus, bytes)
    }

    fun register(sources: List<String>, key: ByteArray?): String {
        val id = UUID.randomUUID().toString()
        val uri = "$SCHEME://episode/$id.mp4"
        synchronized(this) {
            streams[uri] = StreamSource(
                sources, key, http, ::record,
                cacheRoot?.let { File(it, "$id.enc") },
                ::itemLimitBytes, ::trimCache,
            )
            // Keep the last few models even if their disk prefix was evicted.
            // Current playback still has its own live registered source.
            while (streams.size > 16) {
                val oldest = streams.keys.first()
                if (oldest == activeUri) break
                streams.remove(oldest)?.dispose()
            }
            trimCache()
        }
        return uri
    }

    operator fun get(uri: String): StreamSource? = synchronized(this) { streams[uri] }
    fun forget(uri: String) = synchronized(this) {
        streams.remove(uri)?.dispose()
        if (activeUri == uri) activeUri = null
        Unit
    }

    companion object {
        const val SCHEME = "kaixinguo-stream"
    }
}

/**
 * StreamSource is one episode's file on the CDN: a URL and its backup, and
 * the key that decrypts it, or none for a clear file. The first read learns
 * the file's size and, for an encrypted one, its layout; every read after
 * shares them.
 */
class StreamSource(
    val sources: List<String>,
    private val key: ByteArray?,
    private val http: DataSource.Factory,
    private val diagnostic: (String, Long?, Int?, Int?) -> Unit,
    cacheFile: File? = null,
    private val prefixLimitBytes: () -> Int = { KxgWarmupPolicy.DEFAULT_PREFIX_MB * KxgWarmupPolicy.MIB },
    private val cacheModified: () -> Unit = {},
) {
    fun record(event: String, ms: Long? = null, httpStatus: Int? = null, bytes: Int? = null) {
        diagnostic(event, ms, httpStatus, bytes)
    }

    class File(val size: Long, val layout: Layout?)

    /** The source that answered last, which reads try first. */
    @Volatile
    var good = 0

    private var file: File? = null
    private val encryptedCache = cacheFile?.let(::EncryptedPrefixFile)
    private var lastWrittenMiB = 0

    /** Number of encrypted bytes currently committed to the local file. */
    fun warmBytes(): Int = encryptedCache?.size ?: 0

    fun copyWarmBytes(position: Long, target: ByteArray, offset: Int, length: Int): Int =
        encryptedCache?.copy(position, target, offset, length) ?: 0

    /** Only contiguous ciphertext is appended; seek gaps are never faked. */
    fun cacheNetworkBytes(position: Long, bytes: ByteArray, offset: Int, count: Int): Int {
        val cache = encryptedCache ?: return 0
        val added = try {
            cache.append(position, bytes, offset, count, prefixLimitBytes())
        } catch (_: IOException) {
            record("CACHE_WRITE_FAILED")
            0
        }
        if (added > 0) {
            val previousCacheMiB = (cache.size - added) / KxgWarmupPolicy.MIB
            val currentMiB = cache.size / KxgWarmupPolicy.MIB
            if (currentMiB > lastWrittenMiB && currentMiB % 4 == 0) {
                lastWrittenMiB = currentMiB
                record("CACHE_BYTES_STORED", bytes = cache.size)
            }
            // A trim scan for every tiny read is costly. Check the global
            // disk budget only at whole-MiB boundaries.
            if (currentMiB > 0 && currentMiB != previousCacheMiB)
                cacheModified()
        }
        return added
    }

    fun evictCachedBytes() {
        encryptedCache?.evict()
        lastWrittenMiB = 0
    }

    fun dispose() = encryptedCache?.dispose() ?: Unit

    /**
     * No full-file download is required. Publish every 256 KiB encrypted
     * chunk to disk immediately, then let Media3 use any finished prefix.
     */
    fun warmEncryptedPrefix(
        bytes: Int = prefixLimitBytes(),
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
    ): Int {
        val cache = encryptedCache ?: return 0
        val f = prepare()
        val target = minOf(bytes.toLong(), f.size, prefixLimitBytes().toLong()).toInt()
        val start = System.nanoTime()
        while (cache.size < target) {
            if (!shouldContinue() || Thread.currentThread().isInterrupted) break
            val position = cache.size
            val count = minOf(KxgWarmupPolicy.CHUNK_BYTES, target - position)
            val (chunk, size) = read(sources[good], position.toLong(), count.toLong())
            if (size != f.size || chunk.size != count)
                throw IOException("预加载视频分段长度不一致")
            if (cacheNetworkBytes(position.toLong(), chunk, 0, chunk.size) <= 0) break
        }
        record(if (cache.size >= target) "PRELOAD_PREFIX_READY" else "PRELOAD_PARTIAL_READY",
            (System.nanoTime() - start) / 1_000_000, null, cache.size)
        return cache.size
    }

    /** The file's size and layout, learning them the first time; it blocks, on the player's loading thread. */
    @Synchronized
    fun prepare(): File {
        file?.let { return it }
        // Do not permanently cache a temporary CDN transport failure. A subsequent
        // player retry may use this same source after connectivity is restored.
        var last = IOException("没有视频地址")
        for ((i, source) in sources.withIndex()) {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val (head, total) = read(source, 0, 64L shl 10)
                val layout = key?.let { k ->
                    Layout.open(total, k) { off, n ->
                        if (off + n <= head.size) head.copyOfRange(off.toInt(), (off + n).toInt()) else read(source, off, n).first
                    }
                }
                good = i
                record("HEADER_PREPARED",
                    android.os.SystemClock.elapsedRealtime() - started, null, head.size)
                return File(total, layout).also { file = it }
            } catch (e: IOException) {
                if (CdnStreamingPolicy.isCancellation(e, Thread.currentThread().isInterrupted))
                    throw e
                record("HEADER_FAILED", android.os.SystemClock.elapsedRealtime() - started,
                    httpErrorCode(e), null)
                last = e
            } catch (e: RuntimeException) {
                record("HEADER_INVALID", android.os.SystemClock.elapsedRealtime() - started,
                    null, null)
                last = IOException(e)
            }
        }
        throw last
    }

    /** Reads `count` bytes of `source` at `offset`, and the file's size. */
    private fun read(source: String, offset: Long, count: Long): Pair<ByteArray, Long> {
        val ds = http.createDataSource()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            ds.open(DataSpec.Builder().setUri(source).setPosition(offset).setLength(count).build())
            val openedAt = android.os.SystemClock.elapsedRealtime()
            val total = totalSize(ds.responseHeaders) ?: throw IOException("CDN 没有告知文件大小")
            val out = ByteArrayOutputStream(count.toInt().coerceAtMost(1 shl 20))
            val buf = ByteArray(32 shl 10)
            var firstByte = true
            while (out.size() < count) {
                val n = ds.read(buf, 0, minOf(buf.size.toLong(), count - out.size()).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                if (firstByte && n > 0) {
                    firstByte = false
                    record("HEADER_FIRST_BYTE",
                        android.os.SystemClock.elapsedRealtime() - started,
                        httpCode(ds), null)
                }
                out.write(buf, 0, n)
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            if (elapsed >= CdnStreamingPolicy.SLOW_READ_MS ||
                openedAt - started >= CdnStreamingPolicy.SLOW_READ_MS) {
                record("HEADER_SLOW", elapsed, httpCode(ds), out.size())
            }
            return out.toByteArray() to total
        } finally {
            ds.close()
        }
    }
}

/** Sanitize all telemetry: only HTTP codes, elapsed time and byte counts; never CDN URLs. */
private fun httpCode(ds: DataSource): Int? =
    (ds as? HttpDataSource)?.responseCode?.takeIf { it in 100..599 }

private fun httpErrorCode(error: Throwable): Int? =
    generateSequence(error) { it.cause }.take(6)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()?.responseCode

/**
 * Follow upstream iOS StreamLoader: a successful partial response must start at
 * the exact byte requested, especially after a connection has been replaced.
 * The Media3 HTTP source itself handles 200 responses that ignore Range.
 */
private fun matchingRange(ds: DataSource, position: Long): Boolean {
    if (httpCode(ds) != 206) return true
    val contentRange = ds.responseHeaders.entries
        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
        ?.value?.firstOrNull() ?: return true
    val first = contentRange.trim().substringAfter(' ', "")
        .substringBefore('-', "").toLongOrNull()
    return first == position
}

/** The file's size, from the response to a range request, or to a request for the whole file. */
private fun totalSize(headers: Map<String, List<String>>): Long? {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    header("Content-Range")?.let { cr ->
        return cr.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0 }
    }
    return header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}

/**
 * CencDataSource reads an episode registered with [StreamRegistry]: each
 * range the player asks for comes from the CDN, decrypted in place as it
 * streams, so playback starts at once and can seek anywhere. It is the
 * Android counterpart of the iOS app's StreamLoader. A download that breaks
 * off goes on from where it stopped, from the other source when there is
 * one, twice at most.
 */
class CencDataSource(private val registry: StreamRegistry, private val http: DataSource.Factory) : BaseDataSource(true) {
    class Factory(private val registry: StreamRegistry, private val http: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = CencDataSource(registry, http)
    }

    private var spec: DataSpec? = null
    private var stream: StreamSource? = null
    private var layout: Layout? = null
    private var upstream: DataSource? = null
    private var position = 0L // where the next byte read belongs in the file
    private var end = 0L // past the last byte wanted
    private var source = 0
    private var tries = 0 // downloads of this range that broke off
    private var opened = false
    private var connectionStartedAtMs = 0L
    private var connectionBytes = 0L
    private var connectionEnd = 0L
    private var firstByteSeen = false
    private var slowReadMs = 0L
    private var slowReadBytes = 0L
    private var reconnectOnNextRead = false
    private var preloadHitReported = false
    private var cachedReadBytes = 0L
    private var lastReportedCacheMiB = 0L

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        val s = registry[dataSpec.uri.toString()] ?: throw IOException("这一集的地址已经失效")
        val f = s.prepare()
        stream = s
        layout = f.layout
        position = dataSpec.position
        if (position > f.size) throw IOException("超出文件末尾")
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) f.size else minOf(f.size, position + dataSpec.length)
        source = s.good
        tries = 0
        preloadHitReported = false
        cachedReadBytes = 0L
        lastReportedCacheMiB = 0L
        connectionEnd = position
        // Defer network open until no prefetched encrypted bytes are available.
        // This also avoids seeking the original connection from offset zero.
        opened = true
        transferStarted(dataSpec)
        return end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), end - position).toInt()
        // Prefetched bytes are raw CENC bytes; decrypt exactly once here,
        // using the same absolute MP4 offsets as the network range path.
        if (upstream == null) {
            val hit = stream?.copyWarmBytes(position, buffer, offset, want) ?: 0
            if (hit > 0) {
                layout?.decrypt(buffer, offset, hit, position)
                position += hit
                cachedReadBytes += hit
                if (!preloadHitReported ||
                    cachedReadBytes / KxgWarmupPolicy.MIB > lastReportedCacheMiB + 3L) {
                    preloadHitReported = true
                    lastReportedCacheMiB = cachedReadBytes / KxgWarmupPolicy.MIB
                    stream?.record("CACHE_HIT_TOTAL",
                        bytes = cachedReadBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                bytesTransferred(hit)
                return hit
            }
        }
        while (true) {
            if (reconnectOnNextRead) {
                reconnectOnNextRead = false
                if (again()) {
                    stream?.record("RANGE_SLOW_RECONNECT")
                    connect() // exact byte offset; no player reset, seek or lost bytes
                }
            }
            if (position >= connectionEnd && position < end) {
                tries = 0 // next bounded chunk gets its own retry budget
                connect()
            }
            val started = android.os.SystemClock.elapsedRealtime()
            val n = try {
                upstream!!.read(buffer, offset, want)
            } catch (e: IOException) {
                stream?.record("RANGE_READ_FAILED",
                    android.os.SystemClock.elapsedRealtime() - started,
                    httpErrorCode(e), null)
                // A socket read timeout is an InterruptedIOException subclass, but it
                // is NOT a cancelled Media3 load. Switch to the backup CDN.
                if (CdnStreamingPolicy.isCancellation(e, Thread.currentThread().isInterrupted) ||
                    !again()) throw e
                connect()
                continue
            }
            if (n == C.RESULT_END_OF_INPUT) {
                // End of a planned chunk is not a failed download.
                if (position >= connectionEnd && position < end) {
                    tries = 0
                    connect()
                    continue
                }
                stream?.record("RANGE_EARLY_EOF")
                if (!again()) throw IOException("视频下载中断")
                connect()
                continue
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            if (n > 0 && !firstByteSeen) {
                firstByteSeen = true
                stream?.record("RANGE_FIRST_BYTE",
                    android.os.SystemClock.elapsedRealtime() - connectionStartedAtMs,
                    httpCode(upstream!!), null)
            }
            if (n > 0 && elapsed >= CdnStreamingPolicy.SLOW_READ_MS)
                stream?.record("RANGE_READ_SLOW", elapsed, httpCode(upstream!!), n)
            // Cache the ORIGINAL encrypted bytes before in-place CENC decrypt.
            // Rewind can then read locally without depending on the CDN.
            if (n > 0) stream?.cacheNetworkBytes(position, buffer, offset, n)
            layout?.decrypt(buffer, offset, n, position)
            position += n
            connectionBytes += n
            if (CdnStreamingPolicy.isSlowRead(elapsed, n)) {
                slowReadMs += elapsed
                slowReadBytes += n
                if (CdnStreamingPolicy.shouldReconnectSlowRange(
                        slowReadMs, slowReadBytes, connectionEnd - position,
                        tries, Thread.currentThread().isInterrupted)) {
                    reconnectOnNextRead = true
                    stream?.record("RANGE_TRICKLE_DETECTED", slowReadMs,
                        httpCode(upstream!!),
                        slowReadBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    slowReadMs = 0L
                    slowReadBytes = 0L
                }
            } else if (n > 0) {
                slowReadMs = 0L
                slowReadBytes = 0L
            }
            stream?.good = source
            bytesTransferred(n)
            return n
        }
    }

    /** Asks for the rest of the range, from the other source when there is one. */
    private fun connect() {
        val s = stream!!
        while (true) {
            closeUpstream()
            val ds = http.createDataSource()
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                // For progressive MP4, Media3 often requests everything until EOF.
                // Bound CDN Range requests so one throttled long response cannot
                // hold the entire episode hostage. CENC uses absolute offsets.
                val requestBytes = minOf(end - position, CdnStreamingPolicy.RANGE_CHUNK_BYTES)
                ds.open(DataSpec.Builder().setUri(s.sources[source])
                    .setPosition(position).setLength(requestBytes).build())
                if (!matchingRange(ds, position))
                    throw IOException("CDN 返回的分段起点不匹配")
                connectionEnd = position + requestBytes
                connectionStartedAtMs = started
                connectionBytes = 0L
                firstByteSeen = false
                slowReadMs = 0L
                slowReadBytes = 0L
                reconnectOnNextRead = false
                upstream = ds
                s.record("RANGE_OPEN",
                    android.os.SystemClock.elapsedRealtime() - started,
                    httpCode(ds), null)
                return
            } catch (e: IOException) {
                s.record("RANGE_OPEN_FAILED",
                    android.os.SystemClock.elapsedRealtime() - started,
                    httpErrorCode(e), null)
                runCatching { ds.close() }
                if (CdnStreamingPolicy.isCancellation(e, Thread.currentThread().isInterrupted) ||
                    !again()) throw e
            }
        }
    }

    /** Whether to try the range once more, switching sources if there is another. */
    private fun again(): Boolean {
        val s = stream ?: return false
        // One bounded fallback per range: do not revisit a CDN already
        // known to have stalled, or loop against a single unreachable URL.
        if (tries >= CdnStreamingPolicy.MAX_RANGE_RETRIES ||
            Thread.currentThread().isInterrupted) return false
        tries++
        source = (source + 1) % s.sources.size
        s.record(if (s.sources.size > 1) "RANGE_SWITCH_BACKUP" else "RANGE_RETRY_SAME")
        return true
    }

    private fun closeUpstream() {
        val ds = upstream ?: return
        upstream = null
        if (connectionStartedAtMs > 0L && connectionBytes > 0L)
            stream?.record("RANGE_TRANSFER",
                android.os.SystemClock.elapsedRealtime() - connectionStartedAtMs,
                httpCode(ds), connectionBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        connectionStartedAtMs = 0L
        connectionBytes = 0L
        firstByteSeen = false
        slowReadMs = 0L
        slowReadBytes = 0L
        reconnectOnNextRead = false
        runCatching { ds.close() }
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        spec = null
        try {
            closeUpstream()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
