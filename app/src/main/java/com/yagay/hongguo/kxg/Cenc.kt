// CENC turns a progressive MP4 encrypted with the 'cenc' scheme (AES-CTR,
// ISO/IEC 23001-7) into a clear one, a byte range at a time.
//
// Decryption never changes a byte's position: samples are decrypted in
// place and the boxes that mark the file as protected are renamed to boxes
// of the same size ('encv' → 'hvc1', 'sinf' → 'free', …). So any range of
// the clear file is the same range of the encrypted file, decrypted, and a
// player can seek through a stream that is decrypted as it downloads.
//
// It is a port of the iOS app's CENC target, itself a port of the macOS
// app's internal/cenc package, and must stay byte for byte in step with
// them.
package com.yagay.hongguo.kxg

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Fetch reads `count` bytes of the file at `offset`. */
fun interface Fetch {
    @Throws(IOException::class)
    fun read(offset: Long, count: Long): ByteArray
}

class CencException(message: String) : IOException(message)

/**
 * Layout is what [decrypt] needs to know about one file: where its samples
 * are, their IVs, and the patched header. It never changes once built, so
 * any thread may decrypt with it.
 */
class Layout private constructor(
    private val key: ByteArray,
    private val moovOffset: Long,
    private val moov: ByteArray, // the patched moov box, written over the original
    private val samples: List<Sample>, // protected samples, sorted by offset
) {
    /** Whether the file had any encrypted samples. */
    val isProtected: Boolean get() = samples.isNotEmpty()

    // Ciphers are not safe to share between threads, and the player reads
    // from several.
    private val cipher = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher =
            Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
    }

    companion object {
        /**
         * Reads the moov box of a file of `total` bytes through `fetch` and
         * returns its layout for the content key.
         */
        @Throws(IOException::class)
        fun open(total: Long, key: ByteArray, fetch: Fetch): Layout {
            val (offset, moov) = findMoov(total, fetch)
            return parse(offset, moov, key, fetch)
        }

        /**
         * Builds the layout of a file whose moov box, at `moovOffset`, is
         * `moov`. `fetch` reads auxiliary data that saio places outside moov;
         * it may be null when the file keeps its IVs in senc boxes.
         */
        @Throws(IOException::class)
        fun parse(moovOffset: Long, moov: ByteArray, key: ByteArray, fetch: Fetch?): Layout {
            if (key.size !in setOf(16, 24, 32)) {
                throw CencException("cenc: invalid key size ${key.size}")
            }
            val buf = Buffer(moov.copyOf())
            val root = Box.parse(buf, 0, buf.count)
            if (root == null || root.type != "moov") throw CencException("cenc: not a moov box")
            val samples = ArrayList<Sample>()
            for (b in root.children()) {
                when (b.type) {
                    "trak" -> samples += parseTrak(b, moovOffset, fetch)
                    "pssh" -> b.rename("free")
                }
            }
            samples.sortBy { it.offset }
            return Layout(key.copyOf(), moovOffset, buf.bytes, samples)
        }
    }

    /** Turns `len` bytes of `buf` from `off`, the bytes of the file at `at`, into the clear file's. */
    fun decrypt(buf: ByteArray, off: Int, len: Int, at: Long) {
        if (len <= 0) return
        val end = at + len
        // The patched header.
        val mEnd = moovOffset + moov.size
        if (at < mEnd && end > moovOffset) {
            val a = maxOf(at, moovOffset)
            val b = minOf(end, mEnd)
            System.arraycopy(moov, (a - moovOffset).toInt(), buf, off + (a - at).toInt(), (b - a).toInt())
        }
        // The samples.
        var i = firstSample(endingAfter = at)
        while (i < samples.size && samples[i].offset < end) {
            val s = samples[i]
            i++
            val subs = s.subsamples
            if (subs == null) {
                val a = maxOf(at, s.offset)
                val b = minOf(end, s.offset + s.size)
                xor(buf, off + (a - at).toInt(), (b - a).toInt(), s.iv, a - s.offset)
                continue
            }
            // Subsamples: the encrypted parts make one CTR stream.
            var pos = s.offset
            var stream = 0L
            var k = 0
            while (k < subs.size) {
                pos += subs[k]
                val enc = subs[k + 1]
                val encEnd = pos + enc
                val a = maxOf(at, pos)
                val b = minOf(end, encEnd)
                if (a < b) {
                    xor(buf, off + (a - at).toInt(), (b - a).toInt(), s.iv, stream + (a - pos))
                }
                stream += enc
                pos = encEnd
                k += 2
            }
        }
    }

    /** Decrypts all of `buf`, the bytes of the file at `at`, in place. */
    fun decrypt(buf: ByteArray, at: Long) = decrypt(buf, 0, buf.size, at)

    /** The index of the first sample that ends after `offset`. */
    private fun firstSample(endingAfter: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].offset + samples[mid].size > endingAfter) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * Applies the keystream of `iv`, from byte `at` of its stream, to the
     * `count` bytes of `dst` from `dstOff`. The counter is the IV's low 64
     * bits, as CENC and ffmpeg count blocks.
     */
    private fun xor(dst: ByteArray, dstOff: Int, count: Int, iv: IV, at: Long) {
        if (count <= 0) return
        val firstBlock = at / 16
        val skip = (at % 16).toInt()
        val blocks = (skip + count + 15) / 16
        // The counter blocks, all at once, and their encryption in one call.
        val counters = ByteArray(blocks * 16)
        for (k in 0 until blocks) {
            putBE64(counters, k * 16, iv.high)
            putBE64(counters, k * 16 + 8, iv.low + firstBlock + k) // wraps as UInt64 does
        }
        val stream = cipher.get()!!.doFinal(counters)
        for (k in 0 until count) {
            dst[dstOff + k] = (dst[dstOff + k].toInt() xor stream[skip + k].toInt()).toByte()
        }
    }
}

