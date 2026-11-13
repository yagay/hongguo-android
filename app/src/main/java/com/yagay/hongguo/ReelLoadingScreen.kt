package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Non-blocking feed startup/recovery.
 *
 * The same ReelFeedHeader is mounted during loading and playback. Loading
 * status is drawn directly on the player viewport without a card/dialog.
 * It retains the same feed header and controls while the media is being resolved.
 */
@Composable
internal fun ReelLoadingScreen(
    state: AppState,
    model: DramaViewModel,
    drama: Drama?,
    coverUrl: String?,
    loading: Boolean,
    preparingSeries: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onSearch: () -> Unit,
    exitLabel: String = "退出刷短剧",
) {
    val drawerState = rememberTransientReelDrawerState()
    val drawerScope = rememberCoroutineScope()
    val stage = if (drama != null) "episode" else "recommendation"
    var elapsedSeconds by remember(stage, drama?.id, loading) { mutableIntStateOf(0) }
    LaunchedEffect(stage, drama?.id, loading) {
        while (loading) {
            delay(1_000)
            elapsedSeconds++
        }
    }
    val failed = error != null && !loading
    val title = when {
        failed -> "加载失败"
        state.nextDramaTransition == NextDramaTransition.FINDING_RECOMMENDATION ->
            "本剧已播完 · 正在获取下一部"
        state.nextDramaTransition == NextDramaTransition.PREPARING_VIDEO ->
            "正在准备下一部视频"
        drama != null -> "正在准备播放"
        else -> "正在获取短剧"
    }
    val description = when {
        failed -> "网络或内容接口暂时不可用"
        state.nextDramaTransition != NextDramaTransition.NONE ->
            "下一部准备好后会自动开始播放"
        preparingSeries && drama != null -> "正在读取这部短剧的分集信息"
        drama != null -> "正在连接视频资源"
        else -> "正在读取官方推荐"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = ReelDrawerPolicy.gesturesEnabled(
            drawerState.isOpen, state.feedMode, searchOverlayOpen = false
        ),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width((LocalConfiguration.current.screenWidthDp * 0.80f).dp),
                drawerContainerColor = HongguoColors.background,
            ) {
                ReelAccountDrawer(
                    state = state,
                    model = model,
                    onClose = { drawerScope.launch { drawerState.close() } },
                    onAccount = { model.show(Modal.ACCOUNT) },
                    onHistoryItem = { model.openFromFeedSelection(it) },
                    onReservations = { model.show(Modal.ACCOUNT) },
                    onDownloads = {
                        model.updateDownloads()
                        model.show(Modal.DOWNLOAD)
                    },
                    onShare = { model.show(Modal.SHARE) },
                    onSettings = { model.show(Modal.SETTINGS) },
                    onBrowseHome = { model.closePlayer() },
                    onOfflineEpisode = { model.openOfflineEpisode(it) },
                )
            }
        },
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(HongguoColors.playerBackground)
                .testTag("reel_loading_screen"),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                        .testTag("reel_loading_background_cover"),
                )
                Box(Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)))
            }
            if (state.feedMode) {
                ReelFeedHeader(
                    reelsMode = state.reelsMode,
                    onComic = { model.setReelsMode("comic") },
                    onReal = { model.setReelsMode("real") },
                    onRecommend = { model.setReelsMode("all") },
                    onOpenAccountDrawer = {
                        drawerScope.launch { drawerState.open() }
                    },
                    onSearch = onSearch,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopStart)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HongguoPlayerTopAction(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        label = "返回",
                        onClick = onExit,
                        iconSize = 26.dp,
                    )
                    Text(
                        "正在准备播放",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }

            // Inline status belongs to the player viewport, not a popup.
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .testTag("reel_loading_inline_status"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (!failed) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = Color.White,
                        strokeWidth = 2.6.dp,
                    )
                } else if (failed) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Text(title,
                    color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center)
                Text(description,
                    color = Color(0xFFE3E3E3), fontSize = 12.sp,
                    lineHeight = 17.sp, textAlign = TextAlign.Center)
                if (drama != null) {
                    Text(drama.title, color = Color(0xFFE4E4E4),
                        fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                }
                if (failed) {
                    Text(error.orEmpty(),
                        color = Color(0xFFFFB09A), fontSize = 12.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                } else if (loading && elapsedSeconds >= 10) {
                    Text("网络响应较慢，可以重新获取",
                        color = Color(0xFFFFD0C0), fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                }
                if (failed || (loading && elapsedSeconds >= 8)) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("reel_loading_retry"),
                    ) {
                        Text(if (failed) "重试" else "重新获取",
                            color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Source badge stays near the existing player header, not inside
            // the loading text column, so switching to playback does not jump.
            if (state.feedMode) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 62.dp)) {
                    ReelRecommendationHint(status = state.feedRecommendationStatus)
                }
            }
            TextButton(
                onClick = onExit,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
                    .testTag("reel_loading_exit"),
            ) {
                Text(exitLabel, color = Color(0xFFE1E1E1), fontSize = 12.sp)
            }

        }
    }
}
