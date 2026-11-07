package com.hongguo.rewrite

import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Bounded diagnostic journal. Never persist URLs, titles, search text,
 * episode IDs, API keys, cookies, headers or raw exception messages.
 */
class DiagnosticLog(private val directory: File, enabled: Boolean = true) {
    @Volatile var enabled: Boolean = enabled
    private val file = File(directory, "diagnostic-events.txt")

    @Synchronized
    fun record(
        stage: String,
        event: String,
        durationMs: Long? = null,
        httpStatus: Int? = null,
        bytes: Int? = null,
    ) {
        if (!enabled) return
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return
            val cleanStage = token(stage)
            val cleanEvent = token(event)
            val suffix = buildString {
                if (durationMs != null) append(" ms=").append(durationMs.coerceIn(0, 120000))
                if (httpStatus != null) append(" http=").append(httpStatus.coerceIn(0, 999))
                if (bytes != null) append(" bytes=").append(bytes.coerceIn(0, 100_000_000))
            }
            val line = "${Instant.now()} stage=$cleanStage event=$cleanEvent$suffix\n"
            if (file.length() + line.length > MAX_BYTES) {
                val previous = if (file.exists()) file.readText(Charsets.UTF_8) else ""
                val recent = previous.takeLast(MAX_BYTES / 2).substringAfter('\n', "")
                file.writeText(recent, Charsets.UTF_8)
            }
            file.appendText(line, Charsets.UTF_8)
        }
    }

    @Synchronized
    fun snapshot(): String = runCatching {
        val body = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        "Hongguo Native Rewrite - Diagnostic log\n" +
            "Generated UTC: ${Instant.now()}\n" +
            "Format: UTC timestamp, stage, event code, elapsed ms, HTTP status, response bytes\n" +
            "Sensitive values (URLs, tokens, search queries, drama titles, cookies) are not collected.\n" +
            "Logging: " + if (enabled) "ON\n\n$body" else "OFF\n\n$body"
    }.getOrElse { "Diagnostic log unavailable\n" }

    @Synchronized
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        fun token(value: String): String =
            value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_")
                .take(48).ifBlank { "UNKNOWN" }
    }
}