// MARK: - The moov box

internal class IV(val high: Long = 0, val low: Long = 0) {
    companion object {
        /** Reads an IV of 8 or 16 bytes; an 8-byte IV is the high half. */
        fun of(b: ByteArray, from: Int, count: Int): IV {
            val all = ByteArray(16)
            System.arraycopy(b, from, all, 0, minOf(count, 16))
            return IV(be64(all, 0), be64(all, 8))
        }
    }
}

internal class Sample(
    val offset: Long,
    val size: Long,
    val iv: IV,
    /** Pairs of clear and encrypted byte counts; null: the whole sample is encrypted. */
    val subsamples: LongArray?,
)

/**
 * findMoov walks the top-level boxes until it finds moov, which faststart
 * files keep before mdat and others after it.
 */
internal fun findMoov(total: Long, fetch: Fetch): Pair<Long, ByteArray> {
    val probe = 64L shl 10
    val head = fetch.read(0, minOf(probe, total))
    var off = 0L
    while (off + 8 <= total) {
        val hdr = if (off + 16 <= head.size) {
            head.copyOfRange(off.toInt(), off.toInt() + 16)
        } else {
            fetch.read(off, minOf(16, total - off))
        }
        if (hdr.size < 8) throw CencException("cenc: short box header at $off")
        var size = be32(hdr, 0)
        val type = fourCC(hdr, 4)
        if (size == 1L && hdr.size >= 16) {
            size = be64(hdr, 8)
        } else if (size == 0L) {
            size = total - off
        }
        if (size < 8 || off + size > total) {
            throw CencException("cenc: bad box \"$type\" at $off")
        }
        if (type == "moov") {
            if (off + size <= head.size) {
                return off to head.copyOfRange(off.toInt(), (off + size).toInt())
            }
            val moov = fetch.read(off, size)
            if (moov.size.toLong() != size) throw CencException("cenc: short moov box")
            return off to moov
        }
        off += size
    }
    throw CencException("cenc: no moov box")
}

/** Track is what one trak says about its protection. */
private class Track {
    var ivSize = 0
    var constIV = ByteArray(0)
    var isProtected = false
    var sizes = LongArray(0)
    var offsets = ArrayList<Long>()
    var ivs: Array<IV> = emptyArray()
    var subs: Array<LongArray?> = emptyArray()
    var senc = false
    var saizDefault = 0
    var saizSizes = ByteArray(0)
    val saioOffsets = ArrayList<Long>()
}

