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

    @Test fun insetTrackReachesEndpointsWithoutTouchingScreenEdges() {
        val width = 360f
        val inset = 24f
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(24f, width, inset), 0f)
        assertEquals(0.5f, PlaybackSeekGeometry.fractionAt(180f, width, inset), 0.0001f)
        assertEquals(1f, PlaybackSeekGeometry.fractionAt(336f, width, inset), 0f)
        // Touches outside the visible track clamp to the nearest end.
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(0f, width, inset), 0f)
        assertEquals(1f, PlaybackSeekGeometry.fractionAt(width, width, inset), 0f)
        assertEquals(0L, PlaybackSeekGeometry.positionAt(50_000L,
            PlaybackSeekGeometry.fractionAt(24f, width, inset)))
        assertEquals(50_000L, PlaybackSeekGeometry.positionAt(50_000L,
            PlaybackSeekGeometry.fractionAt(336f, width, inset)))
    }

    @Test fun insetIsSafeForTinyAndInvalidWidths() {
        val inset = PlaybackSeekGeometry.safeInsetPx(20f, 24f)
        assertEquals(8f, inset, 0f)
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(8f, 20f, 24f), 0f)
        assertEquals(1f, PlaybackSeekGeometry.fractionAt(12f, 20f, 24f), 0f)
        assertEquals(0f, PlaybackSeekGeometry.safeInsetPx(-1f, 24f), 0f)
        assertEquals(0f, PlaybackSeekGeometry.safeInsetPx(Float.NaN, 24f), 0f)
        assertEquals(0f, PlaybackSeekGeometry.fractionAt(20f, Float.NaN, 24f), 0f)
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
