package com.yagay.hongguo

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A single video surface moves WITH the entire playback screen. Do not use
 * AnimatedContent with two NativePlayer instances: both would attach a
 * PlayerView to the same Media3/ExoPlayer and produce a black frame.
 */
internal object ReelModeSlidePolicy {
    enum class Motion { NONE, ENTER_FROM_RIGHT, EXIT_TO_RIGHT }

    fun motion(previousFeed: Boolean?, newFeed: Boolean, fromReel: Boolean): Motion =
        when {
            previousFeed == null || previousFeed == newFeed || !fromReel -> Motion.NONE
            previousFeed && !newFeed -> Motion.ENTER_FROM_RIGHT
            else -> Motion.EXIT_TO_RIGHT
        }
}

internal class ReelModeSlideState {
    private val translation = Animatable(0f)
    private var previousFeed: Boolean? = null

    // A tap prepares the initial (fully right) frame BEFORE the ViewModel
    // changes mode, so Compose cannot briefly show the final position first.
    var entryPrepared by mutableStateOf(false)
        private set
    private var exitRunning = false
    private var entryCaptureRunning = false
    private var videoSource: View? = null
    var videoViewportHeightPx by mutableIntStateOf(0)
        private set
    private var capturedDramaId: String? = null
    var capturedVideoFrame by mutableStateOf<Bitmap?>(null)
        private set

    fun registerPlayerView(playerView: PlayerView?) {
        videoSource = playerView?.videoSurfaceView
    }

    fun recordVideoViewportHeight(heightPx: Int) {
        if (heightPx > 0 && videoViewportHeightPx != heightPx)
            videoViewportHeightPx = heightPx
    }

    fun videoFrameFor(dramaId: String): Bitmap? =
        capturedVideoFrame?.takeIf { capturedDramaId == dramaId }

