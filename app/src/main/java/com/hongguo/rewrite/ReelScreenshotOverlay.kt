package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
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
    onPlaybackSettings: () -> Unit,
    onBack: () -> Unit,
    onDramaDetails: () -> Unit,
    onFavorite: () -> Unit,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onDanmaku: () -> Unit,
    onRetryDanmaku: () -> Unit,
) {
    val white = Color.White
    val muted = Color(0xFFD5D5D5)
    val saved = state.favorites.any { it.drama.id == detail.drama.id }
    val liked = detail.drama.id in state.likedDramaIds
    val inHistory = state.library.any { it.drama.id == detail.drama.id }
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

    Box(Modifier.fillMaxSize().testTag("reference_video_layout")) {
        // Minimal header: no duplicate full-title row over the video.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack,
                modifier = Modifier.size(44.dp)
                    .background(Color.Black.copy(alpha = 0.25f), CircleShape)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                    tint = white, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onPlaybackSettings,
                modifier = Modifier.size(44.dp)
                    .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                    .testTag("reel_more"),
            ) {
                Icon(Icons.Outlined.MoreVert, "播放设置",
                    tint = white, modifier = Modifier.size(23.dp))
            }
        }

        // Public counts come from series-scoped website data and signed,
        // identity-validated episode detail. Local taps never increment them.
        // An unknown value must remain a label, never a fake zero.
        Column(
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
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.background(
                        Color.Black.copy(alpha = 0.54f), RoundedCornerShape(9.dp))
                        .clickable(onClick = onDanmaku)
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                        .testTag("reel_danmaku"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.Subtitles, contentDescription = null,
                        tint = white, modifier = Modifier.size(15.dp))
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
                if (inHistory) {
                    Text(
                        "◉ 最近在看",
                        color = white, fontSize = 11.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.54f),
                                RoundedCornerShape(9.dp))
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                }
            }
            Row(
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
                    modifier = Modifier.weight(1f).testTag("reel_drama_title"))
                Icon(Icons.Outlined.KeyboardArrowRight, "查看剧集详情",
                    tint = white, modifier = Modifier.size(20.dp))
            }
            val actorAndGenreTags = detail.drama.actors.take(2)
                .map { "演员 $it" } + detail.drama.categories.take(4)
            if (actorAndGenreTags.isNotEmpty()) {
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
            aiAuthorStatement?.let { declaration ->
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
            if (detail.drama.synopsis.isNotBlank()) {
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

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF232325))
            .testTag("reel_outside_video_dock"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // A truly thin 2dp progress LINE, not the full Material Slider thumb.
        // 16dp invisible hit target keeps both taps and drag-to-seek usable.
        Canvas(
            Modifier.fillMaxWidth().height(16.dp)
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
                color = Color(0xFF66666B),
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
            Modifier.fillMaxWidth().height(34.dp)
                .clickable(onClick = onEpisodes)
                .padding(start = 10.dp, end = 8.dp)
                .testTag("watch_full_drama"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                DramaCompletion.episodeLabel(
                    total = detail.episodes.size,
                    status = detail.drama.completionStatus,
                    episodeIndex = episode?.index,
                ),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("reel_episode_status"),
            )
            Text(
                PlaybackTimeFormatter.label(playback.positionMs, duration),
                color = Color(0xFFE2E1E5),
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.testTag("reel_time_label"),
            )
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = "打开选集",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
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
