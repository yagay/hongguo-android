package com.hongguo.rewrite

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.hongguotv.core.RemoteVideo
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Foreground-process SAF export coordinator. Its request/job ownership is
 * independent of Compose and the main ViewModel. No playback ExoPlayer is
 * created; each job owns and closes exactly one decrypted RemoteVideo stream.
 *
 * A task cannot be resumed after process death. Persisted download queue and
 * WorkManager execution are deliberately NOT claimed by this class.
 */
internal class LocalDownloadManager(
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
    private val source: NativeHongguoSource,
    private val diagnostics: DiagnosticLog,
    private val onTask: (DownloadTask) -> Unit,
    private val onSaved: (OfflineEpisode) -> Unit,
) {
    private data class Target(
        val vid: String,
        val uri: Uri,
        val drama: Drama?,
        val episode: Episode?,
    )

    private val jobs = mutableMapOf<String, Job>()
    private val retryable = mutableMapOf<String, Target>()
    private val slots = Semaphore(2)

    fun start(
        vid: String,
        uri: Uri,
        drama: Drama? = null,
        episode: Episode? = null,
        previousTaskId: String? = null,
    ): String? {
        if (!vid.matches(Regex("[0-9]{1,30}"))) return null
        val previous = previousTaskId?.let { synchronized(retryable) { retryable[it] } }
        if (previousTaskId != null &&
            (previous == null || previous.vid != vid || previous.uri != uri)) return null
        val target = previous ?: Target(vid, uri, drama, episode)
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val taskId = previousTaskId ?: "local-" + UUID.randomUUID()
        synchronized(retryable) { retryable[taskId] = target }
        onTask(DownloadTask(taskId, "等待下载", 0, 1, emptyList()))
        val job = scope.launch(Dispatchers.IO) {
            var acquired = false
            var video: RemoteVideo? = null
            val started = System.nanoTime()
            diagnostics.record("DOWNLOAD", "START")
            try {
                slots.acquire()
                acquired = true
                video = source.openVideo(vid)
                val total = video.total
                if (total <= 0) throw IOException("下载资源长度无效")
                val output = resolver.openOutputStream(uri, "wt")
                    ?: throw IOException("无法打开目标文件")
                output.use { stream ->
                    var offset = 0L
                    var shownPercent = -1
                    val buffer = ByteArray(32 * 1024)
                    while (offset < total) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(512 * 1024L, total - offset).toInt()
                        video.openRead(offset, count).use { input ->
                            var copied = 0
                            while (copied < count) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer, 0, minOf(buffer.size, count - copied))
                                if (n <= 0) throw EOFException("下载片段读取中断")
                                stream.write(buffer, 0, n)
                                copied += n
                            }
                        }
                        offset += count
                        val percent = (offset * 100 / total).toInt()
                        if (percent != shownPercent) {
                            shownPercent = percent
                            onTask(DownloadTask(taskId, "下载中 $percent%", 0, 1, emptyList()))
                        }
                    }
                    stream.flush()
                }
                synchronized(retryable) { retryable.remove(taskId) }
                val storedDrama = target.drama
                val storedEpisode = target.episode
                if (storedDrama != null && storedEpisode != null) {
                    onSaved(OfflineEpisode(
                        storedDrama.id, storedDrama.title, storedDrama.cover,
                        storedEpisode.index, vid, uri.toString(), System.currentTimeMillis(),
                    ))
                }
                onTask(DownloadTask(taskId, "已保存到所选文件", 1, 1, emptyList()))
                diagnostics.record("DOWNLOAD", "COMPLETE",
                    (System.nanoTime() - started) / 1_000_000)
            } catch (e: CancellationException) {
                diagnostics.record("DOWNLOAD", "CANCELLED")
                onTask(DownloadTask(taskId, "已取消 · 可重试（文件未完成）", 0, 1, emptyList()))
                throw e
            } catch (e: Exception) {
                diagnostics.record("DOWNLOAD", "FAILED",
                    (System.nanoTime() - started) / 1_000_000)
                onTask(DownloadTask(taskId, PlaybackProblem.describe("本机下载", e),
                    0, 1, listOf(1)))
            } finally {
                video?.close()
                if (acquired) slots.release()
                val current = currentCoroutineContext()[Job]
                synchronized(jobs) {
                    if (jobs[taskId] === current) jobs.remove(taskId)
                }
            }
        }
        synchronized(jobs) { jobs[taskId] = job }
        return taskId
    }

    fun cancel(taskId: String) {
        synchronized(jobs) { jobs[taskId] }?.cancel()
    }

    fun retry(taskId: String): Boolean {
        if (synchronized(jobs) { jobs[taskId]?.isActive == true }) return false
        val request = synchronized(retryable) { retryable[taskId] } ?: return false
        return start(request.vid, request.uri, request.drama, request.episode, taskId) != null
    }

    fun close() {
        synchronized(jobs) { jobs.values.toList() }.forEach(Job::cancel)
    }
}
