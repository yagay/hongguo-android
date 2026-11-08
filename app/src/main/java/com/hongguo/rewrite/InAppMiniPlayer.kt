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
internal fun InAppMiniPlayer(state: AppState, model: DramaViewModel) {
    val detail = state.detail ?: return
    val selected = detail.episodes.getOrNull(state.episodeIndex) ?: return
    val activity = LocalContext.current as? MainActivity
    val media by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()
    var currentItemReady by remember(selected.vid) { mutableStateOf(false) }
    var isPlaying by remember(selected.vid) { mutableStateOf(false) }

    LaunchedEffect(media, selected.vid) {
        while (isActive) {
            val matches = media?.currentMediaItem?.mediaId == selected.vid
            currentItemReady = matches
            isPlaying = matches && media?.isPlaying == true
            delay(250)
        }
    }
    // A MediaItem transition can briefly invalidate the selected vid, but
    // never unmount the PlayerView: its video surface must survive every
    // automatic episode and reel switch.
    // Only cover an absent or mismatched media item. During buffering the
    // existing PlayerView retains its last decoded frame instead of flashing
    // the poster over a still-valid playing video.
    val showCover = media == null || !currentItemReady

    Row(
        modifier = Modifier.fillMaxWidth()
            .height(94.dp)
            .background(Color(0xFF1D1D20))
            .clickable(onClick = model::restorePlayer)
            .padding(horizontal = 9.dp, vertical = 8.dp)
            .testTag("in_app_mini_player"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(width = 128.dp, height = 72.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Always compose ONE persistent view. On playback/episode changes,
            // Media3 reuses its ExoPlayer; transient mismatches must not dispose
            // the Android SurfaceView and produce a black frame on reattach.
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
            // The outgoing frame stays behind a cover until the new item is
            // READY; this avoids displaying the previous series by mistake.
            if (showCover && detail.drama.cover.isNotBlank()) {
                AsyncImage(
                    model = model.cover(detail.drama.cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                detail.drama.title,
                fontSize = 13.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "第${selected.index}集 · " + when {
                    !currentItemReady -> "准备播放"
                    isPlaying -> "正在播放"
                    else -> "已暂停"
                },
                fontSize = 11.sp,
                color = Color(0xFFB7B7BC),
                maxLines = 1,
            )
        }
        IconButton(
            onClick = { activity?.enterVideoPictureInPicture(selected.vid) },
            enabled = currentItemReady,
            modifier = Modifier.size(36.dp).testTag("mini_system_pip"),
        ) {
            Icon(
                Icons.Outlined.PictureInPictureAlt,
                contentDescription = "切换到其他应用画中画",
                tint = if (currentItemReady) Color.White else Color.Gray,
            )
        }
        IconButton(
            onClick = {
                val player = media
                if (player != null &&
                    player.currentMediaItem?.mediaId == selected.vid) {
                    if (player.isPlaying) player.pause() else player.play()
                }
            },
            enabled = currentItemReady,
            modifier = Modifier.size(40.dp).testTag("mini_play_pause"),
        ) {
            Icon(
                if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = if (currentItemReady) Color.White else Color.Gray,
            )
        }
        IconButton(
            onClick = model::closePlayer,
            modifier = Modifier.size(38.dp).testTag("mini_close"),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭播放",
                tint = Color(0xFFE9E9EA))
        }
    }
}
