package com.hongguo.android

import com.hongguo.api.service.ProgressiveMedia
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Recently used progressive sessions keep their already-validated index and sparse encrypted
 * ranges for a short replay / swipe-back window. The active Media3 DataSource owns its Reader,
 * not the Session, so a new player may obtain its own Reader after the previous player leaves.
 *
 * This is intentionally NOT a persistent cross-launch cache: signed CDN links can expire.
 * The pool is bounded to two idle sessions and 30 seconds; all evictions close on IO and
 * delete the session's temporary ciphertext ranges using ProgressiveMedia.Session.close().
 */
internal object PlaybackSessionCoordinator {
    private const val MAX_IDLE = 2
    private const val IDLE_MS = 30_000L

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeLock = Mutex()
    private val guard = Any()
    private val closing = Collections.newSetFromMap(WeakHashMap<ProgressiveMedia.Session, Boolean>())
    private val idle = LinkedHashMap<String, IdleEntry>()

    private class IdleEntry(val video: PreparedVideo) {
        var generation = 0
    }

    /** Non-blocking, single-owner checkout; a future scheduled expiry will no longer close it. */
    fun take(episodeId: String): PreparedVideo? = synchronized(guard) {
        idle.remove(episodeId)?.video
    }

    /** Keep only fully initialized sessions. Local completed MP4 files use Stream's own cache. */
    fun release(video: PreparedVideo?) {
        val session = video?.session ?: return
        val toClose = mutableListOf<ProgressiveMedia.Session>()
        val entry: IdleEntry
        synchronized(guard) {
            if (session in closing) return
            val old = idle.remove(video.episodeId)
            if (old != null && old.video.session !== session) old.video.session?.let(toClose::add)
            // A repeated release resets the timer without creating duplicate owners.
            entry = if (old?.video?.session === session) old else IdleEntry(video)
            entry.generation++
            idle[video.episodeId] = entry
            while (idle.size > MAX_IDLE) {
                val first = idle.entries.iterator().next()
                idle.remove(first.key)
                first.value.video.session?.let(toClose::add)
            }
        }
        toClose.forEach(::closeAsync)
        val generation = entry.generation
        cleanupScope.launch {
            delay(IDLE_MS)
            val expired = synchronized(guard) {
                if (idle[video.episodeId] === entry && entry.generation == generation) {
                    idle.remove(video.episodeId)
                    true
                } else false
            }
            if (expired) closeAsync(session)
        }
    }

    /** Closing is idempotent and serialized, but never runs on the UI or playback thread. */
    private fun closeAsync(session: ProgressiveMedia.Session) {
        synchronized(guard) { if (!closing.add(session)) return }
        cleanupScope.launch {
            closeLock.withLock { runCatching { session.close() } }
        }
    }
}
