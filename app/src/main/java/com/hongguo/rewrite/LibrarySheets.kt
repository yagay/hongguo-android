package com.hongguo.rewrite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Consistent bottom sheets for library, history, sharing and download tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibrarySheets(state: AppState, model: DramaViewModel) {
    if (state.modal !in setOf(Modal.HISTORY, Modal.FAVORITES, Modal.DOWNLOAD, Modal.SHARE)) return
    ModalBottomSheet(
        onDismissRequest = model::closeModal,
        containerColor = HongguoColors.surface,
        contentColor = HongguoColors.ink,
        modifier = Modifier.testTag("library_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.modal) {
                Modal.HISTORY, Modal.FAVORITES -> LibraryRows(state, model)
                Modal.DOWNLOAD -> DownloadRows(state, model)
                Modal.SHARE -> ShareInput(model)
                else -> Unit
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LibraryRows(state: AppState, model: DramaViewModel) {
    val isFavorites = state.modal == Modal.FAVORITES
    val rows = if (isFavorites) state.favorites else state.library
    var query by rememberSaveable(state.modal) { mutableStateOf("") }
    var completionFilter by rememberSaveable(state.modal) { mutableStateOf(0) }
    val filtered = rows.filter { entry ->
        entry.drama.title.contains(query.trim(), ignoreCase = true) &&
            (isFavorites || when (completionFilter) {
                1 -> entry.drama.total <= 0 || entry.episode < entry.drama.total
                2 -> entry.drama.total > 0 && entry.episode >= entry.drama.total
                else -> true
            })
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (isFavorites) Icons.Outlined.StarBorder else Icons.Outlined.History,
            null, tint = HongguoColors.accent)
        Spacer(Modifier.width(8.dp))
        Text(if (isFavorites) "我的收藏" else "播放历史",
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        if (rows.isNotEmpty()) {
            TextButton(onClick = { model.clear(isFavorites) }) {
                Text("清空", color = HongguoColors.muted)
            }
        }
    }
    OutlinedTextField(value = query, onValueChange = { query = it.take(60) },
        singleLine = true, label = { Text("搜索本机片单") },
        modifier = Modifier.fillMaxWidth().testTag("local_library_search"))
    if (!isFavorites) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("全部", "未看完", "已看完").forEachIndexed { index, label ->
                FilterChip(selected = completionFilter == index, onClick = { completionFilter = index },
                    label = { Text(label) }, modifier = Modifier.testTag("history_filter_$index"))
            }
        }
    }
    Text("显示 " + filtered.size + " / " + rows.size + " 部",
        color = HongguoColors.muted, fontSize = 12.sp)
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 500.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(filtered, key = { it.drama.id }) { entry ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    model.closeModal()
                    model.open(entry.drama, entry.episode)
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val image = model.cover(entry.drama.cover)
                if (image.isNotBlank()) {
                    AsyncImage(
                        model = image, contentDescription = null,
                        modifier = Modifier.size(width = 58.dp, height = 78.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.size(width = 58.dp, height = 78.dp),
                        contentAlignment = Alignment.Center) {
                        Text("短剧", color = HongguoColors.muted, fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.drama.title, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("看到第 " + entry.episode + " 集",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    Text("点击继续观看", color = HongguoColors.accent, fontSize = 11.sp)
                }
                TextButton(onClick = { model.remove(entry.drama.id, isFavorites) }) {
                    Text("移除", color = HongguoColors.muted, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = HongguoColors.divider)
        }
        if (filtered.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (rows.isNotEmpty()) "没有符合筛选条件的短剧" else if (isFavorites) "还没有收藏的短剧" else "暂无观看历史",
                        color = HongguoColors.muted)
                }
            }
        }
    }
}

@Composable
private fun DownloadRows(state: AppState, model: DramaViewModel) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Download, null, tint = HongguoColors.accent)
        Spacer(Modifier.width(8.dp))
        Text("下载任务", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f))
        if (state.backend.isNotBlank()) {
            TextButton(onClick = model::updateDownloads) {
                Text("刷新", color = HongguoColors.accent)
            }
        }
    }
    Text(if (state.backend.isBlank())
        "本机下载保存到你选择的位置；关闭应用进程后暂不能续传。"
        else "这些是所连接服务的下载任务，文件不自动保存在手机。",
        color = HongguoColors.muted, fontSize = 12.sp)
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.downloadTasks, key = { it.id }) { task ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.status, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Text(task.done.toString() + " / " + task.total + " 集",
                        color = HongguoColors.muted, fontSize = 11.sp)
                }
                if (task.id.startsWith("local-") &&
                    task.status.startsWith("下载中")) {
                    TextButton(onClick = { model.cancelDownload(task.id) }) {
                        Text("取消")
                    }
                }
            }
            HorizontalDivider(color = HongguoColors.divider)
        }
        if (state.downloadTasks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(145.dp),
                    contentAlignment = Alignment.Center) {
                    Text("暂无下载任务", color = HongguoColors.muted)
                }
            }
        }
    }
}

@Composable
private fun ShareInput(model: DramaViewModel) {
    var shareText by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Share, null, tint = HongguoColors.accent)
        Spacer(Modifier.width(8.dp))
        Text("解析分享链接", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Text("粘贴红果短剧分享文案或链接，解析后直接进入相应剧集。",
        fontSize = 12.sp, color = HongguoColors.muted)
    OutlinedTextField(
        value = shareText,
        onValueChange = { shareText = it },
        modifier = Modifier.fillMaxWidth().testTag("share_input"),
        placeholder = { Text("粘贴红果分享文案或链接") },
        shape = RoundedCornerShape(14.dp),
        minLines = 3, maxLines = 5,
    )
    Button(
        onClick = { model.share(shareText) },
        enabled = shareText.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(13.dp),
    ) { Text("解析并播放") }
}
