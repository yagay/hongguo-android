package com.hongguo.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Shared navigation motion: new screens slide in from the right and leave
 * to the right; changes *within* an already playing video use a restrained
 * header-only fade-through, never a full-screen horizontal text animation.
 * The Media3 SurfaceView remains mounted throughout.
 */
internal object PlaybackPageSlide {
    const val enterDurationMs = 320
    const val exitDurationMs = 280

    fun enter(): EnterTransition = slideInHorizontally(
        animationSpec = tween(enterDurationMs, easing = FastOutSlowInEasing),
        initialOffsetX = { it },
    )

    fun exit(): ExitTransition = slideOutHorizontally(
        animationSpec = tween(exitDurationMs, easing = FastOutSlowInEasing),
        targetOffsetX = { it },
    )

    // Switching modes keeps the exact same playing video and most controls.
    // Only the header changes: fade the old row, then gently reveal the new
    // one with a very short vertical offset. No text flies across the video.
    const val chromeEnterDurationMs = 230
    const val chromeExitDurationMs = 105
    const val chromeEnterDelayMs = 30

    fun chromeChange(enteringSeries: Boolean, shiftPx: Int): ContentTransform {
        val direction = if (enteringSeries) 1 else -1
        val incoming = fadeIn(
            animationSpec = tween(
                durationMillis = chromeEnterDurationMs,
                delayMillis = chromeEnterDelayMs,
                easing = FastOutSlowInEasing,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = chromeEnterDurationMs,
                delayMillis = chromeEnterDelayMs,
                easing = FastOutSlowInEasing,
            ),
            initialOffsetY = { direction * shiftPx.coerceIn(0, 64) },
        )
        return incoming togetherWith fadeOut(
            animationSpec = tween(chromeExitDurationMs),
        )
    }
}

@Composable
internal fun RightSlidePage(
    visible: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize().testTag(tag),
        enter = PlaybackPageSlide.enter(),
        exit = PlaybackPageSlide.exit(),
    ) {
        content()
    }
}
