package com.yagay.hongguo

import android.content.Context
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
            ("Last uncaught crash (sanitized, if any):\n" +
                CrashCapture.snapshot(directory) + "\n") +
            "Logging: " + if (enabled) "ON\n\n$body" else "OFF\n\n$body"
    }.getOrElse { "Diagnostic log unavailable\n" }

    @Synchronized
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
        CrashCapture.clear(directory)
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        fun token(value: String): String =
            value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9_]"), "_")
                .take(48).ifBlank { "UNKNOWN" }
    }
}


/**
 * App-only uncaught crash breadcrumb. Never writes exception messages or arbitrary stack traces:
 * the latter can contain user-entered URLs, tokens and search text.
 */
internal object CrashCapture {
    private const val FILE_NAME = "last-crash.txt"
    @Volatile private var installed = false

    @Synchronized fun install(context: Context) {
        if (installed) return
        val directory = File(context.filesDir, "diagnostics")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (!directory.exists()) directory.mkdirs()
                val cause = generateSequence(error) { it.cause }.take(6).last()
                val frames = cause.stackTrace.filter { it.className.startsWith("com.hongguo.") ||
                    it.className.startsWith("com.hongguotv.") }.take(12)
                val report = buildString {
                    append("UTC ").append(Instant.now()).append('\n')
                    append("Thread: ").append(
                        if (thread == android.os.Looper.getMainLooper()?.thread)
                            "main" else "worker").append('\n')
                    append("Type: ").append(DiagnosticLog.token(cause.javaClass.simpleName))
                        .append('\n')
                    frames.forEach { frame ->
                        append(DiagnosticLog.token(frame.className)).append('.')
                            .append(DiagnosticLog.token(frame.methodName))
                            .append(" line=").append(frame.lineNumber.coerceAtLeast(0))
                            .append('\n')
                    }
                }
                File(directory, FILE_NAME).writeText(report.take(4096))
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
        installed = true
    }

    fun snapshot(directory: File): String = runCatching {
        val file = File(directory, FILE_NAME)
        if (file.isFile) file.readText().take(4096) else "None"
    }.getOrDefault("Unavailable")

    fun clear(directory: File) {
        runCatching { File(directory, FILE_NAME).delete() }
    }
}
