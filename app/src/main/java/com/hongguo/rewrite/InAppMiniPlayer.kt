package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * In-app mini player for catalog, search, account and drama detail pages.
 *
 * It is only a second video *surface* for the existing MediaSessionService
 * ExoPlayer, never another ExoPlayer, MediaSession or media source. The full
 * player and this view are never composed simultaneously in the app shell.
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
    // A MediaItem transition can briefly invalidate the selected vid, but
    // never unmount the PlayerView: its video surface must survive every
    // automatic episode and reel switch.
    // Only cover an absent or mismatched media item. During buffering the
    // existing PlayerView retains its last decoded frame instead of flashing
    // the poster over a still-valid playing video.
    val showCover = PlaybackFramePolicy.showCover(selected.vid,
        media?.currentMediaItem?.mediaId)

    // A compact portrait video-only overlay, not a layout-consuming 94dp
    // control bar. The surrounding page remains full-height and scrollable.
    Box(
        modifier = modifier
            .size(width = 90.dp, height = 160.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color.Black)
            .testTag("in_app_mini_player"),
        contentAlignment = Alignment.Center,
    ) {
        // Reattach the existing service-owned Media3 player. No new player,
        // media item, seek or reload is created when expanding the window.
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
        // PlayerView is an AndroidView that may consume pointer events;
        // a transparent Compose hit layer guarantees tap-to-expand.
        Box(
            Modifier.fillMaxSize()
                .clickable(onClick = onExpand)
                .testTag("mini_expand_player"),
        )
    }
}
