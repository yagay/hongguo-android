package com.yagay.hongguo

import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioManager
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hongguotv.core.RemoteVideo
import com.yagay.hongguo.kxg.KxgPlaybackStreams
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns the one real Media3 player while the Activity is backgrounded/locked.
 *
 * The Compose video surface may detach; it never owns or releases this engine.
 * Android's MediaSession notification supplies the native timeline, elapsed /
 * total time, play/pause and lock-screen controls.
 */
@OptIn(UnstableApi::class)
class BackgroundPlaybackService : MediaSessionService() {
    companion object {
        const val PREVIOUS = "com.yagay.hongguo.PREVIOUS_EPISODE_OR_REEL"
        const val NEXT = "com.yagay.hongguo.NEXT_EPISODE_OR_REEL"

        /** In-process UI attachment. Keep the player owned by the service. */
        val playerFlow = MutableStateFlow<ExoPlayer?>(null)
        var active: BackgroundPlaybackService? = null
            private set

        fun stopActive(reason: String = "USER_CLOSE") {
            // ViewModel methods and session operations are dispatched on the main thread.
            active?.stopPlayback(reason)
        }
    }

    private val diagnostics by lazy {
        DiagnosticLog(java.io.File(filesDir, "diagnostics"),
            getSharedPreferences("web_rewrite_settings", MODE_PRIVATE)
                .getBoolean("log_enabled", true))
    }
    private lateinit var engine: ExoPlayer
    private val audioBoost = PlaybackAudioBoost { code ->
        diagnostics.record("AUDIO_BOOST", code)
    }
    private var currentAudioSessionId = -1
    private var requestedAudioBoostDb = 0

    /** Service-owned effect remains valid when the Compose player view detaches. */
    fun setAudioBoostDb(db: Int) {
        if (!AudioBoostPolicy.valid(db)) return
        requestedAudioBoostDb = db
        audioBoost.configure(db, currentAudioSessionId)
    }

    private fun recordSystemMediaVolume() {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        diagnostics.record("AUDIO_LEVEL", "MEDIA_${current}_OF_${maximum}")
        val media3 = if (::engine.isInitialized) engine.volume else 1f
        diagnostics.record("AUDIO_LEVEL", "PLAYER_${(media3 * 100f).toInt()}PCT")
    }

    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val previousCommand = SessionCommand(PREVIOUS, Bundle.EMPTY)
    private val nextCommand = SessionCommand(NEXT, Bundle.EMPTY)
    private var remoteVideo: RemoteVideo? = null
    private var currentKey: String? = null
    private var currentVid: String = ""
    private var previousAllowed = false
    private var nextAllowed = false
    private var onNavigate: ((Int) -> Unit)? = null
    private var onFirstFrame: (() -> Unit)? = null
    private var onBackgroundProgress: (() -> Unit)? = null
    private var backgroundConfirmed = false
    private var backgroundProgressBase = -1L
    private var onFinished: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onCheckpoint: ((Long, Long) -> Unit)? = null
    private var onTimer: (() -> Boolean)? = null
    private var outroMs = 0L
    private var retryAutomatically = false
    private var didSeek = false
    private var didFinish = false
    private var retried = false
    private var outroDone = false
    private var resumeAtMs = 0L
    private var skipIntroMs = 0L
    private var tick = 0
    private var autoStartPending = false
    private var startRecoveryTried = false
    private var lastObservedPlayWhenReady: Boolean? = null
    private var previousPlaybackState: Int = Player.STATE_IDLE
    private var readySinceMs = 0L
    private var lastProgressMs = 0L
    private var lastProgressObservedAtMs = 0L
    private var stalledRecoveryDone = false
    private var lastForegroundRequested: Boolean? = null
    private var lastForegroundActive: Boolean? = null

    // The MediaSessionService remains alive when the Activity is backgrounded,
    // so network handoffs must be observed here, not in a Compose lifecycle.
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkValidated = false
    private var observedDefaultNetwork: Network? = null
    private var networkCallbackRegistered = false
    private var awaitingNetworkResume = false
    private var networkRefreshIssued = false
    private var networkGeneration = 0L
    private var lastGoodPositionMs = 0L
    private var currentSourceIsOffline = false
    private var onNetworkRecovered: ((Long) -> Unit)? = null
    private var networkRecoveryTask: Runnable? = null
    private var networkModelAttempts = 0
    /** The user wanted this media to play before connectivity was lost. */
    private var networkRecoveryPlayRequested = false

