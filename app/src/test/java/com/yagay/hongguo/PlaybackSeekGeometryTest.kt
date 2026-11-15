package com.yagay.hongguo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekGeometryTest {
    @Test fun positionMapsSameAcrossThinTrackAndWideStatusRow() {
        val duration = 120_000L
        // Both gesture surfaces are full-width and use the same x-coordinate.
        val x = 270f
        val width = 360f
        val fraction = PlaybackSeekGeometry.fractionAt(x, width)
        assertEquals(0.75f, fraction, 0.0001f)
        assertEquals(90_000L, PlaybackSeekGeometry.positionAt(duration, fraction))
    }

    @Test fun dragToEitherEndClampsWithinMediaDuration() {
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(-50f, 360f), 0f)
        assertEquals(1f, PlaybackSeekGeometry.fractionAt(600f, 360f), 0f)
        assertEquals(0L, PlaybackSeekGeometry.positionAt(
            87_654L, PlaybackSeekGeometry.fractionAt(-50f, 360f)))
        assertEquals(87_654L, PlaybackSeekGeometry.positionAt(
            87_654L, PlaybackSeekGeometry.fractionAt(600f, 360f)))
    }

    @Test fun unknownDurationAndInvalidWidthNeverSeekOutsideRange() {
        assertEquals(0L, PlaybackSeekGeometry.positionAt(
            0L, PlaybackSeekGeometry.fractionAt(180f, 360f)))
        assertEquals(0L, PlaybackSeekGeometry.positionAt(
            -1L, PlaybackSeekGeometry.fractionAt(180f, 360f)))
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(100f, 0f), 0f)
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(Float.NaN, 360f), 0f)
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(100f, Float.POSITIVE_INFINITY), 0f)
        assertEquals(0L, PlaybackSeekGeometry.positionAt(120_000L, Float.NaN))
    }
}
