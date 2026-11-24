package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * Moveable, edge-snapping mini video surface. The Media3 player still belongs
 * to BackgroundPlaybackService; dragging and folding never create a player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun InAppMiniPlayer(
    state: AppState,
    model: DramaViewModel,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail ?: return
    val selected = detail.episodes.getOrNull(state.episodeIndex) ?: return
    val media by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()
    val showCover = PlaybackFramePolicy.showCover(
        selected.vid, media?.currentMediaItem?.mediaId,
    )
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("hongguo_in_app_mini_position", android.content.Context.MODE_PRIVATE)
    }
    var xFraction by rememberSaveable { mutableFloatStateOf(prefs.getFloat("x", 1f)) }
    var yFraction by rememberSaveable { mutableFloatStateOf(prefs.getFloat("y", 1f)) }
    var folded by rememberSaveable { mutableStateOf(prefs.getBoolean("folded", false)) }
    val width = if (folded) 48.dp else 90.dp
    val height = if (folded) 48.dp else 160.dp
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Leave room for the app's 56dp header, navigation and system safe insets.
    val travelX = with(density) {
        (configuration.screenWidthDp.dp - width - 24.dp).coerceAtLeast(0.dp).toPx()
    }
    val travelY = with(density) {
        (configuration.screenHeightDp.dp - height - 176.dp).coerceAtLeast(0.dp).toPx()
    }
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    MiniPlayerLayoutPolicy.offsetPx(travelX, xFraction).roundToInt(),
                    MiniPlayerLayoutPolicy.offsetPx(travelY, yFraction).roundToInt(),
                )
            }
            .size(width, height)
            .clip(RoundedCornerShape(if (folded) 24.dp else 11.dp))
            .background(Color.Black)
            .testTag("in_app_mini_player"),
        contentAlignment = Alignment.Center,
    ) {
        // Keep AndroidView composed in folded mode: the service player and its
        // media item must not be recreated on a purely visual state change.
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    player = media
                }
            },
            update = { view ->
                if (view.player !== media) view.player = media
            },
            modifier = Modifier.fillMaxSize().testTag("mini_video_surface"),
        )
        if (showCover && detail.drama.cover.isNotBlank()) {
            AsyncImage(
                model = model.cover(detail.drama.cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (folded) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
                    .clickable {
                        folded = false
                        prefs.edit().putBoolean("folded", false).apply()
                    }
                    .testTag("mini_unfold"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.OpenInFull, contentDescription = "展开小窗",
                    tint = Color.White, modifier = Modifier.size(24.dp))
            }
        } else {
            // A transparent input layer wins touch over PlayerView. A drag
            // moves the window, a normal tap opens the full player.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(travelX, travelY) {
                        detectDragGestures(
                            onDragEnd = {
                                xFraction = MiniPlayerLayoutPolicy.snapX(xFraction)
                                prefs.edit().putFloat("x", xFraction)
                                    .putFloat("y", yFraction).apply()
                            },
                            onDrag = { change, amount ->
                                xFraction = MiniPlayerLayoutPolicy.move(
                                    xFraction, amount.x, travelX)
                                yFraction = MiniPlayerLayoutPolicy.move(
                                    yFraction, amount.y, travelY)
                                change.consume()
                            },
                        )
                    }
                    .clickable(onClick = onExpand)
                    .testTag("mini_expand_player"),
            )
            Box(
                Modifier.align(Alignment.TopEnd).size(40.dp)
                    .clickable {
                        folded = true
                        prefs.edit().putBoolean("folded", true)
                            .putFloat("x", xFraction).putFloat("y", yFraction).apply()
                    }
                    .testTag("mini_fold"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.CloseFullscreen,
                    contentDescription = "折叠小窗",
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
