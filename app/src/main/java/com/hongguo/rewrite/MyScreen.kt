package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
    LazyColumn(
        Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    color = HongguoColors.ink, modifier = Modifier.weight(1f))
                Text("返回", color = HongguoColors.muted, fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onBack).padding(10.dp).testTag("my_back"))
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = HongguoColors.surface) {
                Row(Modifier.fillMaxWidth().clickable(onClick = openAccount)
                    .testTag("my_account_card").padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(HongguoColors.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = null,
                            tint = HongguoColors.accent, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(account.profile?.nickname ?: "登录红果账号",
                            color = HongguoColors.ink, fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(when {
                            account.profile != null -> "查看账号收藏、历史与预约"
                            account.checking -> "正在确认账号状态"
                            else -> "登录后查看云端片单"
                        }, color = HongguoColors.muted, fontSize = 14.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                        modifier = Modifier.size(20.dp), tint = HongguoColors.muted)
                }
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
        if (watchLaterOpen) {
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
