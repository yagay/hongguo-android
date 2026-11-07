package com.hongguo.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Global, opt-out, bounded diagnostic event log. No system logcat capture, raw exceptions,
 * URLs, playback IDs, cookies, search text, account data or media content are exported.
 * Only fixed event labels and bounded numeric measurements are accepted.
 */
internal object AppDiagnostics {
    private const val PREFS = "diagnostic_logging"
    private const val FILE_NAME = "hongguo-diagnostics.log"
    private const val MAX_LINES = 850
    private const val MAX_BYTES = 192 * 1024
    private val mutex = Any()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hongguo-diagnostics").apply { isDaemon = true }
    }
    private val lines = ArrayDeque<String>()
    private var application: Context? = null
    private var generation = 0
    @Volatile private var recording = true

    fun initialize(context: Context) {
        synchronized(mutex) {
            if (application != null) return
            application = context.applicationContext
            recording = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("enabled", true)
            val file = storageFile()
            if (file != null && file.isFile) {
                if (file.length() > MAX_BYTES * 3L) file.delete()
                else runCatching {
                    file.forEachLine(Charsets.UTF_8) { line ->
                        if (line.length <= 240) {
                            lines.addLast(line)
                            while (lines.size > MAX_LINES) lines.removeFirst()
                        }
                    }
                }
            }
        }
        record("APP", "START", stage = "process")
    }

    fun isEnabled(): Boolean = recording

    fun setEnabled(value: Boolean) {
        synchronized(mutex) {
            recording = value
            application?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putBoolean("enabled", value)?.apply()
        }
        if (value) record("APP", "LOGGING_ENABLED")
    }

    private fun storageFile(): File? = application?.filesDir?.resolve(FILE_NAME)

    fun record(area: String, action: String, stage: String? = null,
               elapsedMs: Long? = null, count: Int? = null, code: String? = null) {
        if (!recording) return
        val line = DiagnosticLine.format(System.currentTimeMillis(),
            area, action, stage, elapsedMs, count, code) ?: return
        val version: Int
        synchronized(mutex) {
            if (!recording) return
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            version = generation
        }
        // A single background writer never blocks Compose, Media3 or coroutine callbacks.
        executor.execute {
            synchronized(mutex) {
                if (version != generation) return@execute
                val file = storageFile() ?: return@execute
                runCatching {
                    file.appendText(line + "\n", Charsets.UTF_8)
                    if (file.length() > MAX_BYTES) {
                        val keep = lines.joinToString("\n", postfix = "\n")
                        file.writeText(keep.takeLast(MAX_BYTES), Charsets.UTF_8)
                    }
                }
            }
        }
        Log.i("HongguoDiag", line)
    }

    /** Error class only; never export Throwable.message or stacktrace. */
    fun failure(area: String, action: String = "ERROR",
                error: Throwable, elapsedMs: Long? = null) {
        val code = when (error) {
            is SocketTimeoutException -> "TIMEOUT"
            is UnknownHostException -> "DNS"
            is ConnectException -> "CONNECT"
            is SSLException -> "TLS"
            is java.io.InterruptedIOException -> "INTERRUPTED_IO"
            is IOException -> "IO"
            is IllegalArgumentException -> "INVALID_INPUT"
            is SecurityException -> "PERMISSION"
            else -> "UNEXPECTED"
        }
        record(area, action, code = code, elapsedMs = elapsedMs)
    }

    /** Snapshot is instantaneous and includes unflushed events from this process. */
    fun exportText(): String {
        val snapshot = synchronized(mutex) { lines.toList() }
        return buildString {
            append("Hongguo Android - Local Diagnostic Log\n")
            append("app_version=").append(BuildConfig.VERSION_NAME)
            append("\nandroid_api=").append(Build.VERSION.SDK_INT)
            append("\nlog_enabled=").append(recording)
            append("\nevent_count=").append(snapshot.size)
            append("\nformat=UTC | AREA | EVENT | optional stage/ms/count/code")
            append("\nprivacy=no URLs, search queries, account details, device identifiers, cookies, tokens or raw stacktraces")
            append("\nnote=application-generated events only; not full system logcat")
            append("\n--- Events ---\n")
            snapshot.forEach { append(it).append('\n') }
        }
    }

    /** Delete all prior events; queued writes from the old generation are dropped. */
    fun clear() {
        synchronized(mutex) {
            generation++
            lines.clear()
        }
        executor.execute {
            synchronized(mutex) { storageFile()?.delete() }
        }
    }
}

/** Pure formatting, shared by production and JVM tests; rejects free-form network text. */
internal object DiagnosticLine {
    private val symbol = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    private fun token(value: String?): String? = value?.takeIf { symbol.matches(it) }
    fun format(utcMillis: Long, area: String, action: String, stage: String? = null,
               elapsedMs: Long? = null, count: Int? = null, code: String? = null): String? {
        val safeArea = token(area) ?: return null
        val safeAction = token(action) ?: return null
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(utcMillis))
        return buildString {
            append(date).append(" | ").append(safeArea).append(" | ").append(safeAction)
            token(stage)?.let { append(" | stage=").append(it) }
            elapsedMs?.takeIf { it in 0..600_000 }?.let { append(" | ms=").append(it) }
            count?.takeIf { it in 0..1_000_000 }?.let { append(" | count=").append(it) }
            token(code)?.let { append(" | code=").append(it) }
        }
    }
}
