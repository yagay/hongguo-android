package com.yagay.hongguo.kxg

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A strictly contiguous, encrypted on-disk prefix. Every successful HTTP
 * chunk is committed independently; readers may use partially downloaded
 * data immediately. Absolute offsets prevent duplicate bytes on reconnect.
 *
 * Only created inside Context.cacheDir; callers must dispose when a source
 * is discarded. CENC decryption happens AFTER reading from this file.
 */
internal class EncryptedPrefixFile(private val file: File) {
    private var handle: RandomAccessFile? = null
    private var disposed = false
    @Volatile private var publishedBytes = 0
    val size: Int get() = publishedBytes

    private fun writer(): RandomAccessFile {
        if (disposed) throw IOException("缓存已关闭")
        handle?.let { return it }
        if (file.parentFile?.exists() != true && file.parentFile?.mkdirs() != true)
            throw IOException("无法创建视频缓存目录")
        return RandomAccessFile(file, "rw").also { handle = it }
    }

    /**
     * Appends ONLY the missing suffix if this range touches the contiguous
     * prefix. Overlapping retransmissions are ignored, not duplicated.
     */
    @Synchronized
    fun append(position: Long, data: ByteArray, offset: Int, count: Int, limit: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= data.size - count)
        if (disposed) throw IOException("缓存已关闭")
        if (count == 0 || position < 0L || position > publishedBytes.toLong() ||
            publishedBytes >= limit) return 0
        val overlap = (publishedBytes.toLong() - position).toInt()
        if (overlap >= count) return 0
        val add = minOf(count - overlap, limit - publishedBytes)
        if (add <= 0) return 0
        val writer = writer()
        // Even a user-selected 4 GiB budget must not exhaust the phone's
        // storage. Leave at least 256 MiB free and fail open to CDN streaming.
        if (publishedBytes % (4 * KxgWarmupPolicy.MIB) == 0 &&
            file.usableSpace < 256L * KxgWarmupPolicy.MIB) return 0
        writer.seek(publishedBytes.toLong())
        writer.write(data, offset + overlap, add)
        publishedBytes += add
        return add
    }

    @Synchronized
    fun copy(position: Long, target: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && count >= 0 && offset <= target.size - count)
        if (disposed || count == 0 || position < 0L || position >= publishedBytes) return 0
        val input = handle ?: return 0
        val length = minOf(count, publishedBytes - position.toInt())
        input.seek(position)
        input.readFully(target, offset, length)
        return length
    }

    /** Reclaim space without invalidating the signed source or active stream. */
    @Synchronized
    fun evict() {
        handle?.close()
        handle = null
        publishedBytes = 0
        if (file.exists()) file.delete()
    }

    @Synchronized
    fun dispose() {
        disposed = true
        evict()
    }
}
