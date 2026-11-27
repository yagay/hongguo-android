package com.yagay.hongguo.kxg

import dev.openhongguo.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleStreamSelectorTest {
    private fun stream(codec: String, quality: Int, rate: Long = 1000L) =
        Stream(codec = codec, definition = "${quality}p", bitrate = rate,
            url = "https://example.invalid/video.mp4")

    @Test fun highestCompatibleQualityWinsEvenWithHigherUnsupportedRendition() {
        val result = CompatibleStreamSelector.select(listOf(
            stream("bytevc2", 2160), stream("hevc", 1080), stream("avc", 720)
        ), false, 0) as CompatibleStreamSelector.Result.Ready
        assertEquals("1080p", result.stream.definition)
        assertEquals(1, result.excludedCodecCount)
    }

    @Test fun respectsQualityCapAndFallsBackToLowestCompatibleWhenNecessary() {
        val rows = listOf(stream("hevc", 1080), stream("avc", 720), stream("bvc2", 480))
        val cap = CompatibleStreamSelector.select(rows, false, 720) as CompatibleStreamSelector.Result.Ready
        assertEquals("720p", cap.stream.definition)
        val low = CompatibleStreamSelector.select(rows, false, 480) as CompatibleStreamSelector.Result.Ready
        assertEquals("720p", low.stream.definition)
    }

    @Test fun actualDecoderCapabilityFiltersAndPrefersHardwareAtSameQuality() {
        val rows = listOf(stream("av1", 2160), stream("hevc", 1080, 900),
            stream("avc", 1080, 2000))
        val result = CompatibleStreamSelector.select(rows, false, 0) {
            when (it.codec) { "av1" -> 0; "hevc" -> 2; else -> 1 }
        } as CompatibleStreamSelector.Result.Ready
        assertEquals("hevc", result.stream.codec)
        assertEquals(1, result.excludedCodecCount)
    }

    @Test fun distinctFailureTypesAndUnknownCodecRemainSupported() {
        assertEquals(CompatibleStreamSelector.Result.UnsupportedCodec,
            CompatibleStreamSelector.select(listOf(stream("VVC1.1", 1080)), false, 0))
        assertEquals(CompatibleStreamSelector.Result.UnsupportedContainer,
            CompatibleStreamSelector.select(listOf(stream("avc", 720).copy(hls = true)), false, 0))
        assertEquals(CompatibleStreamSelector.Result.UnsupportedContainer,
            CompatibleStreamSelector.select(listOf(stream("avc", 720)), true, 0))
        assertEquals(CompatibleStreamSelector.Result.Empty,
            CompatibleStreamSelector.select(listOf(stream("avc", 720).copy(url = "")), false, 0))
        assertTrue(CompatibleStreamSelector.select(listOf(stream("", 720)), false, 0)
            is CompatibleStreamSelector.Result.Ready)
    }
}