    /**
     * Capture the currently visible video frame, never another ExoPlayer.
     * PixelCopy reads a SurfaceView; TextureView has its own bitmap API.
     * One scaled image is held only for the short page transition.
     */
    private suspend fun captureCurrentFrame(dramaId: String) {
        capturedVideoFrame = null
        capturedDramaId = dramaId
        val view = videoSource ?: return
        if (view.width <= 0 || view.height <= 0) return
        val scale = minOf(1f, 540f / view.width, 960f / view.height)
        val bitmap = Bitmap.createBitmap(
            (view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val copied = when (view) {
            is SurfaceView -> {
                if (!view.holder.surface.isValid) false
                else withTimeoutOrNull(250L) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        try {
                            PixelCopy.request(view, bitmap, { result ->
                                if (continuation.isActive)
                                    continuation.resume(result == PixelCopy.SUCCESS)
                            }, Handler(Looper.getMainLooper()))
                        } catch (_: Exception) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                } == true
            }
            is TextureView -> runCatching {
                if (!view.isAvailable) false
                else { view.getBitmap(bitmap); true }
            }.getOrDefault(false)
            else -> false
        }
        if (copied) capturedVideoFrame = bitmap
    }

    /** Prevent rapid repeated taps while the outgoing frame is captured. */
    suspend fun captureThenPrepareEntry(dramaId: String): Boolean {
        if (entryCaptureRunning || entryPrepared || exitRunning) return false
        entryCaptureRunning = true
        try {
            captureCurrentFrame(dramaId)
            prepareSeriesEntry()
            return true
        } finally {
            entryCaptureRunning = false
        }
    }

    fun cancelPreparedEntry() { entryPrepared = false }

    val fraction: Float get() = translation.value
    fun positionFor(feedMode: Boolean): Float =
        if (!feedMode && entryPrepared) 1f else fraction

    fun prepareSeriesEntry() {
        if (!exitRunning) entryPrepared = true
    }

    suspend fun observeMode(feedMode: Boolean, fromReel: Boolean) {
        val motion = ReelModeSlidePolicy.motion(previousFeed, feedMode, fromReel)
        previousFeed = feedMode
        when (motion) {
            ReelModeSlidePolicy.Motion.ENTER_FROM_RIGHT -> {
                translation.snapTo(1f)
                entryPrepared = false
                translation.animateTo(0f,
                    tween(PlaybackPageSlide.enterDurationMs, easing = FastOutSlowInEasing))
            }
            ReelModeSlidePolicy.Motion.EXIT_TO_RIGHT -> {
                // Explicit Back is deferred until after exitRightThen().
                // Other route changes must not animate the *incoming* feed
                // screen out of view after the navigation is already complete.
                if (!exitRunning) translation.snapTo(0f)
                entryPrepared = false
            }
            ReelModeSlidePolicy.Motion.NONE -> {
                if (previousFeed == feedMode && !exitRunning && !entryPrepared) {
                    translation.snapTo(0f)
                }
            }
        }
    }

    suspend fun exitRightThen(navigateBack: () -> Unit) {
        if (exitRunning) return
        exitRunning = true
        try {
            entryPrepared = false
            translation.snapTo(0f)
            translation.animateTo(1f,
                tween(PlaybackPageSlide.exitDurationMs, easing = FastOutSlowInEasing))
            // Avoid a second exit transition from the mode-change observer.
            previousFeed = true
            navigateBack()
        } finally {
            withContext(NonCancellable) { translation.snapTo(0f) }
            exitRunning = false
        }
    }
}

@Composable
internal fun ReelModeSlidePlayer(
    state: AppState,
    slide: ReelModeSlideState,
    modifier: Modifier = Modifier,
    backdrop: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val fromReel = state.navigation.origin == PlaybackOrigin.SWIPE_FEED
    LaunchedEffect(slide, state.feedMode, fromReel) {
        slide.observeMode(state.feedMode, fromReel)
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .testTag("reel_mode_slide_host")) {
        // Two stacked screens are visible for the transition, but only the
        // moving foreground contains a real Media3 PlayerView.
        val transitionVisible = slide.positionFor(state.feedMode) > 0f
        if (transitionVisible && fromReel) {
            Box(Modifier.fillMaxSize().testTag("reel_mode_feed_backdrop")) {
                backdrop()
            }
        }
        // Modifier.offset moves the AndroidView and its SurfaceView bounds as
        // part of the layout (rather than only translating Compose text).
        // Never detach, recreate, pause or seek the service-owned player.
        val widthPixels = constraints.maxWidth
        Box(Modifier.fillMaxSize()
            .offset {
                IntOffset(
                    (widthPixels * slide.positionFor(state.feedMode)).roundToInt(), 0
                )
            }
            .testTag("reel_mode_moving_player")) {
            content()
        }
    }
}

/**
 * Real outgoing-feed appearance behind the incoming series page.
 * A frozen video frame is rendered with the same feed UI. No second
 * video decoder, SurfaceView, or media item is created.
 */
@Composable
internal fun ReelModeFeedBackdrop(
    state: AppState,
    detail: DramaDetail,
    episode: Episode?,
    playback: ReelPlaybackControls,
    slide: ReelModeSlideState,
    model: DramaViewModel,
) {
    val frameHeight = with(LocalDensity.current) {
        slide.videoViewportHeightPx.takeIf { it > 0 }?.toDp() ?: 360.dp
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF232325))
        .testTag("reel_feed_frozen_screen")) {
        Box(Modifier.fillMaxWidth().height(frameHeight).background(Color.Black)) {
            val frame = slide.videoFrameFor(detail.drama.id)
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (detail.drama.cover.isNotBlank()) {
                AsyncImage(
                    model = model.cover(detail.drama.cover),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            ReelScreenshotOverlay(
                state = state.copy(feedMode = true),
                detail = detail,
                episode = episode,
                onPlaybackSettings = {},
                onDownload = {},
                onOpenAccountDrawer = {},
                onPictureInPicture = null,
                onBack = {},
                onDramaDetails = {},
                onFavorite = {},
                onLike = {},
                onComments = {},
                onDanmaku = {},
                onRetryDanmaku = {},
                onComic = {},
                onReal = {},
                onRecommend = {},
                onSearch = {},
            )
        }
        ReelBottomDock(
            detail = detail,
            episode = episode,
            playback = playback,
            onEpisodes = {},
        )
        Spacer(Modifier.weight(1f))
    }
}
