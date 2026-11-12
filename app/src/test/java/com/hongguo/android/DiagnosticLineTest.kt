package com.hongguo.android

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLineTest {
    @Test fun fixedLabelsAndBoundedNumbersOnly() {
        val line = DiagnosticLine.format(0, "CATEGORY", "LOAD_OK",
            stage = "real_library", elapsedMs = 1234, count = 24, code = "HTTP_200")
        assertNotNull(line)
        assertTrue(line!!.contains("1970-01-01T00:00:00.000Z"))
        assertTrue(line.contains("stage=real_library"))
        assertTrue(line.contains("ms=1234"))
        assertTrue(line.contains("count=24"))
    }

    @Test fun cannotInjectUrisSignedMediaOrArbitraryErrorMessages() {
        val url = "https://video.example/?cookie=secret&token=abc"
        val line = DiagnosticLine.format(10, "PLAYBACK", "ERROR", stage = url,
            elapsedMs = -1, count = -2, code = "authorization failed\\nCookie: secret")
        assertNotNull(line)
        assertFalse(line!!.contains("secret"))
        assertFalse(line.contains("token="))
        assertFalse(line.contains("cookie"))
        assertFalse(line.contains("ms="))
        assertFalse(line.contains("count="))
        assertNull(DiagnosticLine.format(10, "https://unsafe", "ERROR"))
        assertNull(DiagnosticLine.format(10, "APP", "bad\\nCookie: x"))
    }
}
