package com.yagay.hongguo.kxg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackModelRequestGateTest {
    @Test
    fun speculativeLookupNeverQueuesBehindForeground() = runBlocking {
        withTimeout(6_000L) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val active = async {
                PlaybackModelRequestGate.foreground {
                    started.complete(Unit)
                    finish.await()
                    "foreground-model"
                }
            }
            started.await()
            val speculative = PlaybackModelRequestGate.speculative {
                "should-never-be-fetched"
            }
            assertNull(speculative)
            finish.complete(Unit)
            assertEquals("foreground-model", active.await())
        }
    }

    @Test
    fun gateReleasesLockAfterForegroundFailure() = runBlocking {
        withTimeout(6_000L) {
            runCatching {
                PlaybackModelRequestGate.foreground<String> {
                    throw IllegalStateException("synthetic transport failure")
                }
            }
            assertEquals("recovered", PlaybackModelRequestGate.foreground {
                "recovered"
            })
        }
    }
}
