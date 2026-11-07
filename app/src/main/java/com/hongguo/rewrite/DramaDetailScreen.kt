package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/**
 * Reuses main's DetailScreen information architecture, with rewrite's existing
 * cloud/local collections, related-work source and episode playback lifecycle.
 * Reel swiping never routes through this screen.
 */
@Composable
internal fun DramaDetailScreen(state: AppState, model: DramaViewModel) {
    val detail = state.detail ?: return
    val drama = detail.drama
    val account by model.account.state.collectAsStateWithLifecycle()
    val localSaved = state.favorites.any { it.drama.id == drama.id }
    val cloudSaved = account.favorites.any { it.series.id == drama.id }
    var expanded by remember(drama.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(HongguoColors.background)
        .testTag("drama_detail_screen")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::backFromDramaDetails) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                    tint = HongguoColors.ink)
            }
            Text("剧集详情", fontSize = 20.sp, color = HongguoColors.ink,
                fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(model = model.cover(drama.cover), contentDescription = drama.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(112.dp).height(158.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(HongguoColors.accentSoft))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(drama.title, fontWeight = FontWeight.Bold, fontSize = 23.sp,
                            lineHeight = 31.sp, color = HongguoColors.ink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("共 ${detail.episodes.size} 集", color = HongguoColors.muted,
                            fontSize = 14.sp)
                        drama.completionStatus?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = HongguoColors.accent, fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailActionTile(
                        title = if (localSaved) "已本机收藏" else "本机收藏",
                        subtitle = "仅存这台设备",
                        modifier = Modifier.weight(1f),
                        onClick = { model.toggleFavoriteForDrama(drama) }) {
                        Icon(Icons.Outlined.BookmarkBorder, null,
                            tint = HongguoColors.ink)
                    }
                    DetailActionTile(
                        title = if (cloudSaved) "已账号收藏" else "账号收藏",
                        subtitle = if (account.profile == null) "登录后同步"
                            else "同步红果账号",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (account.profile == null) model.show(Modal.ACCOUNT)
                            else model.account.cloudFavorite(drama.id, !cloudSaved)
                        }) {
                        Icon(Icons.Outlined.CloudQueue, null, tint = HongguoColors.ink)
                    }
                }
            }
            if (drama.synopsis.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailSectionTitle("故事简介")
                    Text(drama.synopsis,
                        color = HongguoColors.muted, fontSize = 15.sp, lineHeight = 25.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded })
                    if (drama.synopsis.length > 120) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开全文",
                                color = HongguoColors.accent)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DetailSectionTitle("相关作品")
                        Spacer(Modifier.weight(1f))
                        if (state.relatedLoading) CircularProgressIndicator(
                            Modifier.size(18.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = { model.loadRelated(drama.id) }) {
                            Text("刷新", fontSize = 12.sp, color = HongguoColors.accent)
                        }
                    }
                    if (state.relatedWorks.isEmpty() && !state.relatedLoading) {
                        Text(state.relatedError ?: "暂无相关作品",
                            color = HongguoColors.muted, fontSize = 12.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.relatedWorks.take(18).forEach { work ->
                            Column(Modifier.width(104.dp)
                                .clickable(enabled = !work.upcoming) {
                                    model.open(work.drama)
                                }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                AsyncImage(model = model.cover(work.drama.cover),
                                    contentDescription = work.drama.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(138.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(HongguoColors.accentSoft))
                                Text(work.season.ifBlank { if (work.upcoming) "待上线" else "相关作品" },
                                    fontSize = 11.sp, color = HongguoColors.accent,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(work.drama.title, fontSize = 12.sp,
                                    color = HongguoColors.ink, maxLines = 2,
                                    lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
                                if (work.drama.total > 0) Text("全 ${work.drama.total} 集",
                                    fontSize = 11.sp, color = HongguoColors.muted)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DetailSectionTitle("选集")
                    Spacer(Modifier.weight(1f))
                    Text(if (state.autoPlayNext) "自动连播已开启" else "自动连播已关闭",
                        fontSize = 12.sp, color = HongguoColors.muted)
                }
            }
            items((detail.episodes.size + 4) / 5) { row ->
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(5) { column ->
                        val index = row * 5 + column
                        val episode = detail.episodes.getOrNull(index)
                        if (episode == null) Spacer(Modifier.weight(1f))
                        else {
                            val active = index == state.episodeIndex
                            Surface(
                                onClick = { model.play(index) },
                                modifier = Modifier.weight(1f).heightIn(min = 49.dp)
                                    .testTag("detail_episode_$index"),
                                shape = RoundedCornerShape(12.dp),
                                color = if (active) HongguoColors.accent
                                    else HongguoColors.surface,
                            ) {
                                Box(Modifier.padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text("${episode.index}", fontSize = 16.sp,
                                        color = if (active) Color.White else HongguoColors.ink)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (detail.episodes.isNotEmpty()) {
            Button(onClick = { model.play(state.episodeIndex.coerceIn(0,
                    detail.episodes.lastIndex)) },
                modifier = Modifier.fillMaxWidth().padding(
                    start = 18.dp, end = 18.dp, top = 9.dp, bottom = 14.dp)
                    .height(55.dp).testTag("detail_play_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HongguoColors.accent)) {
                Icon(Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("播放第 ${detail.episodes[state.episodeIndex.coerceIn(
                    0, detail.episodes.lastIndex)].index} 集",
                    fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DetailSectionTitle(label: String) {
    Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        color = HongguoColors.ink)
}

@Composable
private fun DetailActionTile(
    title: String, subtitle: String, modifier: Modifier = Modifier,
    onClick: () -> Unit, icon: @Composable () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 68.dp),
        color = HongguoColors.accentSoft, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 15.sp, color = HongguoColors.ink,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, fontSize = 11.sp, color = HongguoColors.muted,
                    maxLines = 1)
            }
        }
    }
}
