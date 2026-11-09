package com.yagay.hongguo

import android.media.audiofx.LoudnessEnhancer

/**
 * Optional Android audio-session effect, never global stream volume.
 * Devices may reject the effect (e.g. some passthrough or OEM pipelines),
 * so playback must continue unchanged on failure.
 */
internal object AudioBoostPolicy {
    val supportedDb = setOf(0, 3, 6)
    fun valid(db: Int): Boolean = db in supportedDb
    fun targetGainMb(db: Int): Int = if (valid(db)) db * 100 else 0
}

internal class PlaybackAudioBoost(private val log: (String) -> Unit) {
    private var effect: LoudnessEnhancer? = null
    private var attachedSessionId = -1
    var gainDb: Int = 0
        private set

    fun configure(db: Int, audioSessionId: Int) {
        if (!AudioBoostPolicy.valid(db)) return
        val previousDb = gainDb
        gainDb = db
        if (db == 0 || audioSessionId <= 0) {
            clear()
            if (db == 0 && previousDb != 0) log("BOOST_OFF")
            return
        }
        if (effect != null && attachedSessionId == audioSessionId) {
            if (previousDb == db) return
            val ok = runCatching {
                effect?.setTargetGain(AudioBoostPolicy.targetGainMb(db))
            }.isSuccess
            if (ok) {
                log("BOOST_${db}DB")
                return
            }
            clear()
        }
        // A fresh audio session can be created when the audio sink switches.
        clear()
        try {
            val fresh = LoudnessEnhancer(audioSessionId)
            try {
                fresh.setTargetGain(AudioBoostPolicy.targetGainMb(db))
                val status = fresh.setEnabled(true)
                if (status != 0) {
                    fresh.release()
                    log("BOOST_ENABLE_UNAVAILABLE")
                    return
                }
            } catch (error: Exception) {
                fresh.release()
                throw error
            }
            effect = fresh
            attachedSessionId = audioSessionId
            log("BOOST_${db}DB")
        } catch (_: Exception) {
            // Never crash or interrupt an otherwise functional video.
            log("BOOST_UNAVAILABLE")
        }
    }

    fun clear() {
        val old = effect
        effect = null
        attachedSessionId = -1
        if (old != null) {
            runCatching { old.setEnabled(false) }
            runCatching { old.release() }
        }
    }
}