    /** Clear only this episode's automatic recovery, never the Media3 item. */
    private fun cancelNetworkRecovery() {
        networkGeneration++
        cancelNetworkResumeTask()
        awaitingNetworkResume = false
        networkRefreshIssued = false
        networkModelAttempts = 0
        networkRecoveryPlayRequested = false
    }

    /** A manual pause/episode change must beat an in-flight reconnect request. */
    fun cancelNetworkRecoveryForManualAction() {
        cancelNetworkRecovery()
    }

    /** Called by ViewModel before publishing a refreshed signed stream. */
    fun isNetworkRecoveryCurrent(vid: String): Boolean =
        currentKey != null && currentVid == vid &&
            networkModelAttempts > 0 && networkRefreshIssued &&
            networkRecoveryPlayRequested && retryAutomatically

    /** The model request failed, or the refreshed Media3 item failed to load. */
    fun networkRecoveryAttemptFailed(vid: String, retryable: Boolean) {
        if (currentVid != vid || currentKey == null ||
            networkModelAttempts == 0 || !networkRecoveryPlayRequested) return
        // Stale model failures after another request started cannot schedule
        // additional work; the callback always runs on our main thread.
        networkRefreshIssued = false
        if (!retryable || !retryAutomatically) {
            diagnostics.record("NETWORK_RESUME", "NON_RETRYABLE_FAILURE")
            cancelNetworkRecovery()
            return
        }
        if (!NetworkPlaybackRecoveryPolicy.canAttempt(networkModelAttempts + 1)) {
            diagnostics.record("NETWORK_RESUME", "MAX_RETRIES_EXHAUSTED")
            cancelNetworkRecovery()
            return
        }
        awaitingNetworkResume = true
        diagnostics.record("NETWORK_RESUME", "ATTEMPT_FAILED_RETRY_SCHEDULED")
        if (networkValidated) scheduleNetworkResume()
        else diagnostics.record("NETWORK_RESUME", "RETRY_WAITING_FOR_NETWORK")
    }

    fun setPlaybackAutoRetry(enabled: Boolean) {
        retryAutomatically = enabled
        if (!enabled) cancelNetworkRecovery()
    }

    private fun isNetworkValidated(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { refreshNetworkState() }
        }

        override fun onLost(network: Network) {
            main.post { refreshNetworkState() }
        }

