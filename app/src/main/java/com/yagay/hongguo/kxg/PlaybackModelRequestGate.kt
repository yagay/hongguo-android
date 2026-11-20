package com.yagay.hongguo.kxg

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * One pacing point for every SDK playback-model request, regardless of
 * whether it comes from the foreground player or next-episode prefetch.
 *
 * The official SDK already owns identity, signing and bounded device resets.
 * This gate NEVER creates a second identity or retries anonymous denial.
 *
 * A speculative request is deliberately non-queuing: it skips work when
 * foreground playback is pending or another model request is running.
 */
internal object PlaybackModelRequestGate {
    private const val MIN_START_SPACING_MS = 1_000L
    private const val PREFETCH_MODEL_DEADLINE_MS = 4_000L
    private val lock = Mutex()
    private val foregroundPending = AtomicInteger(0)
    private var lastStartNs = 0L

    suspend fun <T> foreground(block: suspend () -> T): T {
        foregroundPending.incrementAndGet()
        try {
            lock.lock()
            try {
                waitForNextTurn()
                return block()
            } finally {
                lock.unlock()
            }
        } finally {
            foregroundPending.decrementAndGet()
        }
    }

    suspend fun <T> speculative(block: suspend () -> T): T? {
        if (foregroundPending.get() > 0 || !lock.tryLock()) return null
        try {
            if (foregroundPending.get() > 0) return null
            waitForNextTurn()
            if (foregroundPending.get() > 0) return null
            return withTimeoutOrNull(PREFETCH_MODEL_DEADLINE_MS) { block() }
        } finally {
            lock.unlock()
        }
    }

    private suspend fun waitForNextTurn() {
        if (lastStartNs != 0L) {
            val elapsedMs = ((System.nanoTime() - lastStartNs) / 1_000_000L)
                .coerceAtLeast(0L)
            val remaining = MIN_START_SPACING_MS - elapsedMs
            if (remaining > 0L) delay(remaining)
        }
        lastStartNs = System.nanoTime()
    }
}
