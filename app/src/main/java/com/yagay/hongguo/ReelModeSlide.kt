package com.yagay.hongguo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
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
    model: DramaViewModel,
    slide: ReelModeSlideState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fromReel = state.navigation.origin == PlaybackOrigin.SWIPE_FEED
    LaunchedEffect(slide, state.feedMode, fromReel) {
        slide.observeMode(state.feedMode, fromReel)
    }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .testTag("reel_mode_slide_host")) {
        // This is a still-artwork underlay, NOT a second Media3 video.
        // It prevents the area revealed by the moving player from flashing
        // black and preserves the recognizable feed context on Back.
        Box(Modifier.fillMaxSize().background(HongguoColors.playerBackground)
            .testTag("reel_mode_feed_backdrop")) {
            val drama = state.detail?.drama
            if (drama != null && drama.cover.isNotBlank()) {
                AsyncImage(
                    model = model.cover(drama.cover),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // No dummy feed navigation or menu here. The old implementation
            // exposed what looked like a second left menu during the slide,
            // even when its account drawer was closed.
            if (fromReel) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
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