        override fun onCapabilitiesChanged(
            network: Network, capabilities: NetworkCapabilities,
        ) {
            main.post { refreshNetworkState() }
        }
    }

    private fun cancelNetworkResumeTask() {
        networkRecoveryTask?.let { main.removeCallbacks(it) }
        networkRecoveryTask = null
    }

    private fun armNetworkResumeIfNeeded() {
        if (!::engine.isInitialized || currentKey == null) return
        if (NetworkPlaybackRecoveryPolicy.shouldArm(
                retryAutomatically, true,
                networkRecoveryPlayRequested || engine.playWhenReady || autoStartPending,
                engine.playbackState == Player.STATE_ENDED, currentSourceIsOffline)) {
            networkRecoveryPlayRequested = true
            awaitingNetworkResume = true
            lastGoodPositionMs = NetworkPlaybackRecoveryPolicy.resumeMs(
                engine.currentPosition, lastGoodPositionMs)
            diagnostics.record("NETWORK_RESUME", "WAITING_FOR_VALIDATED_NETWORK")
        }
    }

    private fun refreshNetworkState() {
        val available = isNetworkValidated()
        val newDefault = runCatching { connectivity.activeNetwork }.getOrNull()
        val changedNetwork = newDefault != observedDefaultNetwork
        val wasValidated = networkValidated
        if (available == wasValidated && !changedNetwork) return
        networkValidated = available
        observedDefaultNetwork = newDefault
        diagnostics.record("NETWORK_RESUME", when {
            !available -> "NETWORK_LOST"
            !wasValidated -> "NETWORK_VALIDATED"
            else -> "DEFAULT_NETWORK_CHANGED"
        })
        if (!available) {
            // A fresh network epoch receives its own bounded retry budget.
            // Let the already-playing source remain untouched.
            networkModelAttempts = 0
            networkRefreshIssued = false
            networkRecoveryPlayRequested = false
            cancelNetworkResumeTask()
            armNetworkResumeIfNeeded()
        } else {
            // Wi-Fi -> mobile data can swap directly between two validated
            // networks without exposing an intermediate offline state.
            if (changedNetwork && !::engine.isInitialized) return
            if (changedNetwork && ::engine.isInitialized &&
                engine.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_IDLE) &&
                !engine.isPlaying) {
                networkModelAttempts = 0
                networkRefreshIssued = false
                armNetworkResumeIfNeeded()
            }
            if (awaitingNetworkResume) {
                networkRefreshIssued = false
                // Let Media3 resume by itself before requesting a new model.
                scheduleNetworkResume()
            }
        }
    }

    private fun scheduleNetworkResume(
        delayMs: Long =
            NetworkPlaybackRecoveryPolicy.retryDelayMs(networkModelAttempts + 1),
    ) {
        if (!awaitingNetworkResume || !networkValidated || networkRefreshIssued) return
        if (!NetworkPlaybackRecoveryPolicy.canAttempt(networkModelAttempts + 1)) {
            diagnostics.record("NETWORK_RESUME", "MAX_RETRIES_EXHAUSTED")
            cancelNetworkRecovery()
            return
        }
        cancelNetworkResumeTask()
        val generation = networkGeneration
        val work = Runnable {
            networkRecoveryTask = null
            if (generation != networkGeneration || !::engine.isInitialized ||
                currentKey == null || currentSourceIsOffline) return@Runnable
            if (engine.isPlaying) {
                cancelNetworkRecovery()
                diagnostics.record("NETWORK_RESUME", "MEDIA3_RESUMED_WITHOUT_RELOAD")
                return@Runnable
            }
            if (!NetworkPlaybackRecoveryPolicy.shouldRefresh(
                    awaitingNetworkResume, networkValidated,
                    networkRecoveryPlayRequested &&
                        (engine.playWhenReady || autoStartPending),
                    engine.isPlaying,
                    engine.playbackState == Player.STATE_ENDED)) {
                diagnostics.record("NETWORK_RESUME", "RELOAD_SKIPPED_NOT_PLAYING")
                return@Runnable
            }
            if (!retryAutomatically || networkRefreshIssued) return@Runnable
            val callback = onNetworkRecovered ?: return@Runnable
            networkRefreshIssued = true
            awaitingNetworkResume = false
            networkModelAttempts++
            val position = NetworkPlaybackRecoveryPolicy.resumeMs(
                engine.currentPosition, lastGoodPositionMs)
            diagnostics.record("NETWORK_RESUME",
                "REFRESH_SIGNED_STREAM_ATTEMPT_" + networkModelAttempts)
            // The ViewModel resolves the current episode again with a fresh
            // playable URI + CENC model, without creating a second player.
            // A stale delayed callback cannot reopen a newer video.
            try {
                callback(position)
            } catch (_: Exception) {
                diagnostics.record("NETWORK_RESUME", "REFRESH_CALLBACK_FAILED")
                networkRecoveryAttemptFailed(currentVid, true)
            }
        }
        networkRecoveryTask = work
        main.postDelayed(work, delayMs)
    }

    override fun onCreate() {
        super.onCreate()
        requestedAudioBoostDb = getSharedPreferences(
            "web_rewrite_settings", MODE_PRIVATE
        ).getInt("playback_audio_boost_db", 0)
            .takeIf(AudioBoostPolicy::valid) ?: 0
        // Follow Kaixinguo's direct ExoPlayer -> CencDataSource pipeline.
        // Keep the ONE service-owned player for notifications and PiP.
        engine = ExoPlayer.Builder(this)
            .setRenderersFactory(
                DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .build().apply {
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val activityIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession, controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon().add(previousCommand).add(nextCommand).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val direction = when (customCommand.customAction) {
                    PREVIOUS -> -1
                    NEXT -> 1
                    else -> 0
                }
                val success = navigateFromPip(direction)
                return Futures.immediateFuture(SessionResult(
                    if (success) SessionResult.RESULT_SUCCESS
                    else SessionResult.RESULT_ERROR_NOT_SUPPORTED,
                ))
            }
        }
        session = MediaSession.Builder(this, engine)
            .setCallback(callback)
            .setSessionActivity(activityIntent)
            .setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                    .setDisplayName("上一集/上一部")
                    .setSessionCommand(previousCommand).build(),
                CommandButton.Builder(CommandButton.ICON_NEXT)
                    .setDisplayName("下一集/下一部")
                    .setSessionCommand(nextCommand).build(),
            ))
            .build()
        // The playback engine is created directly by this started service,
        // rather than by a client MediaController connection. Without an
        // explicit addSession(), MediaSessionService never tracks this session,
        // so Media3 can't automatically promote its playback to a foreground
        // media service. The Android media carousel alone is not proof that
        // the service itself is in the foreground.
        addSession(session)
        diagnostics.record("BACKGROUND", "MEDIA_SESSION_REGISTERED")
        recordSystemMediaVolume()
        engine.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                diagnostics.record("VIDEO_RENDER",
                    "DECODER_" + DiagnosticLog.token(decoderName))
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                diagnostics.record("AUDIO_RENDER",
                    "DECODER_" + DiagnosticLog.token(decoderName))
            }

            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int,
            ) {
                currentAudioSessionId = audioSessionId
                diagnostics.record("AUDIO_RENDER",
                    if (audioSessionId > 0) "SESSION_AVAILABLE" else "SESSION_UNSET")
                setAudioBoostDb(requestedAudioBoostDb)
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                diagnostics.record("VIDEO_FORMAT",
                    "MIME_" + DiagnosticLog.token(format.sampleMimeType ?: "UNKNOWN"))
                diagnostics.record("VIDEO_FORMAT",
                    "SIZE_${format.width.coerceAtLeast(0)}X${format.height.coerceAtLeast(0)}")
                val color = format.colorInfo
                diagnostics.record("VIDEO_COLOR",
                    "SPACE_${color?.colorSpace ?: -1}")
                diagnostics.record("VIDEO_COLOR",
                    "RANGE_${color?.colorRange ?: -1}")
                diagnostics.record("VIDEO_COLOR",
                    "TRANSFER_${color?.colorTransfer ?: -1}")
                diagnostics.record("VIDEO_COLOR",
                    "LUMA_BITS_${color?.lumaBitdepth ?: -1}")
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                diagnostics.record("AUDIO_FORMAT",
                    "MIME_" + DiagnosticLog.token(format.sampleMimeType ?: "UNKNOWN"))
                diagnostics.record("AUDIO_FORMAT",
                    "CHANNELS_${format.channelCount.coerceAtLeast(0)}")
                diagnostics.record("AUDIO_FORMAT",
                    "SAMPLE_RATE_${format.sampleRate.coerceAtLeast(0)}")
                recordSystemMediaVolume()
            }

            override fun onVolumeChanged(
                eventTime: AnalyticsListener.EventTime,
                volume: Float,
            ) {
                diagnostics.record("AUDIO_LEVEL",
                    "PLAYER_${(volume * 100f).toInt().coerceIn(0, 100)}PCT")
            }
        })
        engine.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                lastObservedPlayWhenReady = playWhenReady
                if (!playWhenReady &&
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                    // Respect an explicit user pause (or a manual episode
                    // transition) even if network connectivity changes.
                    autoStartPending = false
                    cancelNetworkRecovery()
                } else if (playWhenReady && !networkValidated) {
                    armNetworkResumeIfNeeded()
                }
                diagnostics.record("BACKGROUND", when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                        if (playWhenReady) "READY_USER_PLAY" else "READY_USER_PAUSE"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                        "READY_AUDIO_FOCUS_LOST"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ->
                        "READY_AUDIO_NOISY"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE ->
                        "READY_REMOTE"
                    else -> "READY_OTHER"
                })
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    autoStartPending = false
                    if (networkModelAttempts > 0)
                        diagnostics.record("NETWORK_RESUME", "PLAYBACK_RESTORED")
                    cancelNetworkRecovery()
                    diagnostics.record("BACKGROUND", "PLAYING")
                }
            }

            override fun onRenderedFirstFrame() { onFirstFrame?.invoke() }

            // Log declared video transfer characteristics to distinguish a
            // dark SDR master from possible device HDR-to-SDR tone mapping.
            // Only coarse labels are recorded, never DRM/data-source metadata.
            override fun onTracksChanged(tracks: Tracks) {
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_VIDEO) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val transfer = group.getTrackFormat(i).colorInfo?.colorTransfer
                        diagnostics.record("VIDEO_COLOR", when (transfer) {
                            C.COLOR_TRANSFER_ST2084 -> "HDR_PQ"
                            C.COLOR_TRANSFER_HLG -> "HDR_HLG"
                            C.COLOR_TRANSFER_SDR -> "SDR"
                            else -> "UNKNOWN_TRANSFER"
                        })
                        return
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (previousPlaybackState != state) {
                    diagnostics.record("BACKGROUND", when (state) {
                        Player.STATE_IDLE -> "STATE_IDLE"
                        Player.STATE_BUFFERING -> "STATE_BUFFERING"
                        Player.STATE_READY -> "STATE_READY"
                        Player.STATE_ENDED -> "STATE_ENDED"
                        else -> "STATE_OTHER"
                    })
                    previousPlaybackState = state
                }
                if (state == Player.STATE_READY) {
                    readySinceMs = android.os.SystemClock.elapsedRealtime()
                    lastProgressMs = engine.currentPosition.coerceAtLeast(0L)
                    lastProgressObservedAtMs = readySinceMs
                }
                if (state == Player.STATE_READY && autoStartPending &&
                    !engine.playWhenReady && !startRecoveryTried) {
                    startRecoveryTried = true
                    diagnostics.record("BACKGROUND", "AUTO_START_RECOVERY")
                    engine.play()
                }
                if (state == Player.STATE_READY && !didSeek) {
                    didSeek = true
                    val duration = engine.duration.takeIf { it > 0 } ?: 0L
                    val target = ResumePolicy.resumable(resumeAtMs, duration)
                    val intro = if (duration > skipIntroMs + 5_000L) skipIntroMs else 0L
                    val position = maxOf(target, intro)
                    if (position > 0L) engine.seekTo(position)
                }
                if (state == Player.STATE_ENDED && !didFinish) {
                    diagnostics.record("BACKGROUND", "MEDIA_ENDED")
                    didFinish = true
                    // Avoid reentrant ExoPlayer.stop()/setMediaSource() from a
                    // listener dispatch. The next episode is started on the
                    // next main-loop turn; ignore the callback if the source
                    // was replaced in the meantime.
                    val endingKey = currentKey
                    val finished = onFinished
                    main.post {
                        if (endingKey != null && currentKey == endingKey)
                            finished?.invoke()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val transientIo = NetworkPlaybackRecoveryPolicy
                    .isTransientMediaIo(error.errorCode)
                if (transientIo && retryAutomatically && !currentSourceIsOffline) {
                    lastGoodPositionMs = NetworkPlaybackRecoveryPolicy.resumeMs(
                        engine.currentPosition, lastGoodPositionMs)
                    if (!networkValidated) {
                        armNetworkResumeIfNeeded()
                        diagnostics.record("NETWORK_RESUME", "OFFLINE_ERROR_DEFERRED")
                    } else if (!retried) {
                        // One immediate retry for brief CDN interruptions.
                        // Never spin while Android reports no validated network.
                        retried = true
                        diagnostics.record("NETWORK_RESUME", "MEDIA3_IO_RETRY_ONCE")
                        val position = NetworkPlaybackRecoveryPolicy.resumeMs(
                            engine.currentPosition, lastGoodPositionMs)
                        engine.prepare()
                        engine.seekTo(position)
                        engine.play()
                        return
                    } else {
                        // Retry a fresh playback model even if no further
                        // network callback arrives on the same connection.
                        armNetworkResumeIfNeeded()
                        if (networkModelAttempts > 0) {
                            if (!networkRefreshIssued)
                                networkRecoveryAttemptFailed(currentVid, true)
                        } else if (networkValidated) {
                            scheduleNetworkResume()
                        }
                    }
                } else if (networkModelAttempts > 0) {
                    // Do not retry a codec error, refused stream or other
                    // permanent Media3 failure merely because it followed
                    // a temporary network outage.
                    networkRecoveryAttemptFailed(currentVid, retryable = false)
                }
                diagnostics.record("BACKGROUND", "MEDIA_ERROR")
                onError?.invoke(PlaybackProblem.media(
                    error.errorCodeName, error.cause?.javaClass?.simpleName))
            }
        })
        // Migration cleanup for the old duplicate progress notification.
        // The active foreground-service notification is owned by Media3.
        getSystemService(NotificationManager::class.java)
            ?.deleteNotificationChannel("hongguo_playback_progress")
        networkValidated = isNetworkValidated()
        observedDefaultNetwork = runCatching { connectivity.activeNetwork }.getOrNull()
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            diagnostics.record("NETWORK_RESUME", "CALLBACK_REGISTERED")
        } catch (_: Exception) {
            diagnostics.record("NETWORK_RESUME", "CALLBACK_UNAVAILABLE")
        }
        diagnostics.record("BACKGROUND", "SERVICE_CREATED")
        active = this
        playerFlow.value = engine
        main.postDelayed(checkpoint, 1000)
    }

    private val checkpoint = object : Runnable {
        override fun run() {
            if (::engine.isInitialized && currentKey != null) {
                val duration = engine.duration.takeIf { it > 0 } ?: 0L
                if (engine.isPlaying && onTimer?.invoke() == true) engine.pause()
                if (engine.isPlaying && !outroDone && outroMs > 0L &&
                    duration > outroMs + 5_000L &&
                    engine.currentPosition >= duration - outroMs) {
                    outroDone = true
                    engine.seekTo(duration)
                }
                if (!backgroundConfirmed && engine.isPlaying) {
                    val position = engine.currentPosition.coerceAtLeast(0L)
                    if (backgroundProgressBase < 0L) {
                        backgroundProgressBase = position
                    } else if (position >= backgroundProgressBase + 3_000L) {
                        // Verify three seconds of actual forward progress, not
                        // merely a resume checkpoint already beyond 00:03.
                        backgroundConfirmed = true
                        onBackgroundProgress?.invoke()
                    }
                }
                if (engine.isPlaying) {
                    lastGoodPositionMs = engine.currentPosition.coerceAtLeast(0L)
                }
                if (engine.playbackState == Player.STATE_READY && engine.isPlaying) {
                    val position = engine.currentPosition.coerceAtLeast(0L)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (position > lastProgressMs + 100L) {
                        lastProgressMs = position
                        lastProgressObservedAtMs = now
                    } else if (!stalledRecoveryDone && !didFinish &&
                        lastProgressObservedAtMs != 0L &&
                        now - lastProgressObservedAtMs >= 12_000L) {
                        // One bounded resume attempt. If this does not resume,
                        // retain the media and export a diagnostic event rather
                        // than entering a prepare/seek loop.
                        stalledRecoveryDone = true
                        diagnostics.record("BACKGROUND", "PLAYING_STALLED")
                        engine.play()
                    }
                }
                if (++tick % 4 == 0 && duration > 0L)
                    onCheckpoint?.invoke(engine.currentPosition.coerceAtLeast(0L), duration)
            }
            main.postDelayed(this, 1000)
        }
    }

    /** Called on main by the UI after metadata + encrypted media are ready. */
    fun configure(
        key: String,
        vid: String,
        url: String,
        remote: RemoteVideo?,
        qualityLimit: Int,
        drama: String,
        episode: Int,
        totalEpisodes: Int,
        cover: String,
        positionMs: Long,
        introMs: Long,
        endingMs: Long,
        autoRetry: Boolean,
        firstFrame: () -> Unit,
        backgroundProgress: () -> Unit,
        finished: () -> Unit,
        failed: (String) -> Unit,
        checkpoint: (Long, Long) -> Unit,
        timerExpired: () -> Boolean,
        networkRecovered: (Long) -> Unit,
    ) {
        onFirstFrame = firstFrame
        onBackgroundProgress = backgroundProgress
        onFinished = finished
        onError = failed
        onCheckpoint = checkpoint
        onTimer = timerExpired
        onNetworkRecovered = networkRecovered
        outroMs = endingMs.coerceAtLeast(0)
        if (currentKey == key) return
        // The requested video is the same but the signed CENC model is new.
        // Preserve the retry budget until Media3 actually starts playing.
        val continuingNetworkRecovery = currentVid == vid &&
            networkModelAttempts > 0 && networkRefreshIssued &&
            networkRecoveryPlayRequested
        networkGeneration++
        networkRefreshIssued = false
        awaitingNetworkResume = continuingNetworkRecovery
        cancelNetworkResumeTask()
        if (!continuingNetworkRecovery) {
            networkModelAttempts = 0
            networkRecoveryPlayRequested = false
        }
        currentSourceIsOffline = url.startsWith("content://") ||
            url.startsWith("file://") || url.startsWith("android.resource://")
        lastGoodPositionMs = positionMs.coerceAtLeast(0L)
        val previousRemote = remoteVideo
        // setMediaSource/setMediaItem already replace the previous timeline.
        // An explicit stop() first adds an unnecessary STATE_IDLE transition
        // and can blank the retained video surface during every episode switch.
        remoteVideo = remote
        currentKey = key
        diagnostics.record("BACKGROUND", "SOURCE_CONFIGURED")
        currentVid = vid
        didSeek = false
        backgroundConfirmed = false
        backgroundProgressBase = -1L
        didFinish = false
        retried = false
        outroDone = false
        tick = 0
        autoStartPending = true
        startRecoveryTried = false
        stalledRecoveryDone = false
        readySinceMs = 0L
        lastProgressMs = 0L
        lastProgressObservedAtMs = 0L
        resumeAtMs = positionMs
        skipIntroMs = introMs
        retryAutomatically = autoRetry
        val metadata = MediaMetadata.Builder()
            .setTitle(BackgroundNavigationPolicy.title(drama, episode))
            .setDisplayTitle(BackgroundNavigationPolicy.title(drama, episode))
            .setArtist(BackgroundNavigationPolicy.episodeLabel(episode, totalEpisodes))
            .setAlbumTitle(drama)
            .apply {
                if (cover.startsWith("https://")) setArtworkUri(Uri.parse(cover))
            }.build()
        val item = MediaItem.Builder()
            .setUri(url).setMediaId(vid)
            .setMediaMetadata(metadata).build()
        if (remote != null) {
            // Optional legacy backend: direct decrypted range reads without
            // either old SimpleCache or a second Media3 preload manager.
            engine.setMediaSource(ProgressiveMediaSource.Factory(
                DataSource.Factory { NativeVideoDataSource(remote) }
            ).createMediaSource(item))
            diagnostics.record("PLAYBACK", "NATIVE_DIRECT_RANGE_SOURCE")
        } else if (url.startsWith("kaixinguo-stream://")) {
            // Exact Kaixinguo approach: registry -> CENC -> Media3.
            // No persistent disk cache, cached MediaSource or duplicate player.
            engine.setMediaSource(ProgressiveMediaSource.Factory(
                KxgPlaybackStreams.factory(this)).createMediaSource(item))
            diagnostics.record("PLAYBACK", "KXG_DIRECT_CENC_SOURCE")
        } else {
            engine.setMediaItem(item)
        }
        // Set autoplay intent before preparation, not afterward: an ended
        // player or foreground-service handoff may otherwise remain paused.
        engine.playWhenReady = true
        engine.prepare()
        engine.play()
        if (continuingNetworkRecovery && networkValidated) {
            // Some CDN streams stay BUFFERING indefinitely instead of
            // dispatching an error. Treat that as a failed attempt too.
            scheduleNetworkResume(delayMs = 15_000L)
        }
        if (previousRemote != null && previousRemote !== remote)
            previousRemote.close()
        diagnostics.record("BACKGROUND", "NEXT_SOURCE_PLAY_REQUESTED")
    }

    /**
     * Explicit PiP RemoteAction and media notification custom commands share
     * exactly the same service->ViewModel route.
     */
    fun navigateFromPip(direction: Int): Boolean {
        val action = onNavigate ?: return false
        if ((direction != -1 && direction != 1) ||
            (direction < 0 && !previousAllowed) ||
            (direction > 0 && !nextAllowed)) {
            diagnostics.record("BACKGROUND", "PIP_SKIP_NOT_AVAILABLE")
            return false
        }
        diagnostics.record("BACKGROUND",
            if (direction < 0) "PIP_MEDIA_PREVIOUS" else "PIP_MEDIA_NEXT")
        action(direction)
        return true
    }

    fun togglePlaybackFromPip(): Boolean {
        if (!::engine.isInitialized || currentKey == null) return false
        if (engine.playWhenReady) engine.pause() else engine.play()
        diagnostics.record("BACKGROUND",
            if (engine.playWhenReady) "PIP_MEDIA_PLAY" else "PIP_MEDIA_PAUSE")
        return true
    }

    /** Same gestures as the on-screen pager; changes do not seek the current file. */
    fun configureNavigation(canPrevious: Boolean, canNext: Boolean, action: (Int) -> Unit) {
        previousAllowed = canPrevious
        nextAllowed = canNext
        onNavigate = action
        updateMediaButtons()
    }

    /** Pause the old episode while the next encrypted source is resolving.
     * Keep the media item in the session so Android doesn't remove the
     * notification or stop the foreground service during a slow network fetch.
     * configure() disposes the old media atomically once the new source is ready.
     */
    /** The encrypted stream must remain open while the session is using it. */
    fun ownsVideo(video: RemoteVideo): Boolean = remoteVideo === video

    fun pauseForTransition() {
        if (!::engine.isInitialized || currentKey == null) return
        if (engine.playbackState != Player.STATE_ENDED) {
            onCheckpoint?.invoke(
                engine.currentPosition.coerceAtLeast(0L),
                engine.duration.takeIf { it > 0L } ?: 0L)
        }
        engine.pause()
        autoStartPending = false
        diagnostics.record("BACKGROUND", "TRANSITION_PENDING")
    }

    /**
     * Preserve the current Media3 item and its encrypted RemoteVideo while the
     * user reads this drama's details. Null means no reusable session; a
     * non-null Boolean remembers whether playback was actually requested.
     */
    fun pauseForDetails(vid: String): Boolean? {
        if (!::engine.isInitialized || currentKey == null ||
            currentVid != vid || engine.currentMediaItem?.mediaId != vid ||
            engine.playbackState == Player.STATE_IDLE ||
            engine.playbackState == Player.STATE_ENDED) return null
        val wasPlaying = engine.playWhenReady
        onCheckpoint?.invoke(
            engine.currentPosition.coerceAtLeast(0L),
            engine.duration.takeIf { it > 0L } ?: 0L)
        engine.pause()
        autoStartPending = false
        diagnostics.record("BACKGROUND", "DETAIL_PAUSED_RETAINED")
        return wasPlaying
    }

    /** Attach the returning UI to the same item without reprepare/reseek. */
    fun resumeFromDetails(vid: String, shouldPlay: Boolean): Boolean {
        if (!::engine.isInitialized || currentKey == null ||
            currentVid != vid || engine.currentMediaItem?.mediaId != vid ||
            engine.playbackState == Player.STATE_IDLE ||
            engine.playbackState == Player.STATE_ENDED) return false
        if (shouldPlay) engine.play()
        diagnostics.record("BACKGROUND",
            if (shouldPlay) "DETAIL_RESUMED_REUSED" else "DETAIL_RETURNED_PAUSED")
        return true
    }

    fun stopPlayback(reason: String = "USER_CLOSE") {
        if (::engine.isInitialized) {
            onCheckpoint?.invoke(
                engine.currentPosition.coerceAtLeast(0L),
                engine.duration.takeIf { it > 0L } ?: 0L)
            engine.stop()
            engine.clearMediaItems()
        }
        currentKey = null
        cancelNetworkRecovery()
        onNetworkRecovered = null
        remoteVideo?.close()
        remoteVideo = null
        onFirstFrame = null
        onBackgroundProgress = null
        onFinished = null
        onError = null
        onCheckpoint = null
        onTimer = null
        onNavigate = null
        audioBoost.clear()
        currentAudioSessionId = -1
        diagnostics.record("BACKGROUND", "STOP_" + DiagnosticLog.token(reason))
        stopSelf()
    }

    private fun updateMediaButtons() {
        if (!::session.isInitialized) return
        session.setMediaButtonPreferences(listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("上一集/上一部").setSessionCommand(previousCommand)
                .setEnabled(previousAllowed).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("下一集/下一部").setSessionCommand(nextCommand)
                .setEnabled(nextAllowed).setSlots(CommandButton.SLOT_FORWARD).build(),
        ))
    }

    /**
     * Record both Media3's request and the actual foreground state. A system
     * media card can appear even when this service was never promoted.
     */
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        if (lastForegroundRequested != startInForegroundRequired) {
            lastForegroundRequested = startInForegroundRequired
            diagnostics.record("BACKGROUND", if (startInForegroundRequired)
                "FOREGROUND_REQUESTED" else "FOREGROUND_NOT_REQUIRED")
        }
        super.onUpdateNotification(session, startInForegroundRequired)
        val ongoing = isPlaybackOngoing()
        if (lastForegroundActive != ongoing) {
            lastForegroundActive = ongoing
            diagnostics.record("BACKGROUND", if (ongoing)
                "FOREGROUND_ACTIVE" else "FOREGROUND_INACTIVE")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::session.isInitialized) session else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnostics.record("BACKGROUND", "TASK_REMOVED")
        // Media3's default behavior retains ongoing playback and stops
        // the service only when the session is not playing.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        diagnostics.record("BACKGROUND", if (::engine.isInitialized &&
            engine.isPlaying) "DESTROYED_WHILE_PLAYING" else "DESTROYED_NOT_PLAYING")
        diagnostics.record("BACKGROUND",
            if (isPlaybackOngoing()) "DESTROYED_FOREGROUND_ACTIVE"
            else "DESTROYED_FOREGROUND_INACTIVE")
        diagnostics.record("BACKGROUND",
            if (::session.isInitialized && isSessionAdded(session))
                "DESTROYED_SESSION_REGISTERED" else "DESTROYED_SESSION_MISSING")
        if (::engine.isInitialized && currentKey != null &&
            engine.playbackState != Player.STATE_ENDED) {
            onCheckpoint?.invoke(engine.currentPosition.coerceAtLeast(0L),
                engine.duration.takeIf { it > 0L } ?: 0L)
        }
        main.removeCallbacks(checkpoint)
        networkGeneration++
        cancelNetworkResumeTask()
        onNetworkRecovered = null
        if (networkCallbackRegistered) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        audioBoost.clear()
        playerFlow.value = null
        if (active === this) active = null
        remoteVideo?.close()
        remoteVideo = null
        if (::session.isInitialized) session.release()
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