private fun parseTrak(trak: Box, moovOffset: Long, fetch: Fetch?): List<Sample> {
    val stbl = trak.find("mdia", "minf", "stbl") ?: return emptyList()
    val stsd = stbl.find("stsd") ?: return emptyList()
    if (stsd.bodyCount < 8) return emptyList()
    val entry = Box.parse(stsd.buf, stsd.bodyAt + 8, stsd.end) ?: return emptyList()
    val bytes = entry.buf.bytes
    var start: Int
    when (entry.type) {
        "encv" -> start = 78
        "enca" -> {
            start = 28
            if (entry.bodyCount >= 10) {
                when (be16(bytes, entry.bodyAt + 8)) { // QuickTime sound versions
                    1 -> start += 16
                    2 -> start += 36
                }
            }
        }
        else -> return emptyList() // a clear track
    }
    val t = Track()
    var format = ""
    for (b in entry.children(start)) {
        if (b.type != "sinf") continue
        b.find("frma")?.let { frma ->
            if (frma.bodyCount >= 4) format = fourCC(bytes, frma.bodyAt)
        }
        b.find("schm")?.let { schm ->
            if (schm.bodyCount >= 8) {
                val scheme = fourCC(bytes, schm.bodyAt + 4)
                if (scheme != "cenc") throw CencException("cenc: scheme \"$scheme\" is not supported")
            }
        }
        val tenc = b.find("schi", "tenc")
        if (tenc == null || tenc.bodyCount < 24) throw CencException("cenc: no tenc box")
        val p = tenc.bodyAt
        t.isProtected = bytes[p + 6].toInt() != 0
        t.ivSize = bytes[p + 7].toInt() and 0xff
        if (t.isProtected && t.ivSize == 0 && tenc.bodyCount >= 25) {
            val n = bytes[p + 24].toInt() and 0xff
            if (tenc.bodyCount >= 25 + n) {
                t.constIV = bytes.copyOfRange(p + 25, p + 25 + n)
            }
        }
        b.rename("free")
    }
    if (format.isEmpty()) throw CencException("cenc: no original format")
    entry.rename(format)
    if (!t.isProtected) return emptyList()

    val stsc = ArrayList<Long>()
    for (b in stbl.children()) {
        val p0 = b.bodyAt
        val count = b.bodyCount
        when (b.type) {
            "stsz" -> {
                if (count < 12) throw CencException("cenc: bad stsz")
                val fixed = be32(bytes, p0 + 4)
                val n = be32(bytes, p0 + 8)
                if (n > 50_000_000) throw CencException("cenc: bad stsz")
                t.sizes = LongArray(n.toInt()) { k ->
                    when {
                        fixed != 0L -> fixed
                        12 + 4 * k + 4 <= count -> be32(bytes, p0 + 12 + 4 * k)
                        else -> 0
                    }
                }
            }
            "stsc" -> {
                if (count < 8) throw CencException("cenc: bad stsc")
                val n = be32(bytes, p0 + 4)
                var k = 0
                while (k < n && 8 + 12 * k + 12 <= count) {
                    val e = p0 + 8 + 12 * k
                    stsc += be32(bytes, e)
                    stsc += be32(bytes, e + 4)
                    k++
                }
            }
            "stco", "co64" -> {
                if (count < 8) throw CencException("cenc: bad ${b.type}")
                val n = be32(bytes, p0 + 4)
                val w = if (b.type == "co64") 8 else 4
                t.offsets = ArrayList()
                var k = 0
                while (k < n && 8 + w * k + w <= count) {
                    val e = p0 + 8 + w * k
                    t.offsets += if (w == 4) be32(bytes, e) else be64(bytes, e)
                    k++
                }
            }
            "senc" -> {
                parseSenc(t, bytes, p0, count)
                b.rename("free")
            }
            "saiz" -> {
                if (count < 4) throw CencException("cenc: bad saiz")
                var p = 4
                if (bytes[p0 + 3].toInt() and 1 != 0) p += 8
                if (count < p + 5) throw CencException("cenc: bad saiz")
                t.saizDefault = bytes[p0 + p].toInt() and 0xff
                val n = be32(bytes, p0 + p + 1)
                if (t.saizDefault == 0 && count >= p + 5 + n) {
                    t.saizSizes = bytes.copyOfRange(p0 + p + 5, p0 + p + 5 + n.toInt())
                }
                b.rename("free")
            }
            "saio" -> {
                if (count < 4) throw CencException("cenc: bad saio")
                var p = 4
                if (bytes[p0 + 3].toInt() and 1 != 0) p += 8
                if (count < p + 4) throw CencException("cenc: bad saio")
                val n = be32(bytes, p0 + p)
                val w = if (bytes[p0].toInt() == 1) 8 else 4
                var k = 0
                while (k < n && p + 4 + w * k + w <= count) {
                    val e = p0 + p + 4 + w * k
                    t.saioOffsets += if (w == 4) be32(bytes, e) else be64(bytes, e)
                    k++
                }
                b.rename("free")
            }
        }
    }
    if (!t.senc) readAux(t, bytes, moovOffset, fetch)
    return addSamples(t, stsc)
}

