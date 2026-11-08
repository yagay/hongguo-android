package com.hongguo.rewrite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
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
        const val PREVIOUS = "com.hongguo.rewrite.PREVIOUS_EPISODE_OR_REEL"
        const val NEXT = "com.hongguo.rewrite.NEXT_EPISODE_OR_REEL"
        private const val NOTIFICATION_CHANNEL = "hongguo_playback_progress"
        private const val NOTIFICATION_ID = 8452
        private const val ACTION_PREVIOUS = "com.hongguo.rewrite.notification.PREVIOUS"
        private const val ACTION_TOGGLE = "com.hongguo.rewrite.notification.TOGGLE"
        private const val ACTION_NEXT = "com.hongguo.rewrite.notification.NEXT"

        /** In-process UI attachment. Keep the player owned by the service. */
        val playerFlow = MutableStateFlow<ExoPlayer?>(null)
        var active: BackgroundPlaybackService? = null
            private set

        fun stopActive() {
            // ViewModel methods and session operations are dispatched on the main thread.
            active?.stopPlayback()
        }
    }

    private val diagnostics by lazy {
        DiagnosticLog(java.io.File(filesDir, "diagnostics"),
            getSharedPreferences("web_rewrite_settings", MODE_PRIVATE)
                .getBoolean("log_enabled", true))
    }
    private var permissionLogged = false
    private lateinit var engine: ExoPlayer
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
    private var notificationTitle = ""
    private var notificationSubtitle = ""
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

    override fun onCreate() {
        super.onCreate()
        engine = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
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
                if (direction == 0 ||
                    (direction < 0 && !previousAllowed) ||
                    (direction > 0 && !nextAllowed)) {
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
                // The ViewModel pauses only after a real destination is chosen.
                // If the reel queue needs paging, keep the current drama playing
                // instead of leaving an idle notification at the queue boundary.
                diagnostics.record("BACKGROUND", if (direction < 0)
                    "MEDIA_PREVIOUS" else "MEDIA_NEXT")
                onNavigate?.invoke(direction)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
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
        engine.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishProgressNotification()
            }

            override fun onRenderedFirstFrame() { onFirstFrame?.invoke() }

            override fun onPlaybackStateChanged(state: Int) {
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
                    onFinished?.invoke()
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
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL, "后台播放进度", NotificationManager.IMPORTANCE_LOW).apply {
            description = "通知栏显示短剧剧名、集数、播放时间及上一集/下一集控制"
            setShowBadge(false)
        })
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
                if (!backgroundConfirmed && engine.isPlaying &&
                    engine.currentPosition >= 3_000L) {
                    backgroundConfirmed = true
                    onBackgroundProgress?.invoke()
                }
                if (++tick % 4 == 0) {
                    if (duration > 0L)
                        onCheckpoint?.invoke(engine.currentPosition.coerceAtLeast(0L), duration)
                    publishProgressNotification()
                }
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
        engine.stop()
        remoteVideo?.takeIf { it !== remote }?.close()
        remoteVideo = remote
        currentKey = key
        diagnostics.record("BACKGROUND", "SOURCE_CONFIGURED")
        currentVid = vid
        didSeek = false
        backgroundConfirmed = false
        didFinish = false
        retried = false
        outroDone = false
        tick = 0
        resumeAtMs = positionMs
        skipIntroMs = introMs
        retryAutomatically = autoRetry
        notificationTitle = BackgroundNavigationPolicy.title(drama, episode)
        notificationSubtitle = BackgroundNavigationPolicy.episodeLabel(episode, totalEpisodes)
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
        engine.prepare()
        engine.playWhenReady = true
        publishProgressNotification()
    }

    /** Same gestures as the on-screen pager; changes do not seek the current file. */
    fun configureNavigation(canPrevious: Boolean, canNext: Boolean, action: (Int) -> Unit) {
        previousAllowed = canPrevious
        nextAllowed = canNext
        onNavigate = action
        updateMediaButtons()
        publishProgressNotification()
    }

    /** Immediately detach the previous video while ViewModel fetches a new episode. */
    fun pauseForTransition() {
        if (!::engine.isInitialized || currentKey == null) return
        onCheckpoint?.invoke(
            engine.currentPosition.coerceAtLeast(0L),
            engine.duration.takeIf { it > 0L } ?: 0L)
        engine.pause()
        engine.stop()
        // Retain the paused media item/notification while the next encrypted
        // model is resolving; clearing the playlist could stop the foreground
        // service before the background skip completes.
        currentKey = null
        remoteVideo?.close()
        remoteVideo = null
    }

    fun stopPlayback() {
        if (::engine.isInitialized) {
            onCheckpoint?.invoke(
                engine.currentPosition.coerceAtLeast(0L),
                engine.duration.takeIf { it > 0L } ?: 0L)
            engine.stop()
            engine.clearMediaItems()
        }
        currentKey = null
        remoteVideo?.close()
        remoteVideo = null
        onFirstFrame = null
        onBackgroundProgress = null
        onFinished = null
        onError = null
        onCheckpoint = null
        onTimer = null
        onNavigate = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        diagnostics.record("BACKGROUND", "SERVICE_STOP_REQUESTED")
        stopSelf()
    }

    // Android 13+ uses the system media player in Quick Settings. This separate
    // ordinary progress notification is shown only when the user grants app
    // notifications, avoiding the false assumption that the media carousel
    // must also appear as a second notification in the drawer.
    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code,
            Intent(this, BackgroundPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun publishProgressNotification() {
        if (currentKey == null || notificationTitle.isEmpty()) {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            if (!permissionLogged) diagnostics.record("BACKGROUND", "NOTIFICATION_PERMISSION_DENIED")
            permissionLogged = true
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            return
        }
        permissionLogged = false
        val duration = engine.duration.takeIf { it > 0L } ?: 0L
        val position = engine.currentPosition.coerceAtLeast(0L)
        val minuteSeconds: (Long) -> String = { milliseconds ->
            val seconds = milliseconds.coerceAtLeast(0) / 1000
            "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        }
        val note = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(notificationTitle)
            .setContentText("$notificationSubtitle  ·  " +
                "${minuteSeconds(position)} / ${minuteSeconds(duration)}")
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true)
            .setOngoing(engine.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                if (duration > 0L) duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                if (duration > 0L) position.coerceIn(0L, duration)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
                duration <= 0L)
            .addAction(android.R.drawable.ic_media_previous, "上一集/上一部",
                actionIntent(ACTION_PREVIOUS, 1))
            .addAction(
                if (engine.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (engine.isPlaying) "暂停" else "播放",
                actionIntent(ACTION_TOGGLE, 2))
            .addAction(android.R.drawable.ic_media_next, "下一集/下一部",
                actionIntent(ACTION_NEXT, 3))
            .build()
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, note)
        } catch (_: SecurityException) {
            diagnostics.record("BACKGROUND", "NOTIFICATION_BLOCKED")
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val direction = when (intent?.action) {
            ACTION_PREVIOUS -> -1
            ACTION_NEXT -> 1
            else -> 0
        }
        if (direction != 0 && (if (direction < 0) previousAllowed else nextAllowed)) {
            onNavigate?.invoke(direction)
        } else if (intent?.action == ACTION_TOGGLE && ::engine.isInitialized) {
            if (engine.isPlaying) engine.pause() else engine.play()
        }
        publishProgressNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::session.isInitialized) session else null

    override fun onDestroy() {
        main.removeCallbacks(checkpoint)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        playerFlow.value = null
        if (active === this) active = null
        remoteVideo?.close()
        remoteVideo = null
        if (::session.isInitialized) session.release()
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
