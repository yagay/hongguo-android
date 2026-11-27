package com.yagay.hongguo.kxg

import android.media.MediaCodecList
import dev.openhongguo.Stream
import java.util.Locale

/** Capability ranks: 0 known incompatible, 1 software/unknown, 2 hardware.
 * Query only already available stream metadata; never request another track.
 * Unknown codec labels or unavailable platform metadata remain eligible.
 */
internal object AndroidDecoderCapability {
    private val decoders by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filterNot { it.isEncoder } }.getOrNull()
    }

    fun rank(stream: Stream): Int {
        val codec = stream.codec.trim().lowercase(Locale.ROOT)
        val mime = when {
            codec.startsWith("avc") || codec in setOf("h264", "h.264") -> "video/avc"
            codec.startsWith("hev") || codec.startsWith("hvc") ||
                codec in setOf("h265", "h.265", "bytevc1") -> "video/hevc"
            codec.startsWith("av01") || codec == "av1" -> "video/av01"
            codec.startsWith("vp09") || codec == "vp9" -> "video/x-vnd.on2.vp9"
            else -> return 1
        }
        val available = decoders ?: return 1
        var rank = 0
        var uncertain = false
        for (decoder in available) {
            val type = decoder.supportedTypes.firstOrNull { it.equals(mime, true) } ?: continue
            if (decoder.name.endsWith(".secure", true) || decoder.name.endsWith(".tunneled", true)) continue
            val supported = runCatching {
                val video = decoder.getCapabilitiesForType(type).videoCapabilities
                stream.width <= 0 || stream.height <= 0 ||
                    video.isSizeSupported(stream.width, stream.height)
            }.getOrElse { uncertain = true; false }
            if (supported) {
                rank = maxOf(rank, if (decoder.isHardwareAccelerated) 2 else 1)
                if (rank == 2) return rank
            }
        }
        return if (uncertain && rank == 0) 1 else rank
    }
}
