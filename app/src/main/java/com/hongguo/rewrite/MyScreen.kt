package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Main-branch inspired "我的" home. Uses rewrite's existing library, account, diagnostics
 * and navigation; the legacy app's ViewModel and other implementation are not imported.
 */
@Composable
internal fun MyScreen(state: AppState, model: DramaViewModel, onBack: () -> Unit) {
    val account by model.account.state.collectAsStateWithLifecycle()
    val openAccount = { model.show(Modal.ACCOUNT) }
    var watchLaterOpen by remember { mutableStateOf(false) }
    var offlineOpen by remember { mutableStateOf(false) }
    var libraryTab by rememberSaveable { mutableStateOf(0) }
    var watchFilter by rememberSaveable { mutableStateOf(0) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = HongguoColors.ink, modifier = Modifier.weight(1f))
                Text("设置", color = HongguoColors.muted, fontSize = 14.sp,
                    modifier = Modifier.clickable { model.show(Modal.SETTINGS) }
                        .padding(10.dp).testTag("my_settings_header"))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = HongguoColors.surface) {
                Row(Modifier.fillMaxWidth().clickable(onClick = openAccount)
                    .testTag("my_account_card").padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val avatar = account.profile?.avatarUrl
                    if (!avatar.isNullOrBlank()) {
                        AsyncImage(model = avatar, contentDescription = "账号头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(27.dp)))
                    } else {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(27.dp))
                            .background(HongguoColors.accentSoft),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PersonOutline, contentDescription = null,
                                tint = HongguoColors.accent, modifier = Modifier.size(28.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(account.profile?.nickname ?: "登录红果账号",
                            color = HongguoColors.ink, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val accountId = account.profile?.id.orEmpty()
                        Text(when {
                            accountId.isNotBlank() -> "红果号：$accountId"
                            account.profile != null -> "账号已登录"
                            account.checking -> "正在确认账号状态"
                            else -> "登录后查看云端片单"
                        }, color = HongguoColors.muted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = HongguoColors.muted)
                }
            }
        }
        // Account and local records share one gallery, but the data sources
        // remain separate. Cloud items never overwrite local checkpoints.
        item {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("历史", "收藏", "点赞", "预约").forEachIndexed { index, label ->
                    Column(
                        modifier = Modifier.clickable {
                            libraryTab = index
                            watchFilter = 0
                            if (index == 2 && !account.likesLoaded &&
                                !account.likesLoading) model.account.loadLikes(force = true)
                            if (index == 3 && !account.reservationsLoaded)
                                model.account.loadReservations()
                        }.testTag("my_gallery_tab_$index").padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, fontSize = 16.sp,
                            fontWeight = if (libraryTab == index)
                                FontWeight.Bold else FontWeight.Normal,
                            color = if (libraryTab == index)
                                HongguoColors.ink else HongguoColors.muted)
                        Spacer(Modifier.size(2.dp))
                        Box(Modifier.width(23.dp).height(2.dp)
                            .background(if (libraryTab == index)
                                HongguoColors.accent else Color.Transparent))
                    }
                }
            }
        }
        if (libraryTab == 0) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "已看完", "未看完").forEachIndexed { index, label ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (watchFilter == index)
                            HongguoColors.accentSoft else HongguoColors.surface,
                        modifier = Modifier.clickable { watchFilter = index }
                            .testTag("my_history_filter_$index"),
                    ) {
                        Text(label, modifier = Modifier.padding(
                            horizontal = 13.dp, vertical = 9.dp),
                            color = if (watchFilter == index)
                                HongguoColors.accent else HongguoColors.muted,
                            fontSize = 13.sp)
                    }
                }
            }
        }
        val cloudHistory = account.history.map { progress ->
            Drama(progress.series.id, progress.series.title, progress.series.cover)
        }
        val localHistory = state.library.map { it.drama }
        val history = (cloudHistory + localHistory).distinctBy { it.id }
            .filter { drama ->
                // Do not classify cloud-only progress as unfinished without
                // a verified total/complete flag. The local completion marker
                // is authoritative only for local entries.
                val hasLocalStatus = state.library.any { it.drama.id == drama.id }
                when (watchFilter) {
                    1 -> hasLocalStatus && drama.id in state.completedDramaIds
                    2 -> hasLocalStatus && drama.id !in state.completedDramaIds
                    else -> true
                }
            }
        val gallery = when (libraryTab) {
            0 -> history
            1 -> (state.favorites.map { it.drama } +
                account.favorites.map { Drama(it.series.id, it.series.title, it.series.cover) })
                .distinctBy { it.id }
            2 -> (state.library.map { it.drama } + state.favorites.map { it.drama })
                .filter { it.id in state.likedDramaIds }.distinctBy { it.id } +
                account.likes.map { Drama(it.series.id, it.series.title, it.series.cover) }
                    .filterNot { it.id in state.likedDramaIds }
            else -> (account.online + account.upcoming)
                .map { Drama(it.series.id, it.series.title, it.series.cover) }
                .distinctBy { it.id }
        }
        if (gallery.isEmpty()) item {
            Text(when (libraryTab) {
                0 -> "暂无符合条件的观看记录"
                1 -> "暂无收藏"
                2 -> if (account.likesLoading) "正在读取点赞记录…"
                     else "暂无点赞记录"
                else -> if (account.profile == null) "登录后查看预约"
                    else "暂无预约作品"
            }, color = HongguoColors.muted, fontSize = 13.sp)
        }
        items(gallery.chunked(3)) { row ->
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { drama ->
                    Column(
                        modifier = Modifier.weight(1f).clickable {
                            when (libraryTab) {
                                0 -> {
                                    val cloud = account.history.firstOrNull { it.series.id == drama.id }
                                    if (cloud != null) model.openCloudProgress(cloud)
                                    else {
                                        val local = state.library.firstOrNull { it.drama.id == drama.id }
                                        model.open(drama, resumeEp = local?.episode)
                                    }
                                }
                                3 -> {
                                    val online = account.online.firstOrNull { it.series.id == drama.id }
                                    if (online != null) model.openCloudDrama(online.series)
                                    else model.show(Modal.ACCOUNT)
                                }
                                else -> model.open(drama)
                            }
                        }.testTag("my_gallery_drama_" + drama.id),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AsyncImage(model = model.cover(drama.cover),
                            contentDescription = drama.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(HongguoColors.accentSoft))
                        Text(drama.title, color = HongguoColors.ink, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (libraryTab == 3) {
                            val upcoming = account.upcoming.firstOrNull {
                                it.series.id == drama.id
                            }
                            Text(upcoming?.publishText?.takeIf { it.isNotBlank() }
                                ?: if (upcoming != null) "待上线" else "已上线",
                                color = HongguoColors.muted, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (libraryTab == 0) {
                            val entry = state.library.firstOrNull { it.drama.id == drama.id }
                            val cloud = account.history.firstOrNull {
                                it.series.id == drama.id
                            }
                            val progressText = if (cloud != null)
                                "第${cloud.episodeIndex + 1}集 · " +
                                    PlaybackTimeFormatter.format(cloud.position)
                            else entry?.let { "第${it.episode}集" } ?: "观看记录"
                            Text(progressText, color = HongguoColors.muted,
                                fontSize = 11.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = HongguoColors.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("追剧更新", color = HongguoColors.ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (state.trackingUpdates) CircularProgressIndicator(Modifier.size(18.dp))
                        else TextButton(onClick = model::checkTrackedUpdates,
                            modifier = Modifier.testTag("check_tracked_updates")) {
                            Text("检查更新")
                        }
                    }
                    Text("检查本机收藏和稍后看的新增集数。首次检查仅建立基准，不依赖账号。",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    state.trackingUpdateMessage?.let {
                        Text(it, color = HongguoColors.muted, fontSize = 12.sp)
                    }
                    val followed = (state.favorites.map { it.drama } + state.watchLater)
                        .distinctBy { it.id }
                    followed.filter { (state.trackedEpisodeUpdates[it.id] ?: 0) > 0 }
                        .take(12).forEach { drama ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(drama.title, modifier = Modifier.weight(1f),
                                    color = HongguoColors.ink, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text("+${state.trackedEpisodeUpdates[drama.id] ?: 0} 集",
                                    color = HongguoColors.accent,
                                    modifier = Modifier.clickable { model.open(drama) }.padding(8.dp))
                            }
                        }
                }
            }
        }
        if (offlineOpen) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本机离线 · ${state.offlineEpisodes.size} 集",
                        color = HongguoColors.ink, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { offlineOpen = false }) { Text("返回") }
                }
            }
            state.offlineLibraryMessage?.let { message ->
                item { Text(message, color = HongguoColors.accent, fontSize = 13.sp) }
            }
            if (state.offlineEpisodes.isEmpty()) item {
                Text("还没有已完成的离线保存。播放器选集页可批量保存到手机。",
                    color = HongguoColors.muted, fontSize = 13.sp)
            }
            items(state.offlineEpisodes.size) { index ->
                val episode = state.offlineEpisodes[index]
                Surface(color = HongguoColors.surface, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable {
                            model.openOfflineEpisode(episode)
                        }) {
                            Text(episode.title, color = HongguoColors.ink,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text("第 ${episode.episode} 集 · 本机播放",
                                color = HongguoColors.muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            model.removeOfflineEpisode(episode.documentUri)
                        }, modifier = Modifier.testTag("offline_remove_" + episode.vid)) {
                            Text("移除记录")
                        }
                    }
                }
            }
        } else if (watchLaterOpen) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("稍后看 · ${state.watchLater.size} 部", color = HongguoColors.ink,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Text("返回", color = HongguoColors.accent,
                        modifier = Modifier.clickable { watchLaterOpen = false }.padding(8.dp))
                }
            }
            if (state.watchLater.isEmpty()) item {
                Text("没有稍后看的剧集，可在首页封面右上角添加。",
                    color = HongguoColors.muted, fontSize = 13.sp)
            }
            items(state.watchLater.size) { index ->
                val drama = state.watchLater[index]
                Surface(color = HongguoColors.surface, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { model.open(drama) }) {
                            Text(drama.title, color = HongguoColors.ink,
                                fontWeight = FontWeight.SemiBold)
                            Text("点击观看", color = HongguoColors.muted, fontSize = 12.sp)
                        }
                        Text("移除", color = HongguoColors.accent,
                            modifier = Modifier.clickable { model.toggleWatchLater(drama) }
                                .padding(10.dp).testTag("later_remove_" + drama.id))
                    }
                }
            }
        } else {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MyStat("本机收藏", state.favorites.size, Icons.Outlined.StarBorder,
                    Modifier.weight(1f).testTag("my_favorites")) {
                    model.show(Modal.FAVORITES)
                }
                MyStat("本机看过", state.library.size, Icons.Outlined.History,
                    Modifier.weight(1f).testTag("my_history")) {
                    model.show(Modal.HISTORY)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = HongguoColors.surface) {
                Column {
                    MyMenuRow("账号与隐私", "登录方式、云片单与账号管理",
                        "my_account", Icons.Outlined.PrivacyTip, openAccount)
                    MyMenuDivider()
                    MyMenuRow("设置", "账号、播放偏好、内容来源与诊断",
                        "my_settings", Icons.Outlined.Settings) {
                        model.show(Modal.SETTINGS)
                    }
                    MyMenuDivider()
                    MyMenuRow("稍后看", "待看的短剧 · ${state.watchLater.size} 部",
                        "my_watch_later", Icons.Outlined.BookmarkBorder) {
                        watchLaterOpen = true
                    }
                    MyMenuDivider()
                    MyMenuRow("下载任务", "本机已创建的下载与进度",
                        "my_download", Icons.Outlined.Download) {
                        model.updateDownloads()
                        model.show(Modal.DOWNLOAD)
                    }
                    MyMenuDivider()
                    MyMenuRow("本机离线", "已保存的视频 · ${state.offlineEpisodes.size} 集",
                        "my_offline_library", Icons.Outlined.Download) {
                        offlineOpen = true
                    }
                    MyMenuDivider()
                    MyMenuRow("分享解析", "从短剧分享链接查找作品",
                        "my_share", Icons.Outlined.Share) {
                        model.show(Modal.SHARE)
                    }
                }
            }
        }
        item {
            Text("本机收藏与观看记录仅保存在这台设备。", color = HongguoColors.muted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("红果短剧 ${BuildConfig.VERSION_NAME}", color = HongguoColors.muted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        }
    }
}

@Composable
private fun MyStat(
    label: String, count: Int, icon: ImageVector,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp), color = HongguoColors.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(16.dp), tint = HongguoColors.muted)
                Text(label, color = HongguoColors.muted, fontSize = 14.sp)
            }
            Text(count.toString(), color = HongguoColors.ink,
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MyMenuDivider() = HorizontalDivider(
    color = HongguoColors.background,
    modifier = Modifier.padding(start = 64.dp, end = 18.dp),
)

@Composable
private fun MyMenuRow(
    title: String, subtitle: String, tag: String, icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(HongguoColors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = HongguoColors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = HongguoColors.ink, fontSize = 16.sp,
                fontWeight = FontWeight.Medium)
            Text(subtitle, color = HongguoColors.muted,
                fontSize = 12.sp, lineHeight = 19.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null,
            Modifier.size(20.dp), tint = HongguoColors.muted)
    }
}
