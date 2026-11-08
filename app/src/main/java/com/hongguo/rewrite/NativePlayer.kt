package com.hongguo.rewrite

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import android.content.Intent
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
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
    val previousFeedDrama = if (state.feedMode) state.feedItems.take(state.feedIndex)
        .lastOrNull { it.id !in state.hiddenRecommendationIds } else null
    val nextFeedDrama = if (state.feedMode) state.feedItems.drop(state.feedIndex + 1)
        .firstOrNull { it.id !in state.hiddenRecommendationIds } else null
    val activeFeedPage = if (previousFeedDrama != null) 1 else 0
    val nextFeedPage = state.feedMode &&
        (nextFeedDrama != null || state.feedHasMore || state.feedLoading ||
            state.feedError != null)
    val feedPages = activeFeedPage + 1 + if (nextFeedPage) 1 else 0
    val activePage = if (state.feedMode) activeFeedPage
        else state.episodeIndex.coerceIn(0, detail.episodes.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = activePage,
        pageCount = { if (state.feedMode) feedPages else detail.episodes.size },
    )
    val playback = remember(state.episodeIndex) { ReelPlaybackControls() }
    var speed by remember(detail.drama.id) { mutableFloatStateOf(state.defaultPlaybackSpeed) }
    // Full-bleed video as in the reference screenshot; users can switch to
    // fit mode when letterbox/crop considerations or subtitles require it.
    var fillVideo by rememberSaveable { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
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
    var batchEpisodes by remember { mutableStateOf(emptyList<Episode>()) }
    val batchFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder ->
        val episodesToSave = batchEpisodes
        batchEpisodes = emptyList()
        if (folder != null && episodesToSave.isNotEmpty()) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val safeTitle = detail.drama.title.replace(
                Regex("""[^\p{L}\p{N}_-]"""), "_"
            ).take(35).ifBlank { "Hongguo" }
            val parent = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    folder, DocumentsContract.getTreeDocumentId(folder))
            }.getOrNull()
            episodesToSave.forEach { ep ->
                val uri = runCatching {
                    parent?.let {
                        DocumentsContract.createDocument(context.contentResolver, it,
                            "video/mp4", safeTitle + "-EP" + ep.index + ".mp4")
                    }
                }.getOrNull()
                if (uri != null) model.downloadNative(ep.vid, uri)
            }
        }
    }
    val downloadBatch: (Int) -> Unit = { count ->
        val from = state.episodeIndex
        batchEpisodes = detail.episodes.drop(from).take(count)
        if (batchEpisodes.isNotEmpty()) batchFolder.launch(null)
    }
    val downloadSelected: () -> Unit = {
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
    }
    fun flushThenClose() {
        selected?.let { model.savePlaybackPosition(
            it.vid, playback.positionMs, playback.durationMs
        ) }
        model.backFromPlayer()
    }
    BackHandler(enabled = !showPlaybackSettings && !showComments && !showEpisodes) {
        flushThenClose()
    }

    // A programmatic pager correction is not a user swipe. Only dispatch a reel
    // transition after the user has actually scrolled and the page has settled.
    // This mirrors main's settled-page interaction model and avoids duplicate
    // transitions as a 2/3-page reel window recenters between dramas.
    val latestActivePage by rememberUpdatedState(activePage)
    var recenteringPager by remember(pagerState) { mutableStateOf(false) }
    LaunchedEffect(pagerState, detail.drama.id, state.feedMode) {
        var userScrollArmed = false
        snapshotFlow {
            Triple(pagerState.isScrollInProgress, pagerState.settledPage,
                recenteringPager)
        }.distinctUntilChanged().collect { (scrolling, settled, recentering) ->
            if (scrolling && !recentering) {
                userScrollArmed = true
            } else if (!scrolling && !recentering && userScrollArmed) {
                userScrollArmed = false
                if (state.feedMode) {
                    if (settled != latestActivePage)
                        model.navigateFeed(if (settled > latestActivePage) 1 else -1)
                } else model.playIfDifferent(settled)
            }
        }
    }
    LaunchedEffect(state.episodeIndex) {
        if (pagerState.settledPage != activePage && !pagerState.isScrollInProgress) {
            recenteringPager = true
            try {
                pagerState.animateScrollToPage(activePage)
            } finally {
                recenteringPager = false
            }
        }
        showComments = false
        showEpisodes = false
        showPlaybackSettings = false
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
    // MediaSessionService now owns checkpoints and the sleep timer, including
    // when the Activity has stopped. Avoid two competing periodic writers.
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
            userScrollEnabled = !showComments && !showEpisodes && !showPlaybackSettings,
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
                            speed = speed,
                            fillVideo = fillVideo,
                            controls = playback,
                            expectedVid = episode.vid,
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
                        // The next media item may already be prepared. Do not
                        // flash a spinner while the service swaps its MediaItem.
                        // Use the destination artwork as a stable frame; only
                        // show progress after a noticeable loading delay.
                        if (detail.drama.cover.isNotBlank()) {
                            AsyncImage(
                                model = model.cover(detail.drama.cover),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(Modifier.fillMaxSize().background(
                                Color.Black.copy(alpha = 0.26f)))
                        }
                        var showDelayedSpinner by remember(detail.drama.id, state.episodeIndex) {
                            mutableStateOf(false)
                        }
                        LaunchedEffect(detail.drama.id, state.episodeIndex) {
                            delay(450)
                            showDelayedSpinner = true
                        }
                        if (showDelayedSpinner)
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
                    // Adjacent reel pages should show the known target cover,
                    // not a black "switching drama" placeholder during the
                    // user's vertical swipe. Never imply the video is actually
                    // playing until Media3 renders the new item.
                    if (preview != null && preview.cover.isNotBlank()) {
                        AsyncImage(
                            model = model.cover(preview.cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.fillMaxSize().background(
                            Color.Black.copy(alpha = 0.44f)))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(preview?.title ?: if (state.feedMode) "正在获取官方推荐"
                             else detail.drama.title,
                            color = Color.White, fontSize = 19.sp)
                        Text(if (state.feedMode) {
                                when {
                                    state.feedLoading -> "正在载入新短剧…"
                                    state.feedError != null -> state.feedError
                                    nextFeedDrama == null -> "继续上滑获取推荐"
                                    else -> "上滑或下滑继续观看"
                                }
                            } else "第 ${episode?.index ?: page + 1} 集",
                            color = Color.LightGray, fontSize = 13.sp)
                        if (state.feedMode && state.feedError != null &&
                            !state.feedLoading && preview == null) {
                            OutlinedButton(onClick = model::retryOfficialReels,
                                modifier = Modifier.testTag("official_reels_retry")) {
                                Text("重试官方推荐", color = Color.White)
                            }
                        }
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
                playback = playback,
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
            onPlaybackSettings = { showPlaybackSettings = true },
            onBack = ::flushThenClose,
            onDramaDetails = {
                selected?.let { ep ->
                    model.savePlaybackPosition(ep.vid, playback.positionMs, playback.durationMs)
                }
                model.showDramaDetails()
            },
            onFavorite = model::favorite,
            onLike = model::toggleLocalLike,
            onComments = { showComments = true },
            onDanmaku = { model.setDanmaku(!state.danmakuEnabled) },


        )
        }
        ReelBottomDock(
            detail = detail,
            episode = selected,
            playback = playback,
            onEpisodes = { showEpisodes = true },
        )
    }

    if (showPlaybackSettings) {
        PlaybackExperienceSheet(
            state = state,
            model = model,
            speed = speed,
            fillVideo = fillVideo,
            onDismiss = { showPlaybackSettings = false },
            onSpeed = {
                speed = it
                model.setDefaultPlaybackSpeed(it)
            },
            onFillVideo = { fillVideo = it },
            onQuality = {
                model.setQualityLimit(it, playback.positionMs, playback.durationMs)
                showPlaybackSettings = false
            },
            onDownload = downloadSelected,
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选集 · 共 ${detail.episodes.size} 集",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (state.backend.isBlank()) {
                    TextButton(onClick = { downloadBatch(5) },
                        modifier = Modifier.testTag("batch_download_5")) {
                        Text("下载当前起5集")
                    }
                } else {
                    TextButton(onClick = {
                        model.download(detail.episodes[state.episodeIndex].index,
                            detail.episodes.drop(state.episodeIndex).take(5)
                                .lastOrNull()?.index ?: detail.episodes[state.episodeIndex].index)
                        showEpisodes = false
                    }) { Text("下载5集") }
                }
            }
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
    val player = playback.player
    var framePositionMs by remember(player) {
        mutableLongStateOf(
            player?.currentPosition?.coerceAtLeast(0L) ?: playback.positionMs
        )
    }
    LaunchedEffect(player) {
        while (isActive) {
            if (player?.isPlaying == true) {
                withFrameNanos { }
            } else {
                // When paused, do not render idle frames. Still observe
                // seeks and media readiness without a perceptible delay.
                delay(50)
            }
            val time = player?.currentPosition?.coerceAtLeast(0L)
                ?: playback.positionMs
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
    speed: Float,
    fillVideo: Boolean,
    controls: ReelPlaybackControls,
    expectedVid: String,
) {
    // ViewModel configures media in the foreground service after each episode
    // fetch, even while the UI lifecycle is STOPPED. Compose only shows its
    // video surface and keeps local playback buttons in sync.
    val player by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()

    // The ExoPlayer belongs to MediaSessionService and is intentionally
    // reused for every episode. ReelPlaybackControls, however, is recreated
    // by remember(episodeIndex). Both effects MUST rebind when controls change,
    // even if the actual ExoPlayer reference stays identical. Otherwise a
    // previous episode's polling coroutine keeps updating the old controls
    // and the new bottom dock is stuck at 00:00 / --:--.
    DisposableEffect(player, controls) {
        controls.player = player
        onDispose {
            if (controls.player === player) controls.player = null
            // A detached PlayerView must not release the background player.
        }
    }
    LaunchedEffect(player, speed) { player?.setPlaybackSpeed(speed) }
    LaunchedEffect(player, controls, expectedVid) {
        // Avoid showing the previous episode's time/duration while the service
        // is resolving or installing the new MediaItem (or after a rapid swipe).
        controls.positionMs = 0L
        controls.durationMs = 0L
        controls.isPlaying = false
        while (isActive) {
            val sample = ReelClockPolicy.sample(
                expectedVid = expectedVid,
                actualMediaId = player?.currentMediaItem?.mediaId,
                positionMs = player?.currentPosition ?: 0L,
                durationMs = player?.duration ?: 0L,
                isPlaying = player?.isPlaying == true,
            )
            controls.player = if (sample.currentItem) player else null
            controls.positionMs = sample.positionMs
            controls.durationMs = sample.durationMs
            controls.isPlaying = sample.playing
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
