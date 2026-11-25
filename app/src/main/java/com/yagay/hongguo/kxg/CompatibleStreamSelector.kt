package com.yagay.hongguo.kxg

import dev.openhongguo.Stream
import java.util.Locale

/** Local capability selection only, over the already returned media ladder.
 * Does not fetch another model, change device identity, or negotiate access.
 * Unknown codec labels remain eligible for the existing Media3 extractor.
 */
internal object CompatibleStreamSelector {
    sealed interface Result {
        data class Ready(val stream: Stream, val excludedCodecCount: Int) : Result
        data object UnsupportedCodec : Result
        data object UnsupportedContainer : Result
        data object Empty : Result
    }

    fun select(streams: List<Stream>, separateAudio: Boolean, cap: Int): Result {
        val withUrl = streams.filter { it.url.trim().startsWith("https://") }
        if (withUrl.isEmpty()) return Result.Empty
        if (separateAudio) return Result.UnsupportedContainer
        val singleFile = withUrl.filter { !it.hls && !it.dash }
        if (singleFile.isEmpty()) return Result.UnsupportedContainer
        val supported = singleFile.filterNot { unsupportedCodec(it.codec) }
        if (supported.isEmpty()) return Result.UnsupportedCodec
        val capped = if (cap > 0) supported.filter { lines(it) <= cap } else supported
        val order = compareBy<Stream>({ lines(it) }, { it.bitrate })
        val chosen = if (capped.isEmpty()) supported.minWithOrNull(order)
            else capped.maxWithOrNull(order)
        return Result.Ready(checkNotNull(chosen), singleFile.size - supported.size)
    }

    private fun unsupportedCodec(value: String): Boolean {
        val codec = value.trim().lowercase(Locale.ROOT)
        return listOf("bytevc2", "bvc2", "vvc", "vvc1", "vvi1", "h266", "h.266")
            .any { codec == it || codec.startsWith("$it.") || codec.startsWith("$it-") }
    }

    private fun lines(stream: Stream): Int {
        val label = stream.definition.takeWhile { it in '0'..'9' }.toIntOrNull()
        if (label != null && label > 0) return label
        val shorter = minOf(stream.width, stream.height)
        return if (shorter > 0) shorter else maxOf(stream.width, stream.height)
    }
}
