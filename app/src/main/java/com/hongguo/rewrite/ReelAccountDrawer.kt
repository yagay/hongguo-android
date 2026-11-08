package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Account drawer over the existing reel surface; it never owns or rebuilds Media3. */
@Composable
internal fun ReelAccountDrawer(
    state: AppState,
    model: DramaViewModel,
    onAccount: () -> Unit,
    onHistoryItem: (Drama) -> Unit,
    onReservations: () -> Unit,
    onDownloads: () -> Unit,
    onShare: () -> Unit,
    onSettings: () -> Unit,
    onOfflineEpisode: (OfflineEpisode) -> Unit,
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    // History can include repeat visits; show each drama only once, most recent first.
    val recentlyWatched = remember(state.library) {
        state.library.sortedByDescending { it.updated }.distinctBy { it.drama.id }
    }
    var recentExpanded by rememberSaveable { mutableStateOf(false) }
    var drawerPage by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Color(0xFFF8F8F8))
        .testTag("reel_account_drawer")) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp,
                top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (drawerPage == "watch_later" || drawerPage == "offline") {
                item {
                    Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clickable { drawerPage = null }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .testTag("drawer_library_back"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                                "返回常用功能", tint = Color(0xFF222222),
                                modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("返回", color = Color(0xFF222222), fontSize = 14.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (drawerPage == "watch_later") "稍后看" else "本机离线",
                            color = Color(0xFF222222), fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp)
                    }
                }
                if (drawerPage == "watch_later") {
                    if (state.watchLater.isEmpty()) item {
                        Text("还没有稍后看的短剧", color = Color.Gray, fontSize = 13.sp)
                    }
                    items(state.watchLater, key = { "later_" + it.id }) { drama ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = model.cover(drama.cover),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 48.dp, height = 65.dp)
                                        .clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f).clickable {
                                    onHistoryItem(drama)
                                }.testTag("drawer_later_open_" + drama.id)) {
                                    Text(drama.title, fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Color(0xFF222222))
                                    Text("点击继续观看", fontSize = 11.sp, color = Color.Gray)
                                }
                                TextButton(onClick = { model.toggleWatchLater(drama) },
                                    modifier = Modifier.testTag("drawer_later_remove_" + drama.id)) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                } else {
                    state.offlineLibraryMessage?.let { notice ->
                        item { Text(notice, color = Color(0xFF8F4E3C), fontSize = 12.sp) }
                    }
                    if (state.offlineEpisodes.isEmpty()) item {
                        Text("还没有保存到本机的离线视频", color = Color.Gray, fontSize = 13.sp)
                    }
                    items(state.offlineEpisodes,
                        key = { "offline_" + it.documentUri }) { episode ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable {
                                    onOfflineEpisode(episode)
                                }.testTag("drawer_offline_open_" + episode.vid)) {
                                    Text(episode.title, color = Color(0xFF222222),
                                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("第 ${episode.episode} 集 · 本机播放",
                                        color = Color.Gray, fontSize = 12.sp)
                                }
                                TextButton(onClick = {
                                    model.removeOfflineEpisode(episode.documentUri)
                                }, modifier = Modifier.testTag("drawer_offline_remove_" + episode.vid)) {
                                    Text("移除记录")
                                }
                            }
                        }
                    }
                }
            } else {
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onAccount)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("drawer_account"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val avatar = account.profile?.avatarUrl
                if (!avatar.isNullOrBlank()) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Color(0xFFE8E8E8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PersonOutline, null,
                            tint = Color.DarkGray, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(account.profile?.nickname ?: "登录红果账号",
                        color = Color(0xFF161616), fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("账号与隐私", color = Color(0xFF777777), fontSize = 12.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = Color.Gray)
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近在看", fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp, color = Color(0xFF222222),
                            modifier = Modifier.weight(1f))
                        if (recentlyWatched.size > 2) {
                            Text(if (recentExpanded) "收起" else "展开更多 · ${recentlyWatched.size}部",
                                modifier = Modifier.clickable {
                                    recentExpanded = !recentExpanded
                                }.padding(vertical = 7.dp)
                                    .testTag("drawer_recent_expand"),
                                color = Color(0xFF8F4E3C), fontSize = 12.sp)
                        }
                    }
                    if (recentlyWatched.isEmpty()) {
                        Text("还没有观看记录", color = Color.Gray, fontSize = 13.sp)
                    } else if (!recentExpanded) {
                        // One horizontal row contains ALL watched dramas. The next
                        // poster remains partially visible to indicate swiping.
                        LazyRow(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("drawer_recent_carousel"),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(recentlyWatched, key = { it.drama.id }) { watched ->
                                RecentWatchTile(watched, model,
                                    modifier = Modifier.width(100.dp),
                                    onClick = { onHistoryItem(watched.drama) })
                            }
                        }
                        if (recentlyWatched.size > 2) {
                            Text("左右滑动查看更多", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (recentExpanded && recentlyWatched.isNotEmpty()) {
            // Keep the drawer's outer LazyColumn responsible for vertical
            // scrolling, including long histories. No nested vertical scroll.
            items(recentlyWatched.chunked(2),
                key = { row -> "recent_expanded_" + row.first().drama.id }) { row ->
                Card(shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                        .testTag("drawer_recent_expanded_row")) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { watched ->
                            RecentWatchTile(watched, model,
                                modifier = Modifier.weight(1f),
                                onClick = { onHistoryItem(watched.drama) })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("常用功能", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF222222))
                    DrawerAction("我的预约", Icons.Outlined.EventAvailable,
                        onReservations, "drawer_reservations")
                    DrawerAction("稍后看 · ${state.watchLater.size}部",
                        Icons.Outlined.BookmarkBorder,
                        { drawerPage = "watch_later" }, "drawer_watch_later")
                    DrawerAction("下载任务", Icons.Outlined.Download,
                        onDownloads, "drawer_downloads")
                    DrawerAction("本机离线 · ${state.offlineEpisodes.size}集",
                        Icons.Outlined.FolderOpen,
                        { drawerPage = "offline" }, "drawer_offline_library")
                    DrawerAction("分享解析", Icons.Outlined.Share,
                        onShare, "drawer_share")
                    DrawerAction("账号与隐私", Icons.Outlined.PrivacyTip,
                        onAccount, "drawer_privacy")
                }
            }
        }
        } // Drawer main menu
        } // Scrollable content: settings stays pinned below
        Surface(color = Color(0xFFF8F8F8), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = Color(0xFFE5E5E5))
                Row(Modifier.fillMaxWidth().clickable(onClick = onSettings)
                    .testTag("drawer_settings")
                    .padding(horizontal = 23.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, contentDescription = null,
                        tint = Color(0xFF242424), modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("设置", color = Color(0xFF242424), fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                        tint = Color(0xFF777777))
                }
            }
        }
    }
}

/** Same clickable history item in the horizontal strip and expanded grid. */
@Composable
private fun RecentWatchTile(
    watched: Watched,
    model: DramaViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier.clickable(onClick = onClick)
            .testTag("drawer_recent_" + watched.drama.id),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        AsyncImage(
            model = model.cover(watched.drama.cover),
            contentDescription = watched.drama.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(128.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE5E5E5)),
        )
        Text(watched.drama.title, color = Color(0xFF222222),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp, lineHeight = 16.sp)
        Text("第${watched.episode}集", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun DrawerAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 11.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = Color(0xFF222222), modifier = Modifier.size(23.dp))
        Text(title, color = Color(0xFF202020), fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = Color.LightGray)
    }
}