private fun parseSenc(t: Track, b: ByteArray, at: Int, count: Int) {
    if (count < 8) throw CencException("cenc: bad senc")
    t.senc = true
    val flags = be32(b, at) and 0xffffff
    val n = be32(b, at + 4)
    readInfo(t, b, at + 8, count - 8, n, subsamples = flags and 2L != 0L, sizes = null)
}

/**
 * Reads `n` samples' IVs, and their subsamples when `subsamples`, from the
 * `len` bytes of `b` at `base`. `sizes`, when given, is each sample's info
 * size, which says whether it has subsamples.
 */
private fun readInfo(t: Track, b: ByteArray, base: Int, len: Int, n: Long, subsamples: Boolean, sizes: ((Int) -> Int)?) {
    // Each sample takes a byte at least, or its IV is the constant one.
    if (n < 0 || n > 50_000_000 || (t.ivSize > 0 && n > len)) throw CencException("cenc: sample info is short")
    val count = n.toInt()
    t.ivs = Array(count) { IV() }
    t.subs = arrayOfNulls(count)
    var p = 0
    for (k in 0 until count) {
        val start = p
        if (t.ivSize > 0) {
            if (p + t.ivSize > len) throw CencException("cenc: sample info is short")
            t.ivs[k] = IV.of(b, base + p, t.ivSize)
            p += t.ivSize
        } else {
            t.ivs[k] = IV.of(t.constIV, 0, t.constIV.size)
        }
        var has = subsamples
        if (sizes != null) has = sizes(k) > t.ivSize
        if (has) {
            if (p + 2 > len) throw CencException("cenc: subsample count is short")
            val m = be16(b, base + p)
            p += 2
            if (p + 6 * m > len) throw CencException("cenc: subsamples are short")
            val subs = LongArray(m * 2)
            for (j in 0 until m) {
                subs[2 * j] = be16(b, base + p).toLong()
                subs[2 * j + 1] = be32(b, base + p + 2)
                p += 6
            }
            t.subs[k] = subs
        }
        if (sizes != null) p = start + sizes(k)
    }
}

/** Reads the sample info that saiz sizes and saio places, inside moov or through fetch. */
private fun readAux(t: Track, moov: ByteArray, moovOffset: Long, fetch: Fetch?) {
    if (t.saioOffsets.size != 1) throw CencException("cenc: no sample encryption info")
    val n = t.sizes.size
    val saizDefault = t.saizDefault
    val saizSizes = t.saizSizes
    val ivSize = t.ivSize
    val size: (Int) -> Int = { k ->
        when {
            saizDefault != 0 -> saizDefault
            k < saizSizes.size -> saizSizes[k].toInt() and 0xff
            else -> ivSize
        }
    }
    var total = 0L
    for (k in 0 until n) total += size(k)
    val off = t.saioOffsets[0]
    if (off >= moovOffset && off + total <= moovOffset + moov.size) {
        readInfo(t, moov, (off - moovOffset).toInt(), total.toInt(), n.toLong(), false, size)
    } else if (fetch != null) {
        val b = fetch.read(off, total)
        readInfo(t, b, 0, b.size, n.toLong(), false, size)
    } else {
        throw CencException("cenc: sample info is outside moov")
    }
}

