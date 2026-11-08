package com.hongguo.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Screenshot-aligned vertical drama player chrome. Pure Compose UI, with no
 * network or media access and no fake public statistics or unverified tags.
 *
 * Full-screen video remains under the overlay; only real buttons and the seek
 * bar consume touches. VerticalPager continues to handle vertical swipes.
 */
@Composable
internal fun ReelScreenshotOverlay(
    state: AppState,
    detail: DramaDetail,
    episode: Episode?,
    onPlaybackSettings: (PlaybackQuickSetting) -> Unit,
    onDownload: () -> Unit,
    onOpenAccountDrawer: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onBack: () -> Unit,
    onDramaDetails: () -> Unit,
    onFavorite: () -> Unit,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onDanmaku: () -> Unit,
    onRetryDanmaku: () -> Unit,
    onComic: () -> Unit,
    onReal: () -> Unit,
    onRecommend: () -> Unit,
    onSearch: () -> Unit,
    showTopButtons: Boolean = true,
    showSideButtons: Boolean = true,
    showTitle: Boolean = true,
    showTags: Boolean = true,
    showDescription: Boolean = true,
    showDanmakuControls: Boolean = true,
) {
    val white = Color.White
    val muted = Color(0xFFD5D5D5)
    val saved = state.favorites.any { it.drama.id == detail.drama.id }
    val liked = detail.drama.id in state.likedDramaIds
    val aiAuthorStatement = state.openHongguoMetadata
        ?.takeIf { it.seriesId == detail.drama.id }?.authorAiStatement
    val social = state.socialStats?.takeIf {
        it.seriesId == detail.drama.id && state.socialForSeries == detail.drama.id
    }
    val favoriteMetric = social?.favoriteMetric()
    val likeMetric = episode?.vid?.let { social?.likeMetric(it) }
    val commentCount = state.commentsTotal
        ?: episode?.vid?.let { social?.episodes?.get(it)?.comments }
    var synopsisExpanded by rememberSaveable(detail.drama.id) { mutableStateOf(false) }
    var playerMenuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().testTag("reference_video_layout")) {
        // Feed and full-series navigation are alternative *headers*, not
        // different video pages. Only this row changes on mode switch; all
        // title, metrics, icons and playback controls keep their positions.
        val headerShiftPx = with(LocalDensity.current) { 8.dp.roundToPx() }
        if (showTopButtons) AnimatedContent(
            targetState = state.feedMode,
            modifier = Modifier.fillMaxWidth().height(56.dp)
                .align(Alignment.TopCenter)
                .testTag("feed_series_slide"),
            transitionSpec = {
                PlaybackPageSlide.chromeChange(
                    enteringSeries = !targetState,
                    shiftPx = headerShiftPx,
                )
            },
            label = "feed_series_header_fade",
        ) { feedChrome ->
        if (feedChrome) {
            // Official-inspired navigation: only Recommend and Real correspond to
            // live official reel feeds. Other tabs open real existing collections.
            // Keep the edge controls independent of tab count. The reference
            // screenshot places the menu/search near the edges while the
            // category labels form a compact, centred group, not weighted
            // columns stretched over the entire available width.
            val tabs = buildList {
                FeatureAvailability.followingAction()?.let { openFollowing ->
                    add(Triple("关注", false, openFollowing))
                }
                add(Triple("漫剧", state.reelsMode == "comic", onComic))
                add(Triple("真人剧", state.reelsMode == "real", onReal))
                add(Triple("推荐", state.reelsMode == "all", onRecommend))
            }
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(56.dp)
                    .padding(horizontal = 8.dp),
            ) {
                val tabWidth = minOf(60.dp, (maxWidth - 88.dp) / tabs.size)
                HongguoPlayerTopAction(
                    icon = Icons.Outlined.Menu,
                    label = "打开账号与隐私侧边栏",
                    onClick = onOpenAccountDrawer,
                    modifier = Modifier.align(Alignment.TopStart)
                        .testTag("reel_menu"),
                    iconSize = 23.dp,
                )
                Row(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .height(52.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    tabs.forEach { (label, active, action) ->
                        Column(
                            modifier = Modifier.width(tabWidth)
                                .clickable(onClick = action)
                                .padding(top = 3.dp)
                                .testTag("reel_tab_" + label),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(label,
                                color = if (active) white else Color(0xFFE3E3E3),
                                fontSize = 18.sp, lineHeight = 23.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Clip)
                            Box(Modifier.width(20.dp).height(2.dp)
                                .background(if (active) white else Color.Transparent,
                                    RoundedCornerShape(2.dp)))
                        }
                    }
                }
                IconButton(
                    onClick = onSearch,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .size(40.dp).testTag("reel_search"),
                ) {
                    Icon(Icons.Outlined.Search, "搜索短剧",
                        tint = white, modifier = Modifier.size(27.dp))
                }
            }
        } else {
            // Preserve the original icon sizes. Only the dark circular
            // background is reduced to 30dp; 40dp touch targets remain.
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HongguoPlayerTopAction(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    label = "返回",
                    onClick = onBack,
                    iconSize = 26.dp,
                )
                Spacer(Modifier.weight(1f))
                if (onPictureInPicture != null) {
                    HongguoPlayerTopAction(
                        icon = Icons.Outlined.PictureInPictureAlt,
                        label = "跨应用画中画",
                        onClick = onPictureInPicture,
                        modifier = Modifier.testTag("reel_system_pip"),
                    )
                }
                Box {
                    HongguoPlayerTopAction(
                        icon = Icons.Outlined.MoreVert,
                        label = "播放菜单",
                        onClick = { playerMenuOpen = true },
                        modifier = Modifier.testTag("reel_more"),
                    )
                    HongguoActionMenu(
                        expanded = playerMenuOpen,
                        onDismissRequest = { playerMenuOpen = false },
                    ) {
                        val sections = listOf(
                            Triple(PlaybackQuickSetting.VIDEO, "画面设置",
                                Icons.Outlined.AspectRatio),
                            Triple(PlaybackQuickSetting.SPEED, "倍速",
                                Icons.Outlined.Speed),
                            Triple(PlaybackQuickSetting.AUDIO, "音效",
                                Icons.Outlined.VolumeUp),
                            Triple(PlaybackQuickSetting.PREFERENCES, "播放偏好",
                                Icons.Outlined.Tune),
                            Triple(PlaybackQuickSetting.DANMAKU, "弹幕",
                                Icons.Outlined.Subtitles),
                            Triple(PlaybackQuickSetting.TIMER, "定时停止",
                                Icons.Outlined.Timer),
                        )
                        sections.forEach { (section, title, icon) ->
                            HongguoMenuAction(
                                label = title,
                                icon = icon,
                                onClick = {
                                    playerMenuOpen = false
                                    onPlaybackSettings(section)
                                },
                                modifier = Modifier.testTag(
                                    "player_menu_" + section.name.lowercase()),
                            )
                        }
                        HongguoMenuSeparator()
                        HongguoMenuAction(
                            label = "下载当前剧集",
                            icon = Icons.Outlined.Download,
                            onClick = {
                                playerMenuOpen = false
                                onDownload()
                            },
                            modifier = Modifier.testTag("download_current_episode"),
                        )
                    }
                }
            }
        }
        } // Header-only fade; the persistent video controls never animate offscreen

        // Public counts come from series-scoped website data and signed,
        // identity-validated episode detail. Local taps never increment them.
        // An unknown value must remain a label, never a fake zero.
        if (showSideButtons) Column(
            Modifier.align(Alignment.BottomEnd)
                .padding(end = 7.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ReferenceAction(
                if (saved) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (saved) "已收藏" else "收藏",
                "reel_favorite", onFavorite,
                if (saved) Color(0xFFFFD27C) else white,
                count = favoriteMetric?.first?.let(SocialCountFormatter::format)
                    ?: if (state.socialStatsLoading) "…" else null,
                scope = favoriteMetric?.second?.takeIf { it != "收藏" },
            )
            ReferenceAction(Icons.Outlined.ChatBubbleOutline, "评论",
                "reel_comments", onComments,
                count = commentCount?.let(SocialCountFormatter::format)
                    ?: if (state.commentsLoading) "…" else null)
            ReferenceAction(
                if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (liked) "已喜欢" else "喜欢",
                "reel_like", onLike,
                if (liked) Color(0xFFFF6E72) else white,
                count = likeMetric?.first?.let(SocialCountFormatter::format)
                    ?: if (state.socialStatsLoading) "…" else null,
                scope = likeMetric?.second?.takeIf { it == "全剧" },
            )
            // The provider's series_play_cnt is a series-wide view metric.
            // It is display-only and must not behave like a clickable action
            // or be inflated from local playback history.
            ReferenceMetric(
                icon = Icons.Outlined.Visibility,
                label = "全剧播放量",
                count = social?.playCount?.let(SocialCountFormatter::format)
                    ?: if (state.socialStatsLoading) "…" else null,
                tag = "reel_play_count",
            )
        }

        // Left caption area deliberately does not span beneath the action rail.
        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 15.dp, end = 90.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (showDanmakuControls) Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.background(
                        Color.Black.copy(alpha = 0.54f), RoundedCornerShape(9.dp))
                        .clickable(onClick = onDanmaku)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .testTag("reel_danmaku"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(Icons.Outlined.Subtitles, contentDescription = null,
                        tint = white, modifier = Modifier.size(14.dp))
                    Text(
                        when {
                            !state.danmakuEnabled -> "弹 · 关"
                            state.danmakuError != null -> "弹 · 异常"
                            state.danmakuLoading && state.danmakuItems.isEmpty() -> "弹 · 加载"
                            state.danmakuForVid == episode?.vid &&
                                state.danmakuLoadedUntilMs > state.danmakuWindowStartMs &&
                                state.danmakuItems.isEmpty() -> "弹 · 暂无"
                            else -> "弹 · 开"
                        },
                        color = white, fontSize = 11.sp,
                    )
                }
                if (state.danmakuEnabled && state.danmakuError != null) {
                    Text(
                        "重试弹幕",
                        color = Color(0xFFFFCAA9),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.54f),
                                RoundedCornerShape(9.dp))
                            .clickable(onClick = onRetryDanmaku)
                            .padding(horizontal = 9.dp, vertical = 7.dp)
                            .testTag("reel_danmaku_retry"),
                    )
                }

            }
            if (showTitle) Row(
                Modifier.fillMaxWidth()
                    .clickable(onClick = onDramaDetails)
                    .testTag("open_drama_details_from_title"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(detail.drama.title, color = white,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 23.sp,
                    maxLines = if (synopsisExpanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    // Use the text's natural width. Reserve extra room only
                    // for long titles, so the chevron stays beside the title
                    // rather than being pushed to the far edge of the row.
                    modifier = Modifier.weight(1f, fill = false)
                        .testTag("reel_drama_title"))
                Icon(Icons.Outlined.KeyboardArrowRight, "查看剧集详情",
                    tint = white, modifier = Modifier.size(20.dp))
            }
            val actorAndGenreTags = detail.drama.actors.take(2)
                .map { "演员 $it" } + detail.drama.categories.take(4)
            if (showTags && actorAndGenreTags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .testTag("reel_actor_genre_tags"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actorAndGenreTags.forEach { tag ->
                        Text(
                            tag, color = Color.White, fontSize = 10.sp,
                            lineHeight = 13.sp, maxLines = 1,
                            modifier = Modifier.background(
                                Color(0xFF343438).copy(alpha = 0.75f),
                                RoundedCornerShape(4.dp),
                            ).padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (showTags) aiAuthorStatement?.let { declaration ->
                Text(
                    declaration,
                    color = Color(0xFFF0F0F0),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.52f),
                            RoundedCornerShape(5.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("reel_ai_author_statement"),
                )
            }
            if (showDescription && detail.drama.synopsis.isNotBlank()) {
                Text(detail.drama.synopsis, color = muted, fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = if (synopsisExpanded) 7 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { synopsisExpanded = !synopsisExpanded }
                        .testTag("reel_synopsis"))
            }
        }


    }
}


/**
 * The CTA and seek control are OUTSIDE the video viewport, so they never
 * obscure burned-in subtitles or crop the final lines of video.
 */
@Composable
internal fun ReelBottomDock(
    detail: DramaDetail,
    episode: Episode?,
    playback: ReelPlaybackControls,
    onEpisodes: () -> Unit,
) {
    var seeking by remember(episode?.vid) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val fraction = if (duration > 0L)
        (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    else 0f
    // While dragging, the timestamp must preview the same position as the
    // seek line. The actual Media3 seek is still committed only on release.
    val displayedPositionMs = if (duration > 0L && seeking != null)
        (duration * seeking!!.coerceIn(0f, 1f)).toLong()
    else playback.positionMs

    Column(
        Modifier.fillMaxWidth().background(HongguoColors.playerChrome)
            .testTag("reel_outside_video_dock"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Keep the 2dp line and seek gestures, but reduce the empty
        // vertical gutter from 16dp to 12dp. The recovered height belongs
        // to the video viewport, not another overlay.
        Canvas(
            Modifier.fillMaxWidth().height(HongguoUi.playbackSeekTouchHeight)
                .pointerInput(duration, playback) {
                    detectTapGestures { point ->
                        if (duration > 0L && size.width > 0) {
                            val value = (point.x / size.width).coerceIn(0f, 1f)
                            playback.seekTo((duration * value).toLong())
                        }
                    }
                }
                .pointerInput(duration, playback) {
                    detectDragGestures(
                        onDragStart = { start ->
                            if (duration > 0L && size.width > 0)
                                seeking = (start.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            seeking?.let { position ->
                                if (duration > 0L) playback.seekTo((duration * position).toLong())
                            }
                            seeking = null
                        },
                        onDragCancel = { seeking = null },
                        onDrag = { change, _ ->
                            if (duration > 0L && size.width > 0) {
                                seeking = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                            change.consume()
                        },
                    )
                }
                .testTag("reel_seek"),
        ) {
            val y = size.height / 2f
            drawLine(
                color = HongguoColors.playerChromeTrack,
                start = Offset(0f, y), end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = HongguoColors.accent,
                start = Offset(0f, y),
                end = Offset(size.width * (seeking ?: fraction), y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .height(HongguoUi.playbackDockRowHeight)
                .testTag("player_dock_meta"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HongguoPlayerEpisodeEntry(
                label = DramaCompletion.episodeLabel(
                    total = detail.episodes.size,
                    status = detail.drama.completionStatus,
                    episodeIndex = episode?.index,
                ),
                onClick = onEpisodes,
                modifier = Modifier.weight(1f).testTag("watch_full_drama"),
            )
            HongguoPlayerTimeLabel(
                positionMs = displayedPositionMs,
                durationMs = duration,
                modifier = Modifier.padding(end = HongguoUi.pagePadding)
                    .testTag("reel_time_label"),
            )
        }
    }
}

@Composable
private fun ReferenceAction(
    icon: ImageVector, label: String, tag: String,
    onClick: () -> Unit, tint: Color = Color.White,
    count: String? = null,
    scope: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.width(58.dp),
    ) {
        Box(
            modifier = Modifier.size(43.dp).clickable(onClick = onClick).testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint,
                modifier = Modifier.size(29.dp))
        }
        Text(
            count ?: label, fontSize = 12.sp, lineHeight = 14.sp,
            color = Color.White, maxLines = 1, fontWeight = FontWeight.Medium,
        )
        if (count != null && scope != null) {
            Text(scope, fontSize = 9.sp, lineHeight = 11.sp,
                color = Color(0xFFE0E0E0), maxLines = 1)
        }
    }
}

/** Playback count is a non-interactive, real series-wide metric. */
@Composable
private fun ReferenceMetric(
    icon: ImageVector,
    label: String,
    count: String?,
    tag: String,
) {
    Column(
        modifier = Modifier.width(58.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White,
                modifier = Modifier.size(29.dp))
        }
        Text(
            count ?: "播放", fontSize = 12.sp, lineHeight = 14.sp,
            color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1,
        )
    }
}
