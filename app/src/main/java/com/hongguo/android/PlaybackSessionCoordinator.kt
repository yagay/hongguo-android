package com.hongguo.android

import com.hongguo.api.service.ProgressiveMedia
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One owner for asynchronous media-session cleanup. Releasing twice is harmless, and a
 * canceled ViewModel scope cannot abandon a reader or its private on-disk media blocks.
 * This does not own the ExoPlayer; its active reads are canceled by the player first.
 */
internal object PlaybackSessionCoordinator {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeLock = Mutex()
    private val scheduled = Collections.newSetFromMap(WeakHashMap<ProgressiveMedia.Session, Boolean>())

    fun release(video: PreparedVideo?) {
        val session = video?.session ?: return
        synchronized(scheduled) {
            if (!scheduled.add(session)) return
        }
        cleanupScope.launch {
            closeLock.withLock { runCatching { session.close() } }
        }
    }
}
