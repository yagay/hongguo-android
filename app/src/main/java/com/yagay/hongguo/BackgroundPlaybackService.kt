package com.yagay.hongguo

import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioManager
import android.content.Intent
import android.net.Uri
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
    /** Reusable recently played native streams, ordered oldest to newest.
     * All accesses are on the service main thread. No active stream is cached.
     */
    private data class RecentStream(
        val video: RemoteVideo,
        val vid: String,
        val quality: Int,
        val savedAtMs: Long,
    )
    private val recentStreams = mutableListOf<RecentStream>()
    private val recentTtlMs = 15 * 60_000L
    private val maxRecentEntries = 6
    private val maxRecentPrefixBytes = 48L * 1024 * 1024
    private var currentQuality = 0
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

    override fun onCreate() {
        super.onCreate()
        requestedAudioBoostDb = getSharedPreferences(
            "web_rewrite_settings", MODE_PRIVATE
        ).getInt("playback_audio_boost_db", 0)
            .takeIf(AudioBoostPolicy::valid) ?: 0
        engine = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
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
                if (retryAutomatically && !retried && error.errorCode in 2000..2999) {
                    retried = true
                    val position = engine.currentPosition.coerceAtLeast(0L)
                    engine.prepare()
                    engine.seekTo(position)
                    engine.play()
                    return
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
        diagnostics.record("BACKGROUND", "SERVICE_CREATED")
        active = this
        playerFlow.value = engine
        main.postDelayed(checkpoint, 1000)
    }

    private val checkpoint = object : Runnable {
        override fun run() {
            pruneRecentStreams()
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

    private fun clearRecentVideo() {
        recentStreams.forEach { it.video.close() }
        recentStreams.clear()
    }

    private fun prefixBytes(video: RemoteVideo): Long =
        runCatching { video.cachedBytes(0L).toLong().coerceAtLeast(0L) }
            .getOrDefault(0L)

    private fun pruneRecentStreams() {
        val now = android.os.SystemClock.elapsedRealtime()
        val expired = recentStreams.filter { now - it.savedAtMs >= recentTtlMs }
        expired.forEach { old ->
            recentStreams.remove(old)
            old.video.close()
        }
        if (expired.isNotEmpty()) diagnostics.record("BACKGROUND", "RECENT_STREAM_EXPIRED")
        // Memory is bounded by both entry count and already cached prefix.
        // This is an estimate; the native core may hold non-prefix ranges.
        while (recentStreams.size > maxRecentEntries ||
            recentStreams.sumOf { prefixBytes(it.video) } > maxRecentPrefixBytes) {
            val evicted = recentStreams.removeAt(0)
            evicted.video.close()
            diagnostics.record("BACKGROUND", "RECENT_STREAM_EVICTED")
        }
    }

    private fun rememberRecentVideo(vid: String, quality: Int, video: RemoteVideo) {
        if (vid.isBlank()) {
            video.close()
            return
        }
        // Do not cache a second owner of the same handle.
        recentStreams.removeAll { old ->
            if (old.vid == vid && old.quality == quality) {
                if (old.video !== video) old.video.close()
                true
            } else false
        }
        recentStreams.add(RecentStream(video, vid, quality,
            android.os.SystemClock.elapsedRealtime()))
        pruneRecentStreams()
        diagnostics.record("BACKGROUND", "RECENT_STREAM_STORED")
    }

    /** Transfer cached ownership once. Entries remain usable for up to 15 minutes
     * unless pressure forces earlier eviction. Quality must match exactly.
     */
    fun takeRecentVideo(vid: String, quality: Int): RemoteVideo? {
        pruneRecentStreams()
        val index = recentStreams.indexOfLast {
            it.vid == vid && it.quality == quality
        }
        if (index < 0) {
            diagnostics.record("BACKGROUND", "RECENT_STREAM_MISS")
            return null
        }
        val cached = recentStreams.removeAt(index).video
        diagnostics.record("BACKGROUND", "RECENT_STREAM_REUSED")
        return cached
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
    ) {
        onFirstFrame = firstFrame
        onBackgroundProgress = backgroundProgress
        onFinished = finished
        onError = failed
        onCheckpoint = checkpoint
        onTimer = timerExpired
        outroMs = endingMs.coerceAtLeast(0)
        if (currentKey == key) return
        val previousRemote = remoteVideo
        val previousVid = currentVid
        val previousQuality = currentQuality
        // setMediaSource/setMediaItem already replace the previous timeline.
        // An explicit stop() first adds an unnecessary STATE_IDLE transition
        // and can blank the retained video surface during every episode switch.
        remoteVideo = remote
        currentQuality = qualityLimit
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
        val item = MediaItem.Builder().setUri(url).setMediaId(vid)
            .setMediaMetadata(metadata).build()
        if (remote != null) {
            val factory = DataSource.Factory { NativeVideoDataSource(remote) }
            engine.setMediaSource(
                ProgressiveMediaSource.Factory(factory).createMediaSource(item))
        } else {
            engine.setMediaItem(item)
        }
        // Set autoplay intent before preparation, not afterward: an ended
        // player or foreground-service handoff may otherwise remain paused.
        engine.playWhenReady = true
        engine.prepare()
        engine.play()
        if (previousRemote != null && previousRemote !== remote &&
            previousVid.isNotBlank()) {
            // Keep a bounded LRU of recently played native streams, without
            // creating a second decoder or re-requesting their media model.
            rememberRecentVideo(previousVid, previousQuality, previousRemote)
        } else if (previousRemote != null && previousRemote !== remote) {
            previousRemote.close()
        }
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
        clearRecentVideo()
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
        audioBoost.clear()
        playerFlow.value = null
        if (active === this) active = null
        remoteVideo?.close()
        remoteVideo = null
        clearRecentVideo()
        if (::session.isInitialized) session.release()
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
