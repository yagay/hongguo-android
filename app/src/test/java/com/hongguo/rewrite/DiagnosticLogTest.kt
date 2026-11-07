package com.hongguo.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stageAndStatusSavedWithoutOtherFields() {
        val journal = DiagnosticLog(temporary.newFolder())
        journal.record("WEBSITE_HTTP", "FAIL_CATEGORY", durationMs = 987,
            httpStatus = 403, bytes = 52)
        val result = journal.snapshot()
        assertTrue(result.contains("stage=WEBSITE_HTTP event=FAIL_CATEGORY ms=987 http=403 bytes=52"))
        assertFalse(result.contains("api_key="))
        assertFalse(result.contains("Cookie="))
    }

    @Test fun disabledDoesNotWriteEntries() {
        val folder = temporary.newFolder()
        val journal = DiagnosticLog(folder, enabled = false)
        journal.record("PLAYBACK", "START")
        assertFalse(File(folder, "diagnostic-events.txt").exists())
        journal.enabled = true
        journal.record("PLAYBACK", "START")
        assertTrue(journal.snapshot().contains("event=START"))
        journal.clear()
        assertFalse(journal.snapshot().contains("event=START"))
    }

    @Test fun logSizeIsBounded() {
        val directory = temporary.newFolder()
        val journal = DiagnosticLog(directory)
        repeat(7500) { journal.record("CATALOG", "SUCCESS", durationMs = 15) }
        val file = File(directory, "diagnostic-events.txt")
        assertTrue(file.length() in 1..DiagnosticLog.MAX_BYTES.toLong())
        assertTrue(journal.snapshot().contains("stage=CATALOG"))
    }
}
