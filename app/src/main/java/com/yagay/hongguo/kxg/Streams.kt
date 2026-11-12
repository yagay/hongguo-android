package com.yagay.hongguo.kxg

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID

/**
 * StreamRegistry keeps the episodes' files the player may read, by the URI
 * it plays each at. It keeps the last few: the one playing, and one the
 * player may still read.
 */
class StreamRegistry(private val http: DataSource.Factory) {
    private val streams = LinkedHashMap<String, StreamSource>()

    fun register(sources: List<String>, key: ByteArray?): String {
        val uri = "$SCHEME://episode/${UUID.randomUUID()}.mp4"
        synchronized(this) {
            streams[uri] = StreamSource(sources, key, http)
            while (streams.size > 8) streams.remove(streams.keys.first())
        }
        return uri
    }

    operator fun get(uri: String): StreamSource? = synchronized(this) { streams[uri] }
    fun forget(uri: String) = synchronized(this) { streams.remove(uri); Unit }

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
class StreamSource(val sources: List<String>, private val key: ByteArray?, private val http: DataSource.Factory) {
    class File(val size: Long, val layout: Layout?)

    /** The source that answered last, which reads try first. */
    @Volatile
    var good = 0

    private var file: File? = null
    private var failure: IOException? = null

    /** The file's size and layout, learning them the first time; it blocks, on the player's loading thread. */
    @Synchronized
    fun prepare(): File {
        file?.let { return it }
        failure?.let { throw it }
        var last = IOException("没有视频地址")
        for ((i, source) in sources.withIndex()) {
            try {
                val (head, total) = read(source, 0, 64L shl 10)
                val layout = key?.let { k ->
                    Layout.open(total, k) { off, n ->
                        if (off + n <= head.size) head.copyOfRange(off.toInt(), (off + n).toInt()) else read(source, off, n).first
                    }
                }
                good = i
                return File(total, layout).also { file = it }
            } catch (e: InterruptedIOException) {
                throw e // the player gave up on this read: another may try again
            } catch (e: IOException) {
                // CDN errors are handled by the caller; never log signed URLs or keys.
                last = e
            } catch (e: RuntimeException) {
                // CDN errors are handled by the caller; never log signed URLs or keys.
                last = IOException(e)
            }
        }
        failure = last
        throw last
    }

    /** Reads `count` bytes of `source` at `offset`, and the file's size. */
    private fun read(source: String, offset: Long, count: Long): Pair<ByteArray, Long> {
        val ds = http.createDataSource()
        try {
            ds.open(DataSpec.Builder().setUri(source).setPosition(offset).setLength(count).build())
            val total = totalSize(ds.responseHeaders) ?: throw IOException("CDN 没有告知文件大小")
            val out = ByteArrayOutputStream(count.toInt().coerceAtMost(1 shl 20))
            val buf = ByteArray(32 shl 10)
            while (out.size() < count) {
                val n = ds.read(buf, 0, minOf(buf.size.toLong(), count - out.size()).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            return out.toByteArray() to total
        } finally {
            ds.close()
        }
    }
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
        if (position < end) connect()
        opened = true
        transferStarted(dataSpec)
        return end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), end - position).toInt()
        while (true) {
            val n = try {
                upstream!!.read(buffer, offset, want)
            } catch (e: IOException) {
                if (e is InterruptedIOException || !again()) throw e
                connect()
                continue
            }
            if (n == C.RESULT_END_OF_INPUT) {
                // The CDN stopped short of the range.
                if (!again()) throw IOException("视频下载中断")
                connect()
                continue
            }
            layout?.decrypt(buffer, offset, n, position)
            position += n
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
            try {
                ds.open(DataSpec.Builder().setUri(s.sources[source]).setPosition(position).setLength(end - position).build())
                upstream = ds
                return
            } catch (e: IOException) {
                runCatching { ds.close() }
                if (e is InterruptedIOException || !again()) throw e
            }
        }
    }

    /** Whether to try the range once more, switching sources if there is another. */
    private fun again(): Boolean {
        val s = stream ?: return false
        if (tries >= 2 || Thread.currentThread().isInterrupted) return false
        tries++
        source = (source + 1) % s.sources.size
        return true
    }

    private fun closeUpstream() {
        val ds = upstream ?: return
        upstream = null
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
