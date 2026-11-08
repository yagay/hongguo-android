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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
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
    val metadata = state.openHongguoMetadata?.takeIf { it.seriesId == drama.id }
    val tags = (drama.categories + metadata.orEmptyTags()).distinct().take(12)
    val cast = (drama.actors + metadata.orEmptyActors()).distinct().take(20)

    LaunchedEffect(drama.id, state.episodeIndex) {
        model.ensureCommentsLoaded()
    }

    Column(Modifier.fillMaxSize().background(HongguoColors.background)
        .testTag("drama_detail_screen")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(312.dp)
                    .background(HongguoColors.playerBackground)
                    .testTag("detail_cover_hero")) {
                    AsyncImage(
                        model = model.cover(drama.cover),
                        contentDescription = drama.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            alpha = 0.55f
                        },
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(
                            Color(0x55000000),
                            Color(0x99000000),
                            HongguoColors.playerBackground,
                        ),
                    )))
                    IconButton(
                        onClick = model::backFromDramaDetails,
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(start = 6.dp, top = 5.dp)
                            .testTag("detail_back"),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                            tint = Color.White)
                    }
                    Row(
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth().padding(horizontal = 18.dp, vertical = 19.dp),
                        horizontalArrangement = Arrangement.spacedBy(15.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AsyncImage(
                            model = model.cover(drama.cover),
                            contentDescription = drama.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(110.dp).height(154.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF292929))
                                .testTag("detail_poster"),
                        )
                        Column(
                            Modifier.weight(1f).padding(bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(
                                drama.title, fontWeight = FontWeight.Bold,
                                fontSize = 23.sp, lineHeight = 30.sp, color = Color.White,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                            val status = listOfNotNull(
                                "全${detail.episodes.size}集",
                                (drama.completionStatus ?: metadata?.statusText)
                                    ?.takeIf(String::isNotBlank),
                                metadata?.season?.let { "第${it}季" },
                            )
                            Text(status.joinToString(" · "), color = Color(0xFFEEEEEE),
                                fontSize = 12.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
            if (tags.isNotEmpty()) item {
                Row(Modifier.padding(horizontal = 18.dp),Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        Surface(color = HongguoColors.accentSoft,
                            shape = RoundedCornerShape(8.dp)) {
                            Text(tag, Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                color = HongguoColors.ink, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (drama.synopsis.isNotBlank()) item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailSectionTitle("剧情简介")
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
            if (cast.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailSectionTitle("演员")
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        cast.forEach { actor ->
                            Column(Modifier.width(76.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                // Only actor names are present in the series info row.
                                // Never invent a portrait or a profile ID.
                                Box(Modifier.size(62.dp)
                                    .background(HongguoColors.accentSoft, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(actor.take(1), fontSize = 25.sp,
                                        color = HongguoColors.accent)
                                }
                                Text(actor, color = HongguoColors.ink,
                                    maxLines = 1, fontSize = 12.sp,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            metadata?.score?.let { score -> item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailSectionTitle("剧集评分")
                    Surface(color = HongguoColors.accentSoft,
                        shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(17.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(score, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                                color = HongguoColors.accent)
                            Text("官方详情评分", fontSize = 13.sp,
                                color = HongguoColors.muted)
                        }
                    }
                }
            } }
            metadata?.novel?.let { novel -> item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailSectionTitle("原著小说")
                    Surface(color = HongguoColors.surface,
                        shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (novel.cover.isNotBlank())
                                AsyncImage(model = model.cover(novel.cover),
                                    contentDescription = novel.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(55.dp).height(75.dp)
                                        .clip(RoundedCornerShape(9.dp)))
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(novel.title.ifBlank { "原著小说" },
                                    color = HongguoColors.ink,
                                    fontWeight = FontWeight.SemiBold)
                                val facts = listOfNotNull(
                                    novel.status.takeIf(String::isNotBlank),
                                    novel.reads?.let { "${it} 次阅读" },
                                    novel.tags.take(2).joinToString(" · ")
                                        .takeIf(String::isNotBlank),
                                )
                                if (facts.isNotEmpty()) Text(facts.joinToString(" · "),
                                    fontSize = 12.sp, color = HongguoColors.muted)
                            }
                        }
                    }
                    Text("仅展示已确认的原著关联；暂不使用未验证的小说跳转地址。",
                        fontSize = 11.sp, color = HongguoColors.muted)
                }
            } }
            if (state.openHongguoSeasons.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailSectionTitle("系列剧 · 其他季")
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.openHongguoSeasons.forEach { season ->
                            Column(Modifier.width(104.dp)
                                .clickable { model.open(season) },
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(model = model.cover(season.cover),
                                    contentDescription = season.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(104.dp).height(138.dp)
                                        .clip(RoundedCornerShape(12.dp)))
                                Text(season.title, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = HongguoColors.ink)
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Column(Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        DetailSectionTitle("剧评")
                        Spacer(Modifier.weight(1f))
                        val count = state.commentsTotal
                        if (count != null) Text("${count} 条",
                            fontSize = 12.sp, color = HongguoColors.muted)
                    }
                    val comments = state.comments.take(3)
                    when {
                        state.commentsLoading && comments.isEmpty() ->
                            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        comments.isEmpty() ->
                            Text(state.commentsError ?: "暂无可显示的剧评",
                                color = HongguoColors.muted, fontSize = 13.sp)
                        else -> comments.forEach { comment ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(comment.author.ifBlank { "用户" },
                                    color = HongguoColors.ink, fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp)
                                Text(comment.content, color = HongguoColors.muted,
                                    fontSize = 13.sp, lineHeight = 20.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Text("评论来自当前播放分集", fontSize = 11.sp,
                        color = HongguoColors.muted)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailActionTile(
                        title = if (localSaved) "已收藏" else "收藏",
                        subtitle = "本机片单",
                        modifier = Modifier.weight(1f),
                        onClick = { model.toggleFavoriteForDrama(drama) }) {
                        Icon(Icons.Outlined.BookmarkBorder, null,
                            tint = HongguoColors.ink)
                    }
                    DetailActionTile(
                        title = if (cloudSaved) "已追剧" else "追剧",
                        subtitle = if (account.profile == null) "登录后同步"
                            else "账号同步",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (account.profile == null) model.show(Modal.ACCOUNT)
                            else model.account.cloudFavorite(drama.id, !cloudSaved)
                        }) {
                        Icon(Icons.Outlined.CloudQueue, null,
                            tint = HongguoColors.ink)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    DetailSectionTitle("选集")
                    Spacer(Modifier.weight(1f))
                    Text(if (state.autoPlayNext) "自动连播已开启" else "自动连播已关闭",
                        fontSize = 12.sp, color = HongguoColors.muted)
                }
            }
            items((detail.episodes.size + 4) / 5) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
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

private fun OpenHongguoMetadata?.orEmptyTags(): List<String> = this?.tags.orEmpty()
private fun OpenHongguoMetadata?.orEmptyActors(): List<String> = this?.actors.orEmpty()
