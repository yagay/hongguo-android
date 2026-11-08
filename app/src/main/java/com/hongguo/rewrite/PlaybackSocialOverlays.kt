package com.hongguo.rewrite

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import android.content.Intent
import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Comments and timed danmaku remain independent from media-surface lifetime.
 * Recompose this overlay without changing the service-owned ExoPlayer.
 */
@Composable
internal fun CommentsSheet(state: AppState, model: DramaViewModel) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 540.dp)
            .padding(horizontal = HongguoUi.pagePadding),
        verticalArrangement = Arrangement.spacedBy(HongguoUi.pageSectionGap),
    ) {
        HongguoSheetHeader(title = "评论", dark = true) {
            HongguoIconAction(onClick = { model.loadComments() },
                modifier = Modifier.testTag("player_comments_refresh")) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新评论",
                    tint = Color.White)
            }
        }
        val countLabel = (state.commentsTotal ?: state.socialStats
            ?.takeIf { it.seriesId == state.detail?.drama?.id }
            ?.episodes?.get(state.detail?.episodes
                ?.getOrNull(state.episodeIndex)?.vid)
            ?.comments)?.let { total ->
                "共 " + SocialCountFormatter.format(total) + " 条"
            } ?: "已加载 " + state.comments.size + " 条"
        Text(countLabel, color = Color(0xFFC5C3C9), fontSize = 12.sp)
        Text("按当前分集读取公开评论 · 暂不支持匿名发布或点赞同步",
            color = Color(0xFFC5C3C9), fontSize = 12.sp)
        if (state.commentsLimited) {
            Text("当前显示官网公开的部分评论，非全部评论",
                color = Color(0xFFFFB885), fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.comments, key = { it.id }) { comment ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (comment.avatar.startsWith("https://")) {
                        AsyncImage(
                            model = comment.avatar, contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.size(36.dp)
                            .background(Color(0xFF404045), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Text(comment.author.take(1), color = Color.White,
                                fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(comment.author, color = Color.White,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            if (comment.likes > 0) {
                                Text("♥ " + comment.likes,
                                    color = Color.LightGray, fontSize = 12.sp)
                            }
                        }
                        Text(comment.content, color = Color.White,
                            fontSize = 14.sp, lineHeight = 20.sp)
                        if (comment.created > 0) {
                            val date = remember(comment.created) {
                                runCatching {
                                    val seconds = if (comment.created > 10_000_000_000L)
                                        comment.created / 1000 else comment.created
                                    Instant.ofEpochSecond(seconds)
                                        .atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                }.getOrDefault("")
                            }
                            if (date.isNotBlank()) {
                                Text(date, fontSize = 11.sp,
                                    color = Color.LightGray)
                            }
                        }
                        HorizontalDivider(color = Color(0xFF414043))
                    }
                }
            }
            if (state.commentsLoading) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                }
            }
            state.commentsError?.let { error -> item {
                Text(error, color = Color(0xFFFFB6A6), fontSize = 13.sp)
                HongguoAction(
                    label = "重新加载",
                    onClick = { model.loadComments() },
                    style = HongguoActionStyle.OUTLINE,
                )
            } }
            if (!state.commentsLoading && state.comments.isEmpty() &&
                state.commentsError == null) item {
                Text("当前分集没有可读取的评论",
                    color = Color(0xFFC5C3C9), fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                        .padding(vertical = 20.dp))
            }
            if (state.commentsHasMore && !state.commentsLoading) item {
                HongguoAction(
                    label = "加载更多评论",
                    onClick = { model.loadComments(reset = false) },
                    style = HongguoActionStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun TimedDanmakuOverlay(
    items: List<DanmakuEntry>,
    expectedVid: String,
    playback: ReelPlaybackControls,
    speedPreset: Int,
    tracks: Int,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val flightMs = DanmakuLayout.flightForSpeed(speedPreset)
    val lanes = tracks.coerceIn(1, 5)
    val placed = remember(items, lanes, flightMs) {
        DanmakuLayout.arrange(items, lanes, flightMs)
    }

    // The ordinary player controls update every 250 ms for progress labels.
    // That sampling rate must NOT drive scrolling text. The old 205-ms tween
    // left 45 ms of idle time between updates ("move, stop, move").
    //
    // Read the actual ExoPlayer playback clock on each display frame instead.
    // This follows pause, buffering, playback speed and seeking naturally.
    // Keep this frame state local to the overlay: the rest of the player UI
    // must not be recomposed at 60 fps.
    // Never animate the preceding episode's comments against the new one's
    // clock. The service intentionally reuses the same ExoPlayer instance, so
    // item identity and the controls object must participate in effect keys.
    val player = playback.player?.takeIf {
        it.currentMediaItem?.mediaId == expectedVid
    }
    var framePositionMs by remember(player, expectedVid) {
        mutableLongStateOf(player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
    }
    LaunchedEffect(player, playback, expectedVid) {
        while (isActive) {
            if (player?.isPlaying == true) {
                withFrameNanos { }
            } else {
                delay(50)
            }
            val time = player?.takeIf {
                it.currentMediaItem?.mediaId == expectedVid
            }?.currentPosition?.coerceAtLeast(0L) ?: playback.positionMs
            if (framePositionMs != time) framePositionMs = time
        }
    }

    // This state changes only when a danmaku enters/leaves; movement below
    // reads the current clock inside the placement lambda. Compose therefore
    // only repositions the active texts, not the entire video overlay.
    val visible by remember(placed, flightMs, lanes) {
        derivedStateOf {
            val now = framePositionMs
            placed.filter { it.startMs <= now && now < it.startMs + flightMs }
                .take(lanes)
        }
    }
    if (visible.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(
        color = Color.White,
        fontSize = fontSizeSp.coerceIn(12, 18).sp,
        fontWeight = FontWeight.Medium,
        shadow = Shadow(Color.Black, Offset(1f, 2f), 4f),
    )
    BoxWithConstraints(
        modifier.height((lanes * 34 + 12).dp).clipToBounds()
            .testTag("timed_danmaku_overlay")
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val laneHeightPx = with(density) { 34.dp.toPx() }
        val shadowPaddingPx = with(density) { 5.dp.toPx() }

        visible.forEach { line ->
            key(line.id) {
                val content = line.text.take(44)
                val measuredWidth = remember(
                    content, style, textMeasurer, density.density, density.fontScale
                ) {
                    textMeasurer.measure(
                        text = AnnotatedString(content), style = style,
                        maxLines = 1, softWrap = false,
                    ).size.width.toFloat() + shadowPaddingPx
                }

                Text(
                    text = content,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset {
                        val progress = DanmakuLayout.progress(
                            framePositionMs, line.startMs, flightMs
                        )
                        val xPx = DanmakuLayout.xPosition(
                            widthPx, measuredWidth, progress
                        )
                        IntOffset(
                            xPx.roundToInt(),
                            (line.lane * laneHeightPx).roundToInt(),
                        )
                    },
                )
            }
        }
    }
}

