package com.hongguo.rewrite

import android.app.PendingIntent
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

        /** In-process UI attachment. Keep the player owned by the service. */
        val playerFlow = MutableStateFlow<ExoPlayer?>(null)
        var active: BackgroundPlaybackService? = null
            private set

        fun stopActive() {
            // ViewModel methods and session operations are dispatched on the main thread.
            active?.stopPlayback()
        }
    }

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
            setHandleAudioBecomingNoisy(true)
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
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
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
                engine.pause()
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
                onError?.invoke(PlaybackProblem.media(
                    error.errorCodeName, error.cause?.javaClass?.simpleName))
            }
        })
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
        drama: String,
        episode: Int,
        totalEpisodes: Int,
        cover: String,
        positionMs: Long,
        introMs: Long,
        endingMs: Long,
        autoRetry: Boolean,
        firstFrame: () -> Unit,
        finished: () -> Unit,
        failed: (String) -> Unit,
        checkpoint: (Long, Long) -> Unit,
        timerExpired: () -> Boolean,
    ) {
        onFirstFrame = firstFrame
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
        currentVid = vid
        didSeek = false
        didFinish = false
        retried = false
        outroDone = false
        tick = 0
        resumeAtMs = positionMs
        skipIntroMs = introMs
        retryAutomatically = autoRetry
        val metadata = MediaMetadata.Builder()
            .setTitle(drama)
            .setDisplayTitle("$drama · 第${episode}集")
            .setArtist("第${episode}集 / 共${totalEpisodes}集")
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
    }

    /** Same gestures as the on-screen pager; changes do not seek the current file. */
    fun configureNavigation(canPrevious: Boolean, canNext: Boolean, action: (Int) -> Unit) {
        previousAllowed = canPrevious
        nextAllowed = canNext
        onNavigate = action
    }

    /** Immediately detach the previous video while ViewModel fetches a new episode. */
    fun pauseForTransition() {
        if (!::engine.isInitialized || currentKey == null) return
        onCheckpoint?.invoke(
            engine.currentPosition.coerceAtLeast(0L),
            engine.duration.takeIf { it > 0L } ?: 0L)
        engine.pause()
        engine.stop()
        engine.clearMediaItems()
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
        onFinished = null
        onError = null
        onCheckpoint = null
        onTimer = null
        onNavigate = null
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::session.isInitialized) session else null

    override fun onDestroy() {
        main.removeCallbacks(checkpoint)
        playerFlow.value = null
        if (active === this) active = null
        remoteVideo?.close()
        remoteVideo = null
        if (::session.isInitialized) session.release()
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}
