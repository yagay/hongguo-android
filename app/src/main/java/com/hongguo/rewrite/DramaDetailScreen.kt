package com.hongguo.rewrite

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.util.Locale

// Deliberately local to the detail page: the rest of the app keeps its selected theme.
private val DetailBackground = Color(0xFF472613)
private val DetailTop = Color(0xFF6A4939)
private val DetailCard = Color(0xFF66432F)
private val DetailCardLight = Color(0xFF765340)
private val DetailText = Color(0xFFF9F3ED)
private val DetailMuted = Color(0xFFC0A79A)
private val DetailChip = Color(0xFF755544)

@Composable
internal fun DramaDetailScreen(state: AppState, model: DramaViewModel) {
    val detail = state.detail ?: return
    val drama = detail.drama
    val context = LocalContext.current
    val account by model.account.state.collectAsStateWithLifecycle()
    val locallySaved = state.favorites.any { it.drama.id == drama.id }
    val cloudSaved = account.favorites.any { it.series.id == drama.id }
    val verified = state.publicDetail?.takeIf { it.seriesId == drama.id }
    val metadata = state.openHongguoMetadata?.takeIf { it.seriesId == drama.id }
    val cast = verified?.cast.orEmpty().ifEmpty {
        (drama.actors + metadata?.actors.orEmpty()).distinct()
            .take(20).map { PublicCastMember(it) }
    }
    val tags = (drama.categories + metadata?.tags.orEmpty()).distinct().take(12)
    var expanded by remember(drama.id) { mutableStateOf(false) }
    var showEpisodes by remember(drama.id) { mutableStateOf(false) }
    var showAllReviews by remember(drama.id) { mutableStateOf(false) }
    var showMenu by remember(drama.id) { mutableStateOf(false) }

    val shareDrama: () -> Unit = {
        val message = "${drama.title} https://hongguoduanju.com/player/${drama.id}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(intent, "分享短剧"))
    }

    Column(Modifier.fillMaxSize().background(DetailBackground)
        .testTag("drama_detail_screen")) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.verticalGradient(listOf(DetailTop, DetailBackground)))
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(62.dp)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = model::backFromDramaDetails,
                            modifier = Modifier.testTag("detail_back"),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                                tint = DetailText)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = shareDrama) {
                            Icon(Icons.Outlined.Share, "分享", tint = DetailText)
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, "更多", tint = DetailText)
                            }
                            DropdownMenu(expanded = showMenu,
                                onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("播放当前集") },
                                    onClick = {
                                        showMenu = false
                                        model.play(state.episodeIndex.coerceIn(
                                            0, detail.episodes.lastIndex.coerceAtLeast(0)))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (cloudSaved) "取消账号追剧" else "账号追剧") },
                                    onClick = {
                                        showMenu = false
                                        if (account.profile == null) model.show(Modal.ACCOUNT)
                                        else model.account.cloudFavorite(drama.id, !cloudSaved)
                                    },
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AsyncImage(
                            model = model.cover(drama.cover),
                            contentDescription = drama.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(91.dp).height(130.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DetailCard)
                                .testTag("detail_poster"),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(drama.title, color = DetailText,
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                lineHeight = 26.sp, maxLines = 3,
                                overflow = TextOverflow.Ellipsis)
                            Text("全${detail.episodes.size}集",
                                color = DetailMuted, fontSize = 13.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                tags.take(6).forEach { tag ->
                                    Surface(shape = RoundedCornerShape(6.dp),
                                        color = DetailChip) {
                                        Text(tag + " ›", Modifier.padding(
                                            horizontal = 7.dp, vertical = 4.dp),
                                            fontSize = 11.sp, color = DetailMuted,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (drama.synopsis.isNotBlank()) item {
                Column(Modifier.padding(horizontal = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DetailHeader("剧情简介")
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom) {
                        Text(
                            drama.synopsis,
                            color = DetailMuted, fontSize = 13.sp,
                            lineHeight = 22.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                                .clickable { expanded = !expanded }
                                .testTag("detail_synopsis"),
                        )
                        if (drama.synopsis.length > 40)
                            Text(
                                if (expanded) "收起" else "展开",
                                modifier = Modifier.clickable { expanded = !expanded }
                                    .padding(bottom = 2.dp),
                                color = Color(0xFF91B6DB), fontSize = 12.sp,
                            )
                    }
                }
            }

            if (cast.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cast.forEach { actor ->
                        Column(Modifier.width(64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(contentAlignment = Alignment.BottomCenter) {
                                if (actor.avatar.isNotBlank())
                                    AsyncImage(
                                        model = model.cover(actor.avatar),
                                        contentDescription = actor.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(52.dp).clip(CircleShape),
                                    )
                                else Box(Modifier.size(52.dp).background(
                                    DetailCardLight, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(actor.name.take(1), color = DetailText,
                                        fontSize = 21.sp)
                                }
                            }
                            Surface(shape = RoundedCornerShape(15.dp),
                                color = DetailText,
                                onClick = {
                                    Toast.makeText(context, "官方演员关注接口尚未验证",
                                        Toast.LENGTH_SHORT).show()
                                }) {
                                Text("+关注", Modifier.padding(
                                    horizontal = 7.dp, vertical = 2.dp),
                                    color = DetailBackground, fontSize = 11.sp)
                            }
                            Text(actor.name, color = DetailText, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (actor.role.isNotBlank())
                                Text("饰 ${actor.role}", color = DetailMuted,
                                    fontSize = 10.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        DetailHeader("剧评" + (verified?.reviewCount?.let { " · $it" } ?: ""))
                        Spacer(Modifier.weight(1f))
                        Text(if (showAllReviews) "收起剧评 ‹" else "全部剧评 ›",
                            modifier = Modifier.clickable { showAllReviews = !showAllReviews },
                            color = DetailMuted, fontSize = 12.sp)
                    }
                    Surface(shape = RoundedCornerShape(14.dp),
                        color = DetailCard) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.width(69.dp).background(DetailCardLight)
                                    .padding(horizontal = 7.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(verified?.rating?.let {
                                    String.format(Locale.ROOT, "%.1f", it)
                                } ?: "—", fontSize = 24.sp, color = DetailText,
                                    fontWeight = FontWeight.Bold)
                                Text("红果分", fontSize = 11.sp, color = DetailMuted)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(verified?.ratingCount?.let { "${it}人评分" }
                                    ?: "评分数据暂不可用",
                                    color = DetailMuted, fontSize = 12.sp)
                                Text("整剧评分", color = DetailMuted, fontSize = 11.sp)
                            }
                            Column(Modifier.padding(end = 13.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("看5分钟参与评分", color = DetailText,
                                    fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    repeat(5) {
                                        Icon(Icons.Outlined.StarBorder, null,
                                            modifier = Modifier.size(17.dp),
                                            tint = DetailMuted)
                                    }
                                }
                            }
                        }
                    }

                    if (verified?.reviews?.isNotEmpty() == true) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            verified.reviews.take(if (showAllReviews) 30 else 10)
                                .forEach { review ->
                                Surface(
                                    modifier = Modifier.width(294.dp).heightIn(min = 108.dp),
                                    color = DetailCard,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Column(Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (review.avatar.isNotBlank())
                                                AsyncImage(
                                                    model = model.cover(review.avatar),
                                                    contentDescription = review.author,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.size(22.dp).clip(CircleShape),
                                                )
                                            Text(review.author, color = DetailMuted,
                                                fontSize = 12.sp, maxLines = 1,
                                                modifier = Modifier.weight(1f))
                                            review.rating?.let {
                                                Text(String.format(Locale.ROOT, "%.1f分", it),
                                                    color = DetailMuted, fontSize = 11.sp)
                                            }
                                            if (review.likes > 0)
                                                Text("♡ ${review.likes}",
                                                    color = DetailText, fontSize = 11.sp)
                                        }
                                        Text(review.content, color = DetailText,
                                            fontSize = 13.sp, lineHeight = 20.sp,
                                            maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("暂无公开整剧剧评", color = DetailMuted, fontSize = 12.sp)
                    }
                }
            }

            metadata?.novel?.let { novel -> item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    DetailHeader("原著小说")
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (novel.cover.isNotBlank()) AsyncImage(
                            model = model.cover(novel.cover),
                            contentDescription = novel.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(36.dp).height(43.dp)
                                .clip(RoundedCornerShape(7.dp)),
                        )
                        Column(Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(novel.title, color = DetailText,
                                fontSize = 14.sp, maxLines = 2)
                            val facts = listOfNotNull(
                                novel.tags.take(2).joinToString(" · ").takeIf { it.isNotBlank() },
                                novel.status.takeIf { it.isNotBlank() },
                                novel.reads?.let { "$it 人在读" },
                            )
                            Text(facts.joinToString(" · "), color = DetailMuted,
                                fontSize = 11.sp, maxLines = 2)
                        }
                        Surface(color = DetailChip, shape = RoundedCornerShape(10.dp)) {
                            Text("暂无阅读入口",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                color = DetailMuted, fontSize = 11.sp)
                        }
                    }
                }
            } }

            if (state.relatedWorks.isNotEmpty() || state.relatedLoading ||
                state.relatedError != null) item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DetailHeader("相关作品")
                    if (state.relatedLoading && state.relatedWorks.isEmpty())
                        Text("正在加载相关作品…", color = DetailMuted, fontSize = 12.sp)
                    if (state.relatedWorks.isEmpty() && !state.relatedLoading)
                        Text(state.relatedError ?: "暂无相关作品",
                            color = DetailMuted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.relatedWorks.take(18).forEach { work ->
                            Column(Modifier.width(94.dp)
                                .clickable(enabled = !work.upcoming) {
                                    model.open(work.drama)
                                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(model = model.cover(work.drama.cover),
                                    contentDescription = work.drama.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(90.dp).height(122.dp)
                                        .clip(RoundedCornerShape(8.dp)))
                                if (work.season.isNotBlank() || work.upcoming)
                                    Text(if (work.upcoming) "待上线" else work.season,
                                        color = DetailMuted, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(work.drama.title, color = DetailText,
                                    fontSize = 12.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            if (state.openHongguoSeasons.isNotEmpty()) item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    DetailHeader("系列剧 · 其他季")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.openHongguoSeasons.forEach { season ->
                            Column(Modifier.width(94.dp).clickable { model.open(season) },
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(model = model.cover(season.cover),
                                    contentDescription = season.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(90.dp).height(122.dp)
                                        .clip(RoundedCornerShape(8.dp)))
                                Text(season.title, color = DetailText, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailHeader("猜你喜欢")
                    if (state.guessesLoading && state.guessWorks.isEmpty())
                        Text("正在加载推荐…", color = DetailMuted, fontSize = 12.sp)
                    if (state.guessWorks.isEmpty() && !state.guessesLoading)
                        Text(state.guessesError ?: "暂无猜你喜欢推荐",
                            color = DetailMuted, fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.guessWorks.take(18).forEach { guess ->
                            Column(Modifier.width(94.dp).clickable { model.open(guess) },
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                AsyncImage(model = model.cover(guess.cover),
                                    contentDescription = guess.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.width(90.dp).height(122.dp)
                                        .clip(RoundedCornerShape(8.dp)))
                                Text(guess.title, color = DetailText, fontSize = 12.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    TextButton(onClick = { showEpisodes = !showEpisodes }) {
                        Text("选集 · 全${detail.episodes.size}集  " +
                            if (showEpisodes) "收起 ↑" else "展开 ↓",
                            color = DetailText)
                    }
                }
            }
            if (showEpisodes) {
                items((detail.episodes.size + 4) / 5) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { col ->
                            val index = row * 5 + col
                            val episode = detail.episodes.getOrNull(index)
                            if (episode == null) Spacer(Modifier.weight(1f))
                            else Surface(
                                onClick = { model.play(index) },
                                modifier = Modifier.weight(1f)
                                    .testTag("detail_episode_$index"),
                                color = if (index == state.episodeIndex)
                                    DetailCardLight else DetailCard,
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Box(Modifier.height(44.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text("${episode.index}", color = DetailText,
                                        fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color(0xEF24140E))
                .padding(horizontal = 15.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { model.toggleFavoriteForDrama(drama) },
                modifier = Modifier.weight(1f).height(42.dp)
                    .testTag("detail_favorite_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetailCardLight),
            ) {
                Icon(Icons.Outlined.BookmarkBorder, null, tint = DetailText)
                Spacer(Modifier.width(7.dp))
                Text(if (locallySaved) "已收藏" else "收藏",
                    color = DetailText, fontSize = 15.sp)
            }
            Button(
                onClick = {
                    Toast.makeText(context, "官方写剧评接口尚未验证，暂仅支持查看",
                        Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).height(42.dp)
                    .testTag("detail_write_review_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetailChip),
            ) {
                Text("写剧评", color = DetailText, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String) {
    Text(title, color = DetailText, fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold)
}
