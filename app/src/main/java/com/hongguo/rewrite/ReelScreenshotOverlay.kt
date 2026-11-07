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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
    playback: ReelPlaybackControls,
    speed: Float,
    onBack: () -> Unit,
    onFavorite: () -> Unit,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onDanmaku: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onEpisodes: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val white = Color.White
    val muted = Color(0xFFD5D5D5)
    val saved = state.favorites.any { it.drama.id == detail.drama.id }
    val liked = detail.drama.id in state.likedDramaIds
    val inHistory = state.library.any { it.drama.id == detail.drama.id }
    var moreExpanded by remember { mutableStateOf(false) }
    var synopsisExpanded by rememberSaveable(detail.drama.id) { mutableStateOf(false) }
    var seeking by remember(episode?.vid) { mutableStateOf<Float?>(null) }

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
            Box {
                IconButton(
                    onClick = { moreExpanded = true },
                    modifier = Modifier.size(44.dp)
                        .background(Color.Black.copy(alpha = 0.25f), CircleShape)
                        .testTag("reel_more"),
                ) {
                    Icon(Icons.Outlined.MoreVert, "播放选项",
                        tint = white, modifier = Modifier.size(23.dp))
                }
                DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false },
                    containerColor = HongguoColors.playerSheet,
                ) {
                    DropdownMenuItem(
                        text = { Text("下载本集", color = white) },
                        leadingIcon = { Icon(Icons.Outlined.Download, null, tint = white) },
                        onClick = { moreExpanded = false; onDownload() },
                    )
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { value ->
                        DropdownMenuItem(
                            text = { Text("播放速度 " + value + "×" +
                                (if (speed == value) "  ✓" else ""), color = white) },
                            onClick = { moreExpanded = false; onSpeed(value) },
                        )
                    }
                }
            }
        }

        // Reference screenshot action rail: bookmark, comment, heart, share.
        // Labels are truthful because public counts are not in DramaDetail.
        Column(
            Modifier.align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 111.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ReferenceAction(
                if (saved) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (saved) "已收藏" else "收藏",
                "reel_favorite", onFavorite,
                if (saved) Color(0xFFFFD27C) else white,
            )
            ReferenceAction(Icons.Outlined.ChatBubbleOutline, "评论",
                "reel_comments", onComments)
            ReferenceAction(
                if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                if (liked) "已喜欢" else "喜欢",
                "reel_like", onLike,
                if (liked) Color(0xFFFF6E72) else white,
            )
            ReferenceAction(Icons.Outlined.Share, "分享", "reel_share", onShare)
        }

        // Left caption area deliberately does not span beneath the action rail.
        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 15.dp, end = 90.dp, bottom = 109.dp),
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
                    Text(if (state.danmakuEnabled) "弹 · 开" else "弹 · 关",
                        color = white, fontSize = 11.sp)
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
                    .clickable { synopsisExpanded = !synopsisExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(detail.drama.title, color = white,
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    lineHeight = 23.sp,
                    maxLines = if (synopsisExpanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("reel_drama_title"))
                Icon(Icons.Outlined.KeyboardArrowRight, "展开简介",
                    tint = white, modifier = Modifier.size(20.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val count = detail.episodes.size
                if (count > 0) ReferenceChip("全 " + count + " 集")
                if (episode != null) ReferenceChip("第 " + episode.index + " 集")
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

        // Bottom dock follows the reference's CTA + orange watch-position line.
        // The line represents the actual Media3 position, never fake progress.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color(0xFF232325)),
        ) {
            Row(
                Modifier.fillMaxWidth().height(54.dp)
                    .clickable(onClick = onEpisodes)
                    .padding(start = 16.dp, end = 14.dp)
                    .testTag("watch_full_drama"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.PlayCircleOutline, contentDescription = null,
                    tint = white, modifier = Modifier.size(23.dp))
                Text(
                    "观看完整短剧 · 全 " + detail.episodes.size + " 集",
                    color = white,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "打开选集",
                    tint = white, modifier = Modifier.size(24.dp))
            }
            val duration = playback.durationMs
            val fraction = if (duration > 0) {
                (playback.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Slider(
                value = seeking ?: fraction,
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    if (duration > 0) {
                        seeking?.let { playback.seekTo((it * duration).toLong()) }
                    }
                    seeking = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = white,
                    activeTrackColor = HongguoColors.accent,
                    inactiveTrackColor = Color(0xFF535357),
                    disabledActiveTrackColor = HongguoColors.accent,
                    disabledInactiveTrackColor = Color(0xFF535357),
                ),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(24.dp).testTag("reel_seek"),
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun ReferenceAction(
    icon: ImageVector, label: String, tag: String,
    onClick: () -> Unit, tint: Color = Color.White,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier.width(63.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(47.dp).testTag(tag),
        ) {
            Icon(icon, contentDescription = label, tint = tint,
                modifier = Modifier.size(32.dp))
        }
        Text(label, fontSize = 12.sp, color = Color.White,
            maxLines = 1, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ReferenceChip(label: String) {
    Text(
        label, fontSize = 11.sp, color = Color.White,
        modifier = Modifier.background(
            Color(0xFF6E6E72).copy(alpha = 0.68f),
            RoundedCornerShape(6.dp),
        ).padding(horizontal = 7.dp, vertical = 5.dp),
    )
}