/** Places the track's samples in the file from its chunks. */
private fun addSamples(t: Track, stsc: List<Long>): List<Sample> {
    if (stsc.isEmpty() || t.offsets.isEmpty()) throw CencException("cenc: no chunks")
    if (t.ivs.size < t.sizes.size) {
        throw CencException("cenc: ${t.ivs.size} IVs for ${t.sizes.size} samples")
    }
    val out = ArrayList<Sample>(t.sizes.size)
    var k = 0
    var e = 0
    while (e < stsc.size) {
        val first = stsc[e].toInt() - 1
        val per = stsc[e + 1]
        var last = t.offsets.size
        if (e + 2 < stsc.size) last = stsc[e + 2].toInt() - 1
        var chunk = maxOf(first, 0)
        while (chunk < last && chunk < t.offsets.size) {
            var off = t.offsets[chunk]
            var j = 0L
            while (j < per && k < t.sizes.size) {
                out += Sample(off, t.sizes[k], t.ivs[k], t.subs[k])
                off += t.sizes[k]
                k++
                j++
            }
            chunk++
        }
        e += 2
    }
    if (k != t.sizes.size) throw CencException("cenc: chunks hold $k of ${t.sizes.size} samples")
    return out
}

// MARK: - Boxes

/** Buffer is the moov box, shared by the boxes inside it, which rename themselves in place. */
internal class Buffer(val bytes: ByteArray) {
    val count: Int get() = bytes.size
}

/** Box is an MP4 box inside a buffer that it can rename in place. */
internal class Box(val buf: Buffer, val at: Int, val bodyAt: Int, val end: Int, val type: String) {
    val bodyCount: Int get() = end - bodyAt

    companion object {
        /** Reads the box at `at`, which must end by `limit`. */
        fun parse(buf: Buffer, at: Int, limit: Int): Box? {
            if (at.toLong() + 8 > limit) return null
            var size = be32(buf.bytes, at)
            var hdr = 8
            if (size == 1L) {
                if (at.toLong() + 16 > limit) return null
                val big = be64(buf.bytes, at + 8)
                if (big < 0) return null
                size = big
                hdr = 16
            } else if (size == 0L) {
                size = (limit - at).toLong()
            }
            if (size < hdr || at + size > limit) return null
            return Box(buf, at, at + hdr, (at + size).toInt(), fourCC(buf.bytes, at + 4))
        }
    }

    /** Lists the boxes in the body from byte `start` of it on. */
    fun children(start: Int = 0): List<Box> {
        val out = ArrayList<Box>()
        var at = bodyAt + start
        while (at < end) {
            val c = parse(buf, at, end) ?: break
            out += c
            at = c.end
        }
        return out
    }

    /** Follows a path of child box types. */
    fun find(vararg path: String): Box? {
        var cur = this
        for (type in path) {
            cur = cur.children().firstOrNull { it.type == type } ?: return null
        }
        return cur
    }

    fun rename(type: String) {
        val t = type.toByteArray(Charsets.US_ASCII)
        if (t.size != 4) return
        System.arraycopy(t, 0, buf.bytes, at + 4, 4)
    }
}

// MARK: - Bytes

internal fun be16(b: ByteArray, i: Int): Int =
    (b[i].toInt() and 0xff shl 8) or (b[i + 1].toInt() and 0xff)

/** A big-endian UInt32, as a Long. */
internal fun be32(b: ByteArray, i: Int): Long =
    ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

/** A big-endian UInt64, its bits in a Long. */
internal fun be64(b: ByteArray, i: Int): Long = (be32(b, i) shl 32) or be32(b, i + 4)

internal fun putBE64(b: ByteArray, i: Int, v: Long) {
    for (k in 0 until 8) b[i + k] = (v ushr (56 - 8 * k)).toByte()
}

internal fun fourCC(b: ByteArray, i: Int): String = String(b, i, 4, Charsets.ISO_8859_1)
