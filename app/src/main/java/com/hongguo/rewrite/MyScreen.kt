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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
    var gallerySearchOpen by rememberSaveable { mutableStateOf(false) }
    var galleryQuery by rememberSaveable { mutableStateOf("") }
    var localHistoryEdit by rememberSaveable { mutableStateOf(false) }
    var managementMenuOpen by remember { mutableStateOf(false) }
    var accountOperationsOpen by remember { mutableStateOf(false) }
    var confirmAccountLogout by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("my_screen")
            .padding(horizontal = HongguoUi.pagePadding),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(HongguoUi.pageTopBarHeight),
                verticalAlignment = Alignment.CenterVertically) {
                Text("我的", fontSize = HongguoUi.pageTitleSize, fontWeight = FontWeight.Bold,
                    color = HongguoColors.ink, modifier = Modifier.weight(1f))
                Box {
                    Icon(
                        Icons.Outlined.Menu, contentDescription = "管理菜单",
                        modifier = Modifier.size(40.dp)
                            .clickable { managementMenuOpen = true }
                            .padding(7.dp).testTag("my_management_menu"),
                        tint = HongguoColors.ink,
                    )
                    DropdownMenu(
                        expanded = managementMenuOpen,
                        onDismissRequest = { managementMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("稍后看 · ${state.watchLater.size} 部") },
                            leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null) },
                            onClick = {
                                managementMenuOpen = false
                                offlineOpen = false
                                watchLaterOpen = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("下载任务") },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = {
                                managementMenuOpen = false
                                model.updateDownloads()
                                model.show(Modal.DOWNLOAD)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("本机离线 · ${state.offlineEpisodes.size} 集") },
                            leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            onClick = {
                                managementMenuOpen = false
                                watchLaterOpen = false
                                offlineOpen = true
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("本机收藏 · ${state.favorites.size}") },
                            leadingIcon = { Icon(Icons.Outlined.StarBorder, null) },
                            onClick = {
                                managementMenuOpen = false
                                model.show(Modal.FAVORITES)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("本机看过 · ${state.library.size}") },
                            leadingIcon = { Icon(Icons.Outlined.History, null) },
                            onClick = {
                                managementMenuOpen = false
                                model.show(Modal.HISTORY)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("分享解析") },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) },
                            onClick = {
                                managementMenuOpen = false
                                model.show(Modal.SHARE)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("账号与隐私") },
                            leadingIcon = { Icon(Icons.Outlined.PrivacyTip, null) },
                            onClick = {
                                managementMenuOpen = false
                                openAccount()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = {
                                managementMenuOpen = false
                                model.show(Modal.SETTINGS)
                            },
                        )
                    }
                }
            }
        }
        item {
            HongguoCard() {
                Row(
                    Modifier.fillMaxWidth().testTag("my_account_card")
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    // Match the verified profile presentation in AccountScreen.
                    // A placeholder remains visible if the avatar is missing.
                    Box(Modifier.size(58.dp).clip(CircleShape)
                            .background(HongguoColors.accentSoft)
                            .then(if (account.profile == null)
                                Modifier.clickable(onClick = openAccount) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, null,
                            Modifier.size(33.dp), tint = HongguoColors.accent)
                        account.profile?.avatarUrl?.let { avatar ->
                            AsyncImage(
                                model = avatar, contentDescription = "红果账号头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    .testTag("my_account_avatar"),
                            )
                        }
                    }
                    Column(
                        Modifier.weight(1f).then(
                            if (account.profile == null)
                                Modifier.clickable(onClick = openAccount) else Modifier
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (account.profile == null) "登录红果账号"
                            else account.profile?.nickname.orEmpty().ifBlank { "红果用户" },
                            color = HongguoColors.ink, fontSize = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("my_account_profile"),
                        )
                        Text(
                            if (account.profile != null)
                                "红果号 ${account.profile?.id.orEmpty()}"
                            else if (account.checking) "正在确认账号状态"
                            else if (account.savedSessionPresent) "已保存的账号等待验证"
                            else "查看账号收藏、历史与预约",
                            color = HongguoColors.muted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (account.profile != null) {
                        Box {
                            IconButton(
                                onClick = { accountOperationsOpen = true },
                                modifier = Modifier.testTag("my_account_menu"),
                            ) {
                                Icon(Icons.Outlined.MoreVert, "账号操作",
                                    tint = HongguoColors.ink)
                            }
                            DropdownMenu(
                                expanded = accountOperationsOpen,
                                onDismissRequest = { accountOperationsOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("刷短剧使用登录账号") },
                                    trailingIcon = { Checkbox(
                                        checked = account.accountRecommendations,
                                        onCheckedChange = null) },
                                    onClick = {
                                        accountOperationsOpen = false
                                        model.setAccountRecommendations(
                                            !account.accountRecommendations)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("自动同步观看进度") },
                                    trailingIcon = { Checkbox(
                                        checked = account.syncEnabled,
                                        onCheckedChange = null) },
                                    onClick = {
                                        accountOperationsOpen = false
                                        model.account.setProgressSync(!account.syncEnabled)
                                    },
                                    modifier = Modifier.testTag("my_account_sync_switch"),
                                )
                                DropdownMenuItem(
                                    text = { Text("刷新账号资料") },
                                    onClick = {
                                        accountOperationsOpen = false
                                        model.account.refresh()
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("退出账号") },
                                    onClick = {
                                        accountOperationsOpen = false
                                        confirmAccountLogout = true
                                    },
                                    modifier = Modifier.testTag("my_account_logout"),
                                )
                            }
                        }
                    } else {
                        Icon(Icons.Outlined.ChevronRight, "打开账号登录",
                            tint = HongguoColors.muted,
                            modifier = Modifier.size(24.dp)
                                .clickable(onClick = openAccount))
                    }
                }
            }
        }
        // Account and local records share one gallery, but the data sources
        // remain separate. Cloud items never overwrite local checkpoints.
        item {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                HongguoTabs(
                    labels = listOf("历史", "收藏", "点赞", "预约"),
                    selectedIndex = libraryTab,
                    tagPrefix = "my_gallery_tab",
                    modifier = Modifier.weight(1f),
                    onSelect = { index ->
                        libraryTab = index
                        watchFilter = 0
                        if (index == 2 && !account.likesLoaded &&
                            !account.likesLoading) model.account.loadLikes(force = true)
                        if (index == 3 && !account.reservationsLoaded)
                            model.account.loadReservations()
                    },
                )
                Text(if (gallerySearchOpen) "关闭" else "搜索",
                    color = HongguoColors.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        gallerySearchOpen = !gallerySearchOpen
                        if (!gallerySearchOpen) galleryQuery = ""
                    }.padding(5.dp).testTag("my_gallery_search_toggle"))
            }
        }
        if (gallerySearchOpen) item {
            OutlinedTextField(
                value = galleryQuery,
                onValueChange = { galleryQuery = it.take(80) },
                modifier = Modifier.fillMaxWidth().testTag("my_gallery_search_input"),
                singleLine = true,
                placeholder = { Text("搜索当前片单的剧名") },
            )
        }
        if (libraryTab == 0) item {
            HongguoChoiceGroup(
                options = listOf("全部", "已看完", "未看完")
                    .mapIndexed { index, label -> index to label },
                selected = watchFilter,
                onSelect = { watchFilter = it },
                tag = "my_history_filter",
            )
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
        val gallerySource = when (libraryTab) {
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
        val gallery = gallerySource.filter {
            galleryQuery.isBlank() || it.title.contains(galleryQuery.trim(),
                ignoreCase = true)
        }
        if (libraryTab == 0 && !gallerySearchOpen) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (localHistoryEdit) "完成编辑" else "编辑本机历史",
                    color = HongguoColors.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { localHistoryEdit = !localHistoryEdit }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("my_history_edit_toggle"))
            }
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
                            if (localHistoryEdit && entry != null) {
                                Text(if (drama.id in state.completedDramaIds)
                                        "标为未看完" else "标为已看完",
                                    color = HongguoColors.accent, fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        model.setDramaCompleted(drama.id,
                                            drama.id !in state.completedDramaIds)
                                    }.padding(vertical = 4.dp)
                                        .testTag("my_history_complete_" + drama.id))
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            HongguoCard() {
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
                HongguoCard() {
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
                HongguoCard() {
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
            // Management actions now live in the top-right anchored menu.
            // The main profile screen stays focused on the account gallery.
            item {
                Text("红果短剧 ${BuildConfig.VERSION_NAME}",
                    color = HongguoColors.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (confirmAccountLogout) AlertDialog(
        onDismissRequest = { confirmAccountLogout = false },
        title = { Text("退出红果账号？") },
        text = { Text("清除本机保存的登录凭据；不会删除本机观看历史和收藏。") },
        confirmButton = {
            TextButton(onClick = {
                confirmAccountLogout = false
                model.account.logout()
            }) { Text("退出") }
        },
        dismissButton = {
            TextButton(onClick = { confirmAccountLogout = false }) { Text("取消") }
        },
    )
}
