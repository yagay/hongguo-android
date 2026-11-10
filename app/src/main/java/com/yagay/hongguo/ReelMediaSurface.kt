package com.yagay.hongguo

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Surface attachment and foreground-only playback progress controls.
 * Never creates, owns or releases ExoPlayer: BackgroundPlaybackService does.
 * Keeping this separate prevents unrelated player chrome recompositions from
 * mutating the media lifetime.
 */
/** Shared controls for the one active, currently visible reel only. */
internal class ReelPlaybackControls {
    var player by mutableStateOf<ExoPlayer?>(null)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var playWhenReady by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var currentMediaMatches by mutableStateOf(false)
    fun toggle() {
        player?.let { current ->
            if (current.isPlaying) current.pause()
            else {
                // Media3 play() alone doesn't restart an ENDED item. User
                // tapping the central play icon must always be able to replay.
                if (current.playbackState == androidx.media3.common.Player.STATE_ENDED)
                    current.seekTo(0)
                current.play()
            }
        }
    }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceIn(0L, durationMs.coerceAtLeast(0))) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun Media3Reel(
    speed: Float,
    fastForwardHeld: Boolean,
    fillVideo: Boolean,
    controls: ReelPlaybackControls,
    expectedVid: String,
    onSurfaceType: (String) -> Unit,
    onPlayerView: (PlayerView?) -> Unit = {},
) {
    // ViewModel configures media in the foreground service after each episode
    // fetch, even while the UI lifecycle is STOPPED. Compose only shows its
    // video surface and keeps local playback buttons in sync.
    val player by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()

    // The ExoPlayer belongs to MediaSessionService and is intentionally
    // reused for every episode. ReelPlaybackControls, however, is recreated
    // by remember(episodeIndex). Both effects MUST rebind when controls change,
    // even if the actual ExoPlayer reference stays identical. Otherwise a
    // previous episode's polling coroutine keeps updating the old controls
    // and the new bottom dock is stuck at 00:00 / --:--.
    DisposableEffect(player, controls) {
        controls.player = player
        onDispose {
            if (controls.player === player) controls.player = null
            // A detached PlayerView must not release the background player.
        }
    }
    // The two edge hold gestures temporarily override the configured speed.
    // Disposing the video surface (mini player, PiP, route change) must
    // never leave the service-owned player running at the temporary 2x rate.
    val currentBaseSpeed by rememberUpdatedState(speed)
    DisposableEffect(player) {
        onDispose { player?.setPlaybackSpeed(currentBaseSpeed) }
    }
    LaunchedEffect(player, speed, fastForwardHeld) {
        player?.setPlaybackSpeed(
            if (fastForwardHeld) HoldFastForwardPolicy.SPEED else speed
        )
    }
    LaunchedEffect(player, controls, expectedVid) {
        // Avoid showing the previous episode's time/duration while the service
        // is resolving or installing the new MediaItem (or after a rapid swipe).
        controls.positionMs = 0L
        controls.durationMs = 0L
        controls.isPlaying = false
        controls.playWhenReady = false
        controls.playbackState = Player.STATE_IDLE
        controls.currentMediaMatches = false
        while (isActive) {
            val sample = ReelClockPolicy.sample(
                expectedVid = expectedVid,
                actualMediaId = player?.currentMediaItem?.mediaId,
                positionMs = player?.currentPosition ?: 0L,
                durationMs = player?.duration ?: 0L,
                isPlaying = player?.isPlaying == true,
            )
            controls.player = if (sample.currentItem) player else null
            controls.positionMs = sample.positionMs
            controls.durationMs = sample.durationMs
            controls.isPlaying = sample.playing
            controls.currentMediaMatches = sample.currentItem
            controls.playWhenReady = sample.currentItem && player?.playWhenReady == true
            controls.playbackState = if (sample.currentItem)
                player?.playbackState ?: Player.STATE_IDLE else Player.STATE_IDLE
            delay(250)
        }
    }

    // Register the exact visible video bounds with the Activity so system
    // picture-in-picture scales the video, not the launcher/app icon.
    val pipActivity = LocalContext.current as? MainActivity
    var pipPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    val currentOnPlayerView by rememberUpdatedState(onPlayerView)
    DisposableEffect(pipPlayerView) {
        val view = pipPlayerView
        if (view != null) currentOnPlayerView(view)
        onDispose {
            if (view != null) currentOnPlayerView(null)
        }
    }
    DisposableEffect(pipActivity, pipPlayerView) {
        val view = pipPlayerView
        if (view != null) pipActivity?.registerVideoPipSourceView(view)
        onDispose {
            if (view != null) pipActivity?.unregisterVideoPipSourceView(view)
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                pipPlayerView = this
                this.player = player
                useController = false
                // Never expose the previous drama's retained last frame while
                // Media3 changes its MediaItem on a fast vertical swipe.
                setKeepContentOnPlayerReset(false)
                resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                onSurfaceType(when (videoSurfaceView) {
                    is SurfaceView -> "SURFACE_VIEW"
                    is TextureView -> "TEXTURE_VIEW"
                    else -> "OTHER_SURFACE"
                })
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize().testTag("native_player"),
        update = {
            if (it.player !== player) it.player = player
            it.resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
    )
}
