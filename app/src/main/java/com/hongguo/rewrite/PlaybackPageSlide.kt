package com.hongguo.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Common navigation motion: an opened page enters from the right and
 * leaves to the right. This animates Compose chrome, never the Media3
 * SurfaceView itself (which must stay attached to the service player).
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

    fun revealFeed(): EnterTransition = fadeIn(
        animationSpec = tween(exitDurationMs),
    )

    fun hideFeed(): ExitTransition = fadeOut(
        animationSpec = tween(110),
    )
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
