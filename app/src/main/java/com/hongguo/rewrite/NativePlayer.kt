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
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val previousFeedDrama = if (state.feedMode) state.items.getOrNull(state.feedIndex - 1) else null
    val nextFeedDrama = if (state.feedMode) state.items.getOrNull(state.feedIndex + 1) else null
    val activeFeedPage = if (previousFeedDrama != null) 1 else 0
    val nextFeedPage = state.feedMode &&
        (nextFeedDrama != null || state.hasMore || state.moreLoading)
    val feedPages = activeFeedPage + 1 + if (nextFeedPage) 1 else 0
    val activePage = if (state.feedMode) activeFeedPage
        else state.episodeIndex.coerceIn(0, detail.episodes.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = activePage,
        pageCount = { if (state.feedMode) feedPages else detail.episodes.size },
    )
    val playback = remember(state.episodeIndex) { ReelPlaybackControls() }
    var speed by remember { mutableFloatStateOf(1f) }
    // Full-bleed video as in the reference screenshot; users can switch to
    // fit mode when letterbox/crop considerations or subtitles require it.
    var fillVideo by rememberSaveable { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showHeart by remember { mutableStateOf(false) }
    var longHolding by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableFloatStateOf(1f) }
    val likedNow by rememberUpdatedState(
        detail.drama.id in state.likedDramaIds
    )
    val currentSpeed by rememberUpdatedState(speed)
    var pendingDownloadVid by rememberSaveable { mutableStateOf<String?>(null) }
    val downloader = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val vid = pendingDownloadVid
        pendingDownloadVid = null
        if (uri != null && vid != null) model.downloadNative(vid, uri)
    }
    fun flushThenClose() {
        selected?.let { model.savePlaybackPosition(
            it.vid, playback.positionMs, playback.durationMs
        ) }
        model.closePlayer()
    }
    BackHandler { flushThenClose() }

    LaunchedEffect(pagerState, detail.drama.id, state.feedMode, state.feedIndex) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { index ->
                if (state.feedMode) {
                    if (index != activePage) {
                        model.navigateFeed(if (index > activePage) 1 else -1)
                    }
                } else model.playIfDifferent(index)
            }
    }
    LaunchedEffect(state.episodeIndex) {
        if (pagerState.settledPage != activePage && !pagerState.isScrollInProgress) {
            pagerState.animateScrollToPage(activePage)
        }
        showComments = false
        showEpisodes = false
    }
    LaunchedEffect(showComments, state.episodeIndex) {
        if (showComments) model.ensureCommentsLoaded()
    }
    LaunchedEffect(showHeart) {
        if (showHeart) {
            delay(650)
            showHeart = false
        }
    }
    // Periodic exact resume checkpoints, without writing while the media is
    // still preparing; changes in episode invalidate the old writer.
    LaunchedEffect(detail.drama.id, selected?.vid, state.playbackUrl) {
        val vid = selected?.vid
        if (vid != null && state.playbackUrl != null) {
            while (isActive) {
                delay(4_000)
                model.savePlaybackPosition(vid, playback.positionMs, playback.durationMs)
            }
        }
    }
    LaunchedEffect(detail.drama.id) {
        while (isActive) {
            delay(1_000)
            if (model.shouldPauseForTimer()) {
                playback.player?.pause()
            }
        }
    }
    LaunchedEffect(state.danmakuEnabled, state.episodeIndex, playback.durationMs) {
        if (state.danmakuEnabled) {
            while (isActive) {
                model.ensureDanmaku(playback.positionMs, playback.durationMs)
                delay(3500)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).testTag("reels_player")) {
        Box(Modifier.fillMaxWidth().weight(1f).testTag("reel_video_viewport")) {
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !showComments && !showEpisodes,
            modifier = Modifier.fillMaxSize().testTag("episode_vertical_pager"),
        ) { page ->
            val episode = detail.episodes.getOrNull(
                if (state.feedMode) state.episodeIndex else page
            )
            Box(Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center) {
                if (page == activePage && episode != null) {
                    val url = state.playbackUrl
                    if (url != null) {
                        Media3Reel(
                            url = url, speed = speed, fillVideo = fillVideo,
                            nativeVideo = model.nativeVideoFor(episode.vid),
                            controls = playback,
                            resumeAtMs = state.resumePositionMs,
                            onFirstFrame = { model.firstFrame(episode.vid) },
                            onFinished = {
                                model.finishEpisode(episode.vid)
                                model.nextEpisode(episode.vid)
                            },
                            onError = { model.mediaError(episode.vid, it) },
                        )
                        Box(
                            Modifier.fillMaxSize()
                                .pointerInput(detail.drama.id, state.episodeIndex) {
                                    detectTapGestures(
                                        onTap = { playback.toggle() },
                                        onDoubleTap = {
                                            if (!likedNow) model.likeIfNotAlready()
                                            showHeart = true
                                        },
                                        onLongPress = {
                                            if (!longHolding) {
                                                speedBeforeHold = currentSpeed
                                                longHolding = true
                                                speed = 2f
                                            }
                                        },
                                        onPress = {
                                            tryAwaitRelease()
                                            if (longHolding) {
                                                speed = speedBeforeHold
                                                longHolding = false
                                            }
                                        },
                                    )
                                },
                        )
                        if (showHeart) {
                            Icon(Icons.Outlined.Favorite,
                                contentDescription = "已收藏",
                                tint = Color(0xFFFF6D6D),
                                modifier = Modifier.size(92.dp))
                        }
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
                    val preview = if (state.feedMode) {
                        if (page < activePage) previousFeedDrama else nextFeedDrama
                    } else null
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(preview?.title ?: if (state.feedMode) "正在获取更多推荐"
                             else detail.drama.title,
                            color = Color.White, fontSize = 19.sp)
                        Text(if (state.feedMode) "正在切换短剧…"
                             else "第 ${episode?.index ?: page + 1} 集",
                            color = Color.LightGray)
                    }
                }
            }
        }

        // Passive gradient for legible labels on bright and dark video frames.
        // No pointer input here: paging and player gestures are unaffected.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.00f to Color.Black.copy(alpha = 0.52f),
                    0.20f to Color.Transparent,
                    0.57f to Color.Transparent,
                    1.00f to Color.Black.copy(alpha = 0.84f),
                )
            )
        )

        if (state.danmakuEnabled && selected != null && !showComments && !showEpisodes) {
            TimedDanmakuOverlay(
                items = state.danmakuItems,
                currentMs = playback.positionMs,
                speedPreset = state.danmakuSpeed,
                tracks = state.danmakuDensity,
                fontSizeSp = state.danmakuFontSize,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(top = 58.dp)
            )
        }

        ReelScreenshotOverlay(
            state = state,
            detail = detail,
            episode = selected,
            speed = speed,
            fillVideo = fillVideo,
            qualityLimit = state.qualityLimit,
            sleepTimerLabel = state.sleepTimerLabel,
            onQuality = { model.setQualityLimit(it, playback.positionMs, playback.durationMs) },
            onSleepMinutes = model::sleepAfterMinutes,
            onSleepEpisodes = model::sleepAfterEpisodes,
            onToggleFillVideo = { fillVideo = !fillVideo },
            onBack = ::flushThenClose,
            onFavorite = model::favorite,
            onLike = model::toggleLocalLike,
            onComments = { showComments = true },
            onDanmaku = { model.setDanmaku(!state.danmakuEnabled) },
            onSpeed = { speed = it },
            onShare = {
                val vid = selected?.vid.orEmpty()
                val link = "https://hongguoduanju.com/player/${detail.drama.id}/$vid"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, detail.drama.title + "\n" + link)
                }
                context.startActivity(Intent.createChooser(intent, "分享剧集"))
            },
            onDownload = {
                selected?.let { ep ->
                    if (state.backend.isBlank()) {
                        pendingDownloadVid = ep.vid
                        val safeTitle = detail.drama.title.replace(
                            Regex("""[^\p{L}\p{N}_-]"""), "_"
                        ).take(35).ifBlank { "Hongguo" }
                        downloader.launch(safeTitle + "-EP" + ep.index + ".mp4")
                    } else {
                        model.download(ep.index, ep.index)
                    }
                }
            },
        )
        }
        ReelBottomDock(
            detail = detail,
            episode = selected,
            playback = playback,
            onEpisodes = { showEpisodes = true },
        )
    }

    if (showComments) ModalBottomSheet(
        onDismissRequest = { showComments = false },
        containerColor = HongguoColors.playerSheet,
        contentColor = Color.White,
        modifier = Modifier.testTag("reel_comments_sheet"),
    ) {
        CommentsSheet(state, model)
    }

    if (showEpisodes) ModalBottomSheet(
        onDismissRequest = { showEpisodes = false },
        containerColor = HongguoColors.playerSheet,
        contentColor = Color.White,
        modifier = Modifier.testTag("reel_episodes_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 630.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    Box(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) HongguoColors.accent
                                        else Color(0xFF36363B))
                            .clickable {
                                if (state.feedMode) model.exitFeedToEpisode(index)
                                else model.play(index)
                                showEpisodes = false
                            }
                            .testTag("episode_" + index),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(episode.index.toString(), fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            if (state.relatedSeries == detail.drama.id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("其他季 / 相关作品", color = Color.White,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (state.relatedLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp),
                            strokeWidth = 2.dp, color = Color.White)
                    } else {
                        TextButton(onClick = { model.loadRelated(detail.drama.id) }) {
                            Text("刷新", color = Color.White)
                        }
                    }
                }
                state.relatedWorks.take(15).forEach { work ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF35353A), RoundedCornerShape(10.dp))
                            .clickable(enabled = !work.upcoming) {
                                showEpisodes = false
                                model.open(work.drama)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(work.drama.title, color = Color.White, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(work.season, color = Color.LightGray, fontSize = 11.sp)
                        }
                        Text(if (work.upcoming) "待上线" else "观看",
                            color = if (work.upcoming) Color.LightGray
                                else Color(0xFFFFA37D), fontSize = 12.sp)
                    }
                }
                if (!state.relatedLoading && state.relatedWorks.isEmpty()) {
                    Text(state.relatedError ?: "暂无官方关联作品",
                        fontSize = 12.sp, color = Color.LightGray,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun CommentsSheet(state: AppState, model: DramaViewModel) {
    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("评论", style = MaterialTheme.typography.titleLarge,
                color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                (state.commentsTotal ?: state.socialStats
                    ?.takeIf { it.seriesId == state.detail?.drama?.id }
                    ?.episodes?.get(state.detail?.episodes
                        ?.getOrNull(state.episodeIndex)?.vid)
                    ?.comments)?.let { total ->
                    " · 共 " + SocialCountFormatter.format(total) + " 条"
                } ?: " · 已加载 " + state.comments.size + " 条",
                color = Color.LightGray, fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
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
                            Text(comment.author, color = Color(0xFFFFD5C5),
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
    speedPreset: Int,
    tracks: Int,
    fontSizeSp: Int,
    modifier: Modifier = Modifier,
) {
    val flightMs = DanmakuLayout.flightForSpeed(speedPreset)
    val lanes = tracks.coerceIn(1, 5)
    // Stable lane allocations prevent comments jumping to a different row
    // when one of their neighbors disappears.
    val placed = remember(items, lanes, flightMs) {
        DanmakuLayout.arrange(items, lanes, flightMs)
    }
    val visible = remember(placed, currentMs / 250L) {
        placed.filter { line ->
            currentMs >= line.startMs &&
                currentMs < line.startMs + flightMs
        }.take(lanes)
    }
    if (visible.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val style = TextStyle(
        color = Color.White,
        fontSize = fontSizeSp.coerceIn(12, 18).sp,
        fontWeight = FontWeight.Medium,
        shadow = Shadow(Color.Black, Offset(1f, 2f), 4f),
    )
    BoxWithConstraints(modifier.height((lanes * 34 + 12).dp).clipToBounds()
        .testTag("timed_danmaku_overlay")) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val laneHeightPx = with(density) { 34.dp.toPx() }
        val paddingPx = with(density) { 12.dp.toPx() }
        visible.forEach { line ->
            key(line.id) {
                // Use the actual composed text width, not a fraction of the
                // screen width. Each line fully enters AND leaves the frame.
                val content = line.text.take(44)
                val measuredWidth = textMeasurer.measure(
                    text = AnnotatedString(content), style = style,
                    maxLines = 1, softWrap = false,
                ).size.width.toFloat() + paddingPx
                val progress = DanmakuLayout.progress(currentMs, line.startMs, flightMs)
                val targetPx = DanmakuLayout.xPosition(
                    widthPx, measuredWidth, progress
                )
                val xPx by animateFloatAsState(
                    targetValue = targetPx,
                    animationSpec = tween(205, easing = LinearEasing),
                    label = "danmaku_" + line.id.take(8),
                )
                Text(
                    text = content,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .offset {
                            IntOffset(xPx.roundToInt(),
                                (line.lane * laneHeightPx).roundToInt())
                        }
                        .background(Color.Black.copy(alpha = 0.23f),
                            RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
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
    fillVideo: Boolean,
    nativeVideo: RemoteVideo?,
    controls: ReelPlaybackControls,
    resumeAtMs: Long,
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val first by rememberUpdatedState(onFirstFrame)
    val end by rememberUpdatedState(onFinished)
    val fail by rememberUpdatedState(onError)
    val player = remember(url, nativeVideo) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        var seeked = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = first()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !seeked) {
                    seeked = true
                    val target = ResumePolicy.resumable(
                        resumeAtMs, player.duration.takeIf { it > 0L } ?: 0L)
                    if (target > 0L) player.seekTo(target)
                }
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
    // App backgrounding must not keep playing sound offscreen. Preserve an
    // intentional manual pause when the activity returns to foreground.
    DisposableEffect(player, lifecycleOwner) {
        var resumeOnForeground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnForeground = player.isPlaying
                    player.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeOnForeground) {
                        resumeOnForeground = false
                        player.play()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize().testTag("native_player"),
        update = {
            it.player = player
            it.resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
    )
}
