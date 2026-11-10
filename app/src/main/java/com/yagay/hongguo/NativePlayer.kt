package com.yagay.hongguo

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.DocumentsContract
import android.content.Intent
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Replay
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
import androidx.compose.ui.layout.onSizeChanged
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

/** One page at a time, one Media3 player. Pager owns vertical touch gestures;
 * comment and episode sheets own their own independent scrolling.
 *
 * Vertical-feed implementation follows the architecture of ComposeReels and
 * compose-reels, but uses existing project media and content sources directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePlayer(
    state: AppState,
    model: DramaViewModel,
    modeSlide: ReelModeSlideState,
    onExitFeed: () -> Unit,
    onBackFromSeries: () -> Unit,
    onEnterSeries: (Int) -> Unit,
    autoPipEnabled: Boolean,
    manualPipButtonVisible: Boolean,
    pipSystemStatus: VideoPiPAccess.Status,
    onAutoPipEnabled: (Boolean) -> Unit,
    onManualPipButtonVisible: (Boolean) -> Unit,
) {
    val detail = state.detail ?: return
    if (detail.episodes.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center) {
            Text("此剧暂时没有可播放的分集", color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    // One device-specific portrait viewport calibration, retained across
    // episodes and cold launches. It is never overwritten by later videos.
    val playerLayoutPrefs = remember(context) {
        context.getSharedPreferences("portrait_player_layout", Context.MODE_PRIVATE)
    }
    var fixedPortraitHeightDp by remember {
        mutableFloatStateOf(playerLayoutPrefs.getFloat("portrait_height_dp_v1", 0f))
    }
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
    // Between the feed's small paging window and the series' full episode
    // count the old page can exist for one composition before scrollToPage.
    // Do not draw a black placeholder over the stable video in that frame.
    val pagerRecenterPending = pagerState.currentPage != activePage &&
        !pagerState.isScrollInProgress
    val playback = remember(state.episodeIndex) { ReelPlaybackControls() }
    var showResumeFeedback by remember(detail.drama.id, state.episodeIndex) {
        mutableStateOf(false)
    }
    LaunchedEffect(showResumeFeedback) {
        if (showResumeFeedback) {
            delay(800)
            showResumeFeedback = false
        }
    }
    var speed by remember(detail.drama.id) { mutableFloatStateOf(state.defaultPlaybackSpeed) }
    // Preserve the complete encoded frame by default. Never crop or
    // stretch video just to hide aspect-ratio letterboxing. The external
    // seek dock and bottom tabs remain independent of the video Surface.
    var fillVideo by rememberSaveable { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var focusedPlaybackSetting by remember { mutableStateOf<PlaybackQuickSetting?>(null) }

    // Observe the actual decoded stream dimensions. Only portrait videos
    // use the measured-size layout; landscape videos retain normal FIT.
    val activeMediaPlayer by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()
    var activeVideoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    DisposableEffect(activeMediaPlayer) {
        val p = activeMediaPlayer
        activeVideoSize = p?.videoSize ?: VideoSize.UNKNOWN
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                activeVideoSize = videoSize
            }
        }
        p?.addListener(listener)
        onDispose { p?.removeListener(listener) }
    }
    // Search uses the exact landing page shared with the home search action.
    // Keep the Media3 surface and current feed mounted behind the sliding page.
    var searchOverlayOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.reelSearchRequest) {
        if (state.reelSearchRequest != 0L) {
            searchOverlayOpen = true
            model.consumeReelSearchRequest(state.reelSearchRequest)
        }
    }
    val accountDrawerState = rememberTransientReelDrawerState()
    val accountDrawerScope = rememberCoroutineScope()
    var showHeart by remember { mutableStateOf(false) }
    // A persisted, composable bitmask: each overlay section can be
    // independently hidden, with 63 meaning one-tap full clear screen.
    val chromePrefs = remember(context) {
        context.getSharedPreferences("player_clear_screen", Context.MODE_PRIVATE)
    }
    var hiddenChromeMask by remember {
        mutableIntStateOf(chromePrefs.getInt("hidden_chrome_mask_v2", 0) and 63)
    }
    fun setHiddenChromeMask(mask: Int) {
        hiddenChromeMask = mask and 63
        chromePrefs.edit().putInt("hidden_chrome_mask_v2", hiddenChromeMask).apply()
    }
    val clearScreenEnabled = hiddenChromeMask == 63
    var clearScreenMenuOpen by remember { mutableStateOf(false) }
    // Press-and-hold on either video edge temporarily plays forward at 2x.
    // Do not change the selected/persisted speed; releasing restores it.
    // The feedback stays visible even when clear-screen mode is active.
    var edgeFastForwardSide by remember(detail.drama.id, state.episodeIndex) {
        mutableStateOf<Int?>(null)
    }
    val likedNow by rememberUpdatedState(
        detail.drama.id in state.likedDramaIds
    )
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
        if (state.feedMode) onExitFeed()
        else onBackFromSeries()
    }
    BackHandler(enabled = state.navigation.destination != PlaybackDestination.DETAILS &&
        searchOverlayOpen) {
        searchOverlayOpen = false
    }
    BackHandler(enabled = state.navigation.destination != PlaybackDestination.DETAILS &&
        !searchOverlayOpen && accountDrawerState.isOpen) {
        accountDrawerScope.launch { accountDrawerState.close() }
    }
    BackHandler(enabled = state.navigation.destination != PlaybackDestination.DETAILS &&
        !searchOverlayOpen && accountDrawerState.isClosed && clearScreenEnabled &&
        focusedPlaybackSetting == null && !showComments && !showEpisodes) {
        setHiddenChromeMask(0)
    }
    BackHandler(enabled = state.navigation.destination != PlaybackDestination.DETAILS &&
        !searchOverlayOpen && accountDrawerState.isClosed && !clearScreenEnabled &&
        focusedPlaybackSetting == null && !showComments && !showEpisodes) {
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
    // A feed/series mode change can alter the pager's page index without
    // changing the current episode. Recenter immediately, without simulating
    // a user swipe or scrolling through all intermediate episodes.
    LaunchedEffect(state.episodeIndex, state.feedMode, activePage) {
        if (pagerState.settledPage != activePage) {
            recenteringPager = true
            try {
                pagerState.scrollToPage(activePage)
            } finally {
                recenteringPager = false
            }
        }
        showComments = false
        showEpisodes = false
        focusedPlaybackSetting = null
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

    ModalNavigationDrawer(
        drawerState = accountDrawerState,
        // Swiping right must NEVER open the left drawer, including in feed
        // mode. Keep dismissal gestures only after the menu button opens it.
        gesturesEnabled = ReelDrawerPolicy.gesturesEnabled(
            accountDrawerState.isOpen, state.feedMode, searchOverlayOpen
        ),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width((LocalConfiguration.current.screenWidthDp * 0.80f).dp),
                drawerContainerColor = HongguoColors.background,
            ) {
                ReelAccountDrawer(
                    state = state,
                    model = model,
                    onClose = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                    },
                    onAccount = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.show(Modal.ACCOUNT)
                    },
                    onHistoryItem = { drama ->
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.openFromFeedSelection(drama)
                    },
                    onReservations = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.show(Modal.ACCOUNT)
                    },
                    onDownloads = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.updateDownloads()
                        model.show(Modal.DOWNLOAD)
                    },
                    onShare = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.show(Modal.SHARE)
                    },
                    onSettings = {
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.show(Modal.SETTINGS)
                    },
                    onBrowseHome = {
                        // Explicit navigation is the only route to the old
                        // catalog home from the default swipe feed.
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.minimizePlayer()
                    },
                    onOfflineEpisode = { episode ->
                        accountDrawerScope.launch { accountDrawerState.close() }
                        model.openOfflineEpisode(episode)
                    },
                )
            }
        },
    ) {
    // Keep the closed drawer sheet at its true off-screen position.
    // Only the content page moves during feed/series transitions.
    ReelModeSlidePlayer(
        state = state,
        slide = modeSlide,
        backdrop = {
            ReelModeFeedBackdrop(
                state = state, detail = detail, episode = selected,
                playback = playback, slide = modeSlide, model = model,
            )
        },
    ) {
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("reels_player")) {
        val rotated = activeVideoSize.unappliedRotationDegrees % 180 != 0
        val pixelRatio = activeVideoSize.pixelWidthHeightRatio
        val frameWidthPx = if (rotated) activeVideoSize.height.toFloat()
            else activeVideoSize.width * pixelRatio
        val frameHeightPx = if (rotated) activeVideoSize.width * pixelRatio
            else activeVideoSize.height.toFloat()
        val portraitVideo = frameWidthPx > 0f && frameHeightPx > 0f &&
            pixelRatio > 0f && frameWidthPx < frameHeightPx
        val desiredHeight = if (portraitVideo)
            maxWidth * (frameHeightPx / frameWidthPx)
        else 0.dp
        // The first valid portrait video calibrates this phone's viewport.
        // Future portrait episodes keep that exact dp height without
        // jumping at different source resolutions or aspect ratios.
        // Landscape streams always bypass the portrait height.
        LaunchedEffect(portraitVideo, desiredHeight, maxHeight,
            fixedPortraitHeightDp) {
            if (fixedPortraitHeightDp <= 0f && portraitVideo &&
                desiredHeight > 0.dp && desiredHeight <= maxHeight - 40.dp) {
                fixedPortraitHeightDp = desiredHeight.value
                playerLayoutPrefs.edit()
                    .putFloat("portrait_height_dp_v1", desiredHeight.value).apply()
            }
        }
        val measuredPortraitHeight = fixedPortraitHeightDp.takeIf {
            portraitVideo && it > 0f && it.dp <= maxHeight - 40.dp
        }?.dp
        Column(Modifier.fillMaxSize().background(Color(0xFF232325))) {
        Box((if (measuredPortraitHeight != null)
                Modifier.fillMaxWidth().height(measuredPortraitHeight)
            else Modifier.fillMaxWidth().weight(1f))
            .onSizeChanged { modeSlide.recordVideoViewportHeight(it.height) }
            .testTag("reel_video_viewport")) {
        // A single stable SurfaceView lives under the pager. When switching
        // from one reel-per-drama to the episode pager, pager page positions
        // change but the actual Media3 video surface is never removed.
        selected?.let { playingEpisode ->
            Media3Reel(
                speed = speed,
                fastForwardHeld = edgeFastForwardSide != null,
                fillVideo = fillVideo,
                controls = playback,
                expectedVid = playingEpisode.vid,
                onSurfaceType = model::recordVideoSurface,
                onPlayerView = modeSlide::registerPlayerView,
            )
            // The SurfaceView can briefly retain a decoded frame from the
            // outgoing reel even after the new metadata is installed.
            // Keep the destination poster on top until its own FIRST_FRAME.
            if (state.renderedVideoVid != playingEpisode.vid) {
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .testTag("new_reel_waiting_first_frame")) {
                    if (detail.drama.cover.isNotBlank()) {
                        AsyncImage(
                            model = model.cover(detail.drama.cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        VerticalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !pagerRecenterPending && !searchOverlayOpen &&
                !showComments && !showEpisodes && focusedPlaybackSetting == null,
            modifier = Modifier.fillMaxSize().testTag("episode_vertical_pager"),
        ) { page ->
            val episode = detail.episodes.getOrNull(
                if (state.feedMode) state.episodeIndex else page
            )
            Box(
                Modifier.fillMaxSize().then(
                    if (page == activePage || pagerRecenterPending) Modifier
                    else Modifier.background(Color.Black)
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (page == activePage && episode != null) {
                    val url = state.playbackUrl
                    // The active pager page stays transparent so that the
                    // service-owned SurfaceView remains visible underneath.
                    if (url != null) {
                        Box(
                            Modifier.fillMaxSize()
                                .pointerInput(detail.drama.id, state.episodeIndex, state.feedMode) {
                                    detectTapGestures(
                                        onTap = {
                                            if (state.feedMode)
                                                onEnterSeries(state.episodeIndex)
                                            else {
                                                showResumeFeedback =
                                                    !playback.isPlaying && playback.player != null
                                                playback.toggle()
                                            }
                                        },
                                        onDoubleTap = {
                                            if (!likedNow) model.likeIfNotAlready()
                                            showHeart = true
                                        },
                                        onLongPress = { touch ->
                                            val side = HoldFastForwardPolicy.sideForTouch(
                                                touch.x, size.width.toFloat()
                                            )
                                            when {
                                                side != null && playback.player?.isPlaying == true ->
                                                    edgeFastForwardSide = side
                                                side == null -> clearScreenMenuOpen = true
                                            }
                                        },
                                        onPress = {
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                // Also restore after a cancelled gesture,
                                                // a page change or a disappearing Surface.
                                                edgeFastForwardSide = null
                                            }
                                        },
                                    )
                                },
                        )
                        edgeFastForwardSide?.let { side ->
                            Box(
                                Modifier.align(
                                    if (side < 0) Alignment.CenterStart
                                    else Alignment.CenterEnd
                                ).padding(horizontal = 15.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.43f),
                                        RoundedCornerShape(20.dp),
                                    ).padding(horizontal = 12.dp, vertical = 7.dp)
                                    .testTag("reel_edge_fast_forward_feedback"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "▶▶ 2× 快进",
                                    color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (!clearScreenEnabled && showHeart) {
                            Icon(Icons.Outlined.Favorite,
                                contentDescription = "已收藏",
                                tint = Color(0xFFFF6D6D),
                                modifier = Modifier.size(92.dp))
                        }
                        val centerIndicator = PlaybackCenterIndicatorPolicy.resolve(
                            transition = state.nextDramaTransition,
                            hasPlaybackError = state.playbackError != null,
                            currentMediaMatches = playback.currentMediaMatches,
                            isPlaying = playback.isPlaying,
                            playWhenReady = playback.playWhenReady,
                            buffering = playback.playbackState == Player.STATE_BUFFERING,
                            ended = playback.playbackState == Player.STATE_ENDED,
                            hasProgress = playback.positionMs > 0L,
                            showResumeFeedback = showResumeFeedback,
                        )
                        when (centerIndicator) {
                            PlaybackCenterIndicatorPolicy.Indicator.FINDING_NEXT_DRAMA,
                            PlaybackCenterIndicatorPolicy.Indicator.PREPARING_NEXT_VIDEO,
                            PlaybackCenterIndicatorPolicy.Indicator.RETRY_NEXT_DRAMA -> {
                                Column(
                                    modifier = Modifier
                                        .background(
                                            Color.Black.copy(alpha = 0.65f),
                                            RoundedCornerShape(15.dp),
                                        )
                                        .padding(horizontal = 17.dp, vertical = 13.dp)
                                        .testTag("next_drama_loading_feedback"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    if (centerIndicator ==
                                        PlaybackCenterIndicatorPolicy.Indicator.RETRY_NEXT_DRAMA) {
                                        Icon(
                                            Icons.Outlined.Refresh,
                                            contentDescription = "推荐加载失败",
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp),
                                        )
                                        Text(
                                            "暂时无法获取下一部短剧",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                        )
                                        OutlinedButton(
                                            onClick = model::retryOfficialReels,
                                            modifier = Modifier.testTag("next_drama_retry"),
                                        ) { Text("重试下一部", color = Color.White) }
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(27.dp),
                                            color = Color.White,
                                            strokeWidth = 2.5.dp,
                                        )
                                        Text(
                                            if (centerIndicator ==
                                                PlaybackCenterIndicatorPolicy.Indicator.FINDING_NEXT_DRAMA)
                                                "本剧已播完 · 正在获取下一部…"
                                            else "正在准备下一部视频…",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            "准备好后将自动播放",
                                            color = Color.White.copy(alpha = 0.8f),
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                            PlaybackCenterIndicatorPolicy.Indicator.BUFFERING ->
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(30.dp)
                                        .testTag("video_buffering_indicator"),
                                    strokeWidth = 2.5.dp,
                                )
                            PlaybackCenterIndicatorPolicy.Indicator.PLAY,
                            PlaybackCenterIndicatorPolicy.Indicator.REPLAY,
                            PlaybackCenterIndicatorPolicy.Indicator.PAUSE_FEEDBACK ->
                                if (!clearScreenEnabled) {
                                    val icon = when (centerIndicator) {
                                        PlaybackCenterIndicatorPolicy.Indicator.REPLAY ->
                                            Icons.Outlined.Replay
                                        PlaybackCenterIndicatorPolicy.Indicator.PAUSE_FEEDBACK ->
                                            Icons.Outlined.Pause
                                        else -> Icons.Outlined.PlayArrow
                                    }
                                    Icon(
                                        icon,
                                        contentDescription = when (centerIndicator) {
                                            PlaybackCenterIndicatorPolicy.Indicator.REPLAY ->
                                                "重新播放"
                                            PlaybackCenterIndicatorPolicy.Indicator.PAUSE_FEEDBACK ->
                                                "正在播放"
                                            else -> "继续播放"
                                        },
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.size(64.dp)
                                            .testTag("video_center_playback_indicator"),
                                    )
                                }
                            PlaybackCenterIndicatorPolicy.Indicator.NONE -> Unit
                        }
                    } else if (state.playbackError == null) {
                        // The next media item may already be prepared. Do not
                        // flash a spinner while the service swaps its MediaItem.
                        // Show un-dimmed destination artwork as the loading frame;
                        // only show progress after a noticeable loading delay.
                        if (detail.drama.cover.isNotBlank()) {
                            AsyncImage(
                                model = model.cover(detail.drama.cover),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        var showDelayedSpinner by remember(detail.drama.id, state.episodeIndex) {
                            mutableStateOf(false)
                        }
                        LaunchedEffect(detail.drama.id, state.episodeIndex) {
                            delay(450)
                            showDelayedSpinner = true
                        }
                        if (showDelayedSpinner) {
                            Column(
                                modifier = Modifier.testTag("video_source_loading_feedback"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                if (state.nextDramaTransition ==
                                    NextDramaTransition.PREPARING_VIDEO) {
                                    Text(
                                        "正在准备下一部视频…",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
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
                            OutlinedButton(
                                onClick = {
                                    if (state.feedMode) model.refreshFailedReels()
                                    else model.retryPlayback()
                                },
                            ) {
                                Icon(Icons.Outlined.Refresh, null)
                                Text(if (state.feedMode) "刷新推荐列表" else "重新尝试")
                            }
                            Text(if (state.feedMode)
                                    "继续上滑也会请求新的推荐，不再循环播放失效列表"
                                 else "可重新尝试或切换其他剧集",
                                color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                } else if (!pagerRecenterPending) {
                    val preview = if (state.feedMode) {
                        if (page < activePage) previousFeedDrama else nextFeedDrama
                    } else null
                    // Adjacent pages display the original cover at full brightness.
                    // Never imply the video is actually playing before the new item renders.
                    if (preview != null && preview.cover.isNotBlank()) {
                        AsyncImage(
                            model = model.cover(preview.cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
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

        // Keep the actual video unobscured. Text and buttons provide their own
        // contrast treatment instead of dimming the entire top/bottom frame.

        if ((hiddenChromeMask and 32) == 0 && state.danmakuEnabled &&
            selected != null && !showComments && !showEpisodes) {
            TimedDanmakuOverlay(
                items = state.danmakuItems.takeIf {
                    state.danmakuForVid == selected.vid
                }.orEmpty(),
                expectedVid = selected.vid,
                playback = playback,
                speedPreset = state.danmakuSpeed,
                tracks = state.danmakuDensity,
                fontSizeSp = state.danmakuFontSize,
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(top = 58.dp)
            )
        }

        // Exactly one persistent overlay. Its *top navigation* fades
        // between feed and full-series mode; title, sidebar, status and dock
        // remain stable above the unchanged Media3 video SurfaceView.
        ReelScreenshotOverlay(
            state = state,
            detail = detail,
            episode = selected,
            onPlaybackSettings = { focusedPlaybackSetting = it },
            onDownload = downloadSelected,
            onOpenAccountDrawer = {
                accountDrawerScope.launch { accountDrawerState.open() }
            },
            onPictureInPicture = if (
                VideoPiPPolicy.showManualButton(manualPipButtonVisible)
            ) ({
                selected?.vid?.let { vid ->
                    val activity = context as? MainActivity
                    if (activity?.enterVideoPictureInPicture(vid) != true) {
                        Toast.makeText(context,
                            "画中画未启动：请检查系统权限和视频播放状态",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }) else null,
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
            onRetryDanmaku = {
                model.retryDanmaku(playback.positionMs, playback.durationMs)
            },
            onComic = { model.setReelsMode("comic") },
            onReal = { model.setReelsMode("real") },
            onRecommend = { model.setReelsMode("all") },
            onSearch = { searchOverlayOpen = true },
            showTopButtons = (hiddenChromeMask and 1) == 0,
            showSideButtons = (hiddenChromeMask and 2) == 0,
            showTitle = (hiddenChromeMask and 4) == 0,
            showTags = (hiddenChromeMask and 8) == 0,
            showDescription = (hiddenChromeMask and 16) == 0,
            showDanmakuControls = (hiddenChromeMask and 32) == 0,
        )
        }
        ReelBottomDock(
            detail = detail,
            episode = selected,
            playback = playback,
            onEpisodes = {
                if (state.feedMode) onEnterSeries(state.episodeIndex)
                else showEpisodes = true
            },
        )
        // Remaining space is outside the video. Never enlarge the frame to
        // conceal it: that would require cropping, stretching or letterboxing.
        if (measuredPortraitHeight != null) Spacer(Modifier.weight(1f))
        }
    }
    // The search landing slides over (not instead of) the reel video.
    // Its Back arrow dismisses the overlay without navigating away or
    // restarting playback. Search submission follows the home search flow.
    RightSlidePage(
        visible = searchOverlayOpen && state.feedMode,
        modifier = Modifier.align(Alignment.CenterEnd),
        tag = "reel_search_slide_page",
    ) {
        SearchLandingScreen(
            state = state,
            model = model,
            onClose = { searchOverlayOpen = false },
            onResults = { /* Results render in this slide-over page. */ },
            onOpenDrama = { drama ->
                searchOverlayOpen = false
                model.openFromFeedSelection(drama)
            },
        )
    }
    } // Overlay container
    } // ReelModeSlidePlayer content
    } // ModalNavigationDrawer content

    if (clearScreenMenuOpen) AlertDialog(
        onDismissRequest = { clearScreenMenuOpen = false },
        title = { Text("清屏设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("选择要从视频画面上隐藏的内容",
                    color = HongguoColors.muted, fontSize = 12.sp)
                listOf(
                    Triple(1, "顶部按钮", "推荐分类、返回、画中画和设置"),
                    Triple(2, "右侧按钮", "收藏、评论、喜欢、播放量"),
                    Triple(4, "剧名", "剧名和进入详情的入口"),
                    Triple(8, "演员与标签", "分类、演员及 AI 提示"),
                    Triple(16, "简介文案", "视频下方的剧情介绍"),
                    Triple(32, "弹幕", "画面弹幕与弹幕开关"),
                ).forEach { (bit, label, description) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                setHiddenChromeMask(hiddenChromeMask xor bit)
                            }.padding(vertical = 2.dp)
                            .testTag("clear_screen_option_$bit"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = (hiddenChromeMask and bit) != 0,
                            onCheckedChange = { hide ->
                                setHiddenChromeMask(
                                    if (hide) hiddenChromeMask or bit
                                    else hiddenChromeMask and bit.inv()
                                )
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(label, color = HongguoColors.ink, fontSize = 14.sp)
                            Text(description, color = HongguoColors.muted,
                                fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
                Text("进度条、选集、底部导航始终保留。",
                    color = HongguoColors.muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { clearScreenMenuOpen = false },
                modifier = Modifier.testTag("player_clear_screen_toggle"),
            ) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = { setHiddenChromeMask(
                if (hiddenChromeMask == 63) 0 else 63
            ) }) { Text(if (clearScreenEnabled) "全部显示" else "全部清屏") }
        },
        modifier = Modifier.testTag("player_clear_screen_dialog"),
    )

    focusedPlaybackSetting?.let { currentSetting ->
        PlaybackExperienceSheet(
            state = state,
            model = model,
            speed = speed,
            fillVideo = fillVideo,
            autoPipEnabled = autoPipEnabled,
            manualPipButtonVisible = manualPipButtonVisible,
            pipSystemStatus = pipSystemStatus,
            onAutoPipEnabled = onAutoPipEnabled,
            onManualPipButtonVisible = onManualPipButtonVisible,
            onOpenPipSettings = {
                if ((context as? MainActivity)?.openVideoPictureInPictureSettings() != true) {
                    Toast.makeText(context, "无法打开系统画中画设置",
                        Toast.LENGTH_SHORT).show()
                }
            },
            onTestManualPip = {
                val vid = selected?.vid
                if (vid == null ||
                    (context as? MainActivity)?.enterVideoPictureInPicture(vid) != true) {
                    Toast.makeText(context,
                        "未能进入小窗：请确认视频正在播放且系统允许画中画",
                        Toast.LENGTH_LONG).show()
                } else {
                    focusedPlaybackSetting = null
                }
            },
            onDismiss = { focusedPlaybackSetting = null },
            onSpeed = {
                speed = it
                model.setDefaultPlaybackSpeed(it)
            },
            onFillVideo = { fillVideo = it },
            onQuality = {
                model.setQualityLimit(it, playback.positionMs, playback.durationMs)
                focusedPlaybackSetting = null
            },
            onDownload = downloadSelected,
            initialSection = currentSetting,
        )
    }

    if (showComments) HongguoPlayerSheet(
        onDismissRequest = { showComments = false },
        modifier = Modifier.testTag("reel_comments_sheet"),
    ) {
        CommentsSheet(state, model)
    }

    if (showEpisodes) ReelEpisodeSelectionSheet(
        detail = detail,
        state = state,
        model = model,
        onDismiss = { showEpisodes = false },
        onDownloadFive = {
            if (state.backend.isBlank()) {
                downloadBatch(5)
            } else {
                val from = detail.episodes[state.episodeIndex].index
                val to = detail.episodes.drop(state.episodeIndex).take(5)
                    .lastOrNull()?.index ?: from
                model.download(from, to)
                showEpisodes = false
            }
        },
        onSelectEpisode = { index ->
            if (state.feedMode) onEnterSeries(index)
            else model.play(index)
            showEpisodes = false
        },
        onRelatedDrama = { work ->
            showEpisodes = false
            model.openRelatedDrama(work.drama)
        },
    )
}


private fun formatClock(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(sec / 60, sec % 60)
}
