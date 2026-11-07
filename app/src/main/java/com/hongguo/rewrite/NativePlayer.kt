package com.hongguo.rewrite

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.hongguotv.core.RemoteVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One page at a time, one Media3 player. Pager owns vertical touch gestures;
 * comment and episode sheets own their own independent scrolling.
 *
 * Vertical-feed implementation follows the architecture of ComposeReels and
 * compose-reels, but uses existing project media and content sources directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePlayer(state: AppState, model: DramaViewModel) {
    val detail = state.detail ?: return
    if (detail.episodes.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center) {
            Text("此剧暂时没有可播放的分集", color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    val selected = detail.episodes.getOrNull(state.episodeIndex)
    val pagerState = rememberPagerState(
        initialPage = state.episodeIndex.coerceIn(0, detail.episodes.lastIndex),
        pageCount = { detail.episodes.size },
    )
    val playback = remember(state.episodeIndex) { ReelPlaybackControls() }
    var speed by remember { mutableFloatStateOf(1f) }
    var showComments by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var pendingDownloadVid by rememberSaveable { mutableStateOf<String?>(null) }
    val downloader = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val vid = pendingDownloadVid
        pendingDownloadVid = null
        if (uri != null && vid != null) model.downloadNative(vid, uri)
    }
    BackHandler { model.closePlayer() }

    LaunchedEffect(pagerState, detail.drama.id) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { model.playIfDifferent(it) }
    }
    LaunchedEffect(state.episodeIndex) {
        if (pagerState.settledPage != state.episodeIndex && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(state.episodeIndex)
        }
        showComments = false
        showEpisodes = false
    }
    LaunchedEffect(showComments, state.episodeIndex) {
        if (showComments) model.loadComments()
    }
    LaunchedEffect(state.danmakuEnabled, state.episodeIndex, playback.durationMs) {
        if (state.danmakuEnabled) {
            while (isActive) {
                model.ensureDanmaku(playback.positionMs, playback.durationMs)
                delay(3500)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("reels_player")) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !showComments && !showEpisodes,
            modifier = Modifier.fillMaxSize().testTag("episode_vertical_pager"),
        ) { page ->
            val episode = detail.episodes[page]
            Box(Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center) {
                if (page == state.episodeIndex) {
                    val url = state.playbackUrl
                    if (url != null) {
                        Media3Reel(
                            url = url, speed = speed,
                            nativeVideo = model.nativeVideoFor(episode.vid),
                            controls = playback,
                            onFirstFrame = { model.firstFrame(episode.vid) },
                            onFinished = { model.nextEpisode(episode.vid) },
                            onError = { model.mediaError(episode.vid, it) },
                        )
                        Box(
                            Modifier.fillMaxSize().clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { playback.toggle() },
                        )
                        if (!playback.isPlaying && playback.positionMs > 0) {
                            Icon(Icons.Outlined.PlayArrow, "继续播放",
                                tint = Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.size(68.dp))
                        }
                    } else if (state.playbackError == null) {
                        CircularProgressIndicator(color = Color.White)
                    }
                    state.playbackError?.let { error ->
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("本集暂时不能播放", color = Color.White,
                                fontWeight = FontWeight.Bold)
                            Text(error, color = Color(0xFFD9D4D1), fontSize = 13.sp)
                            OutlinedButton(onClick = model::retryPlayback) {
                                Icon(Icons.Outlined.Refresh, null)
                                Text("重新尝试")
                            }
                            Text("可继续上滑或下滑切换剧集",
                                color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(detail.drama.title, color = Color.White, fontSize = 19.sp)
                        Text("第 ${episode.index} 集", color = Color.LightGray)
                    }
                }
            }
        }

        if (state.danmakuEnabled && selected != null && !showComments && !showEpisodes) {
            TimedDanmakuOverlay(state.danmakuItems,
                playback.positionMs,
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 86.dp))
        }

        // These controls intentionally live above the pager, not inside its
        // scrolling page, so the active episode cannot move the action rail.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = model::closePlayer) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(detail.drama.title, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text("第 ${selected?.index ?: 1} / ${detail.episodes.size} 集",
                    color = Color.LightGray, fontSize = 12.sp)
            }
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val saved = state.favorites.any { it.drama.id == detail.drama.id }
            ReelAction(
                if (saved) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (saved) "已收藏" else "收藏", "reel_favorite",
            ) { model.favorite() }
            ReelAction(Icons.Outlined.ChatBubbleOutline, "评论", "reel_comments") {
                showComments = true
            }
            ReelAction(Icons.Outlined.Subtitles,
                if (state.danmakuEnabled) "弹幕开" else "弹幕关", "reel_danmaku") {
                model.setDanmaku(!state.danmakuEnabled)
            }
            ReelAction(Icons.Outlined.Share, "分享", "reel_share") {
                val vid = selected?.vid.orEmpty()
                val link = "https://hongguoduanju.com/player/${detail.drama.id}/$vid"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, detail.drama.title + "\n" + link)
                }
                context.startActivity(Intent.createChooser(intent, "分享剧集"))
            }
            ReelAction(Icons.Outlined.Download, "下载", "reel_download") {
                selected?.let { ep ->
                    if (state.backend.isBlank()) {
                        pendingDownloadVid = ep.vid
                        val safeTitle = detail.drama.title.replace(
                            Regex("""[^\p{L}\p{N}_-]"""), "_"
                        ).take(35).ifBlank { "Hongguo" }
                        downloader.launch(safeTitle + "-EP" + ep.index + ".mp4")
                    } else model.download(ep.index, ep.index)
                }
            }
            ReelAction(Icons.Outlined.FormatListNumbered, "选集", "reel_episodes") {
                showEpisodes = true
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(start = 12.dp, end = 68.dp, top = 8.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${selected?.index ?: 1} 集", color = Color.White,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    speed = when (speed) {
                        1f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2f
                        else -> 1f
                    }
                }) { Text("${speed}×", color = Color.White) }
            }
            val duration = playback.durationMs
            if (duration > 0L) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val fraction = (playback.positionMs.toFloat() / duration)
                    .coerceIn(0f, 1f)
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { playback.seekTo((it * duration).toLong()) }
                        dragging = null
                    },
                    modifier = Modifier.fillMaxWidth().height(25.dp).testTag("reel_seek"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatClock(playback.positionMs), color = Color.LightGray, fontSize = 11.sp)
                    Text("/ " + formatClock(duration), color = Color.LightGray, fontSize = 11.sp)
                }
            } else {
                Text("上滑下一集 · 下滑上一集", color = Color.LightGray, fontSize = 11.sp)
            }
            state.danmakuError?.let {
                Text(it, color = Color(0xFFFFD0C7), fontSize = 11.sp, maxLines = 1)
            }
        }
    }

    if (showComments) ModalBottomSheet(
        onDismissRequest = { showComments = false },
        containerColor = Color(0xFF222022),
        contentColor = Color.White,
        modifier = Modifier.testTag("reel_comments_sheet"),
    ) {
        CommentsSheet(state, model)
    }

    if (showEpisodes) ModalBottomSheet(
        onDismissRequest = { showEpisodes = false },
        containerColor = Color(0xFF222022),
        contentColor = Color.White,
        modifier = Modifier.testTag("reel_episodes_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("选集 · 共 ${detail.episodes.size} 集",
                style = MaterialTheme.typography.titleMedium)
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(detail.episodes) { index, episode ->
                    val active = index == state.episodeIndex
                    OutlinedButton(
                        onClick = { model.play(index); showEpisodes = false },
                        modifier = Modifier.testTag("episode_$index"),
                    ) {
                        Text(episode.index.toString(),
                            color = if (active) Color(0xFFFF8B60) else Color.White)
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ReelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    test: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(50.dp)
                .background(Color.Black.copy(alpha = 0.24f), CircleShape)
                .testTag(test),
        ) {
            Icon(icon, contentDescription = title, tint = Color.White,
                modifier = Modifier.size(28.dp))
        }
        Text(title, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun CommentsSheet(state: AppState, model: DramaViewModel) {
    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("评论", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f), color = Color.White)
            TextButton(onClick = { model.loadComments() }) {
                Text("刷新", color = Color(0xFFFF9C75))
            }
        }
        Text("按当前分集读取公开评论 · 暂不支持匿名发布或点赞同步",
            color = Color.LightGray, fontSize = 12.sp)
        if (state.commentsLimited) {
            Text("当前显示官网公开的部分评论，非全部评论",
                color = Color(0xFFFFB885), fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.comments, key = { it.id }) { comment ->
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(comment.author, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFFD5C5), fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        if (comment.likes > 0) Text("♥ ${comment.likes}",
                            color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text(comment.content, color = Color.White, fontSize = 14.sp)
                    if (comment.created > 0) {
                        val date = remember(comment.created) {
                            runCatching {
                                val seconds = if (comment.created > 10_000_000_000L)
                                    comment.created / 1000 else comment.created
                                Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            }.getOrDefault("")
                        }
                        if (date.isNotBlank()) Text(date, fontSize = 11.sp, color = Color.LightGray)
                    }
                    HorizontalDivider(color = Color(0xFF414043))
                }
            }
            if (state.commentsLoading) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                }
            }
            state.commentsError?.let { error -> item {
                Text(error, color = Color(0xFFFFB6A6), fontSize = 13.sp)
                TextButton(onClick = { model.loadComments() }) {
                    Text("重新加载", color = Color(0xFFFF9C75))
                }
            } }
            if (!state.commentsLoading && state.comments.isEmpty() &&
                state.commentsError == null) item {
                Text("当前分集没有可读取的评论", color = Color.LightGray)
            }
            if (state.commentsHasMore && !state.commentsLoading) item {
                Button(onClick = { model.loadComments(reset = false) }) {
                    Text("加载更多评论")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TimedDanmakuOverlay(
    items: List<DanmakuEntry>,
    currentMs: Long,
    modifier: Modifier = Modifier,
) {
    val visible = remember(items, currentMs / 250L) {
        items.asSequence().filter {
            currentMs >= it.timeMs && currentMs < it.timeMs + 5_000L
        }.take(14).toList()
    }
    if (visible.isEmpty()) return
    BoxWithConstraints(modifier.height(128.dp).clipToBounds()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val lanePx = with(LocalDensity.current) { 36.dp.toPx() }
        visible.forEach { line ->
            val age = (currentMs - line.timeMs).coerceIn(0L, 5_000L)
            val target = 1.0f - age.toFloat() / 5_000f * 1.85f
            val horizontal by animateFloatAsState(target,
                animationSpec = tween(260, easing = LinearEasing), label = "danmaku")
            val lane = (line.id.hashCode() and Int.MAX_VALUE) % 3
            Text(
                line.content,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                modifier = Modifier.offset {
                    IntOffset((horizontal * widthPx).roundToInt(),
                        (lane * lanePx).roundToInt())
                },
                style = androidx.compose.ui.text.TextStyle(
                    shadow = Shadow(Color.Black, Offset(1f, 2f), 4f)
                ),
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(sec / 60, sec % 60)
}

/** Shared controls for the one active, currently visible reel only. */
internal class ReelPlaybackControls {
    var player by mutableStateOf<ExoPlayer?>(null)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    fun toggle() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { player?.seekTo(ms.coerceIn(0L, durationMs.coerceAtLeast(0))) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Media3Reel(
    url: String,
    speed: Float,
    nativeVideo: RemoteVideo?,
    controls: ReelPlaybackControls,
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val first by rememberUpdatedState(onFirstFrame)
    val end by rememberUpdatedState(onFinished)
    val fail by rememberUpdatedState(onError)
    val player = remember(url, nativeVideo) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = first()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) end()
            }
            override fun onPlayerError(error: PlaybackException) {
                fail(PlaybackProblem.media(error.errorCodeName,
                    error.cause?.javaClass?.simpleName))
            }
        }
        player.addListener(listener)
        if (nativeVideo != null) {
            val factory = DataSource.Factory { NativeVideoDataSource(nativeVideo) }
            player.setMediaSource(
                ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri(url))
            )
        } else {
            player.setMediaItem(MediaItem.fromUri(url))
        }
        player.prepare()
        player.playWhenReady = true
        controls.player = player
        onDispose {
            if (controls.player === player) controls.player = null
            player.removeListener(listener)
            player.release()
            nativeVideo?.close()
        }
    }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(player) {
        while (isActive) {
            controls.positionMs = player.currentPosition.coerceAtLeast(0)
            controls.durationMs = player.duration.takeIf {
                it > 0L && it != androidx.media3.common.C.TIME_UNSET
            } ?: 0L
            controls.isPlaying = player.isPlaying
            delay(250)
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize().testTag("native_player"),
        update = { it.player = player },
    )
}
