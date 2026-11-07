package com.hongguo.rewrite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    var selecting by rememberSaveable(state.modal) { mutableStateOf(false) }
    var confirmClear by rememberSaveable(state.modal) { mutableStateOf(false) }
    var confirmBatch by rememberSaveable(state.modal) { mutableStateOf(false) }
    var selectedIds by rememberSaveable(state.modal) { mutableStateOf(emptyList<String>()) }
    var groupFilter by rememberSaveable(state.modal) { mutableStateOf("全部") }
    var menuFor by rememberSaveable(state.modal) { mutableStateOf("") }
    var customGroupFor by rememberSaveable(state.modal) { mutableStateOf("") }
    var customGroupText by rememberSaveable(state.modal) { mutableStateOf("") }
    val filtered = rows.filter { entry ->
        entry.drama.title.contains(query.trim(), ignoreCase = true) &&
            (if (isFavorites) (groupFilter == "全部" ||
                (state.favoriteGroups[entry.drama.id] ?: "默认") == groupFilter)
            else when (completionFilter) {
                1 -> entry.drama.id !in state.completedDramaIds
                2 -> entry.drama.id in state.completedDramaIds
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
            TextButton(onClick = {
                selecting = !selecting
                selectedIds = emptyList()
            }) { Text(if (selecting) "完成" else "批量管理") }
            TextButton(onClick = { confirmClear = true }) {
                Text("清空", color = HongguoColors.muted)
            }
        }
    }
    OutlinedTextField(value = query, onValueChange = { query = it.take(60) },
        singleLine = true, label = { Text("搜索本机片单") },
        modifier = Modifier.fillMaxWidth().testTag("local_library_search"))
    if (isFavorites) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (listOf("全部", "默认", "追剧中", "待看", "已看完") +
                state.favoriteGroups.values).distinct().forEach { group ->
                FilterChip(selected = groupFilter == group,
                    onClick = { groupFilter = group }, label = { Text(group) })
            }
        }
    }
    if (!isFavorites) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("全部", "未看完", "已看完").forEachIndexed { index, label ->
                FilterChip(selected = completionFilter == index, onClick = { completionFilter = index },
                    label = { Text(label) }, modifier = Modifier.testTag("history_filter_$index"))
            }
        }
    }
    if (selecting) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selectedIds = filtered.map { it.drama.id } }) {
                Text("全选当前结果")
            }
            TextButton(onClick = { selectedIds = emptyList() }) {
                Text("取消全选")
            }
            TextButton(onClick = { confirmBatch = true }, enabled = selectedIds.isNotEmpty()) {
                Text("删除选中 ${selectedIds.size} 部")
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
                    if (selecting) {
                        selectedIds = if (entry.drama.id in selectedIds)
                            selectedIds - entry.drama.id else selectedIds + entry.drama.id
                    } else {
                        model.closeModal()
                        model.open(entry.drama, entry.episode)
                    }
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selecting) Checkbox(checked = entry.drama.id in selectedIds,
                    onCheckedChange = { enabled ->
                        selectedIds = if (enabled) (selectedIds + entry.drama.id).distinct()
                            else selectedIds - entry.drama.id
                    })
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
                if (!selecting && isFavorites) Box {
                    TextButton(onClick = { menuFor = entry.drama.id }) {
                        Text(state.favoriteGroups[entry.drama.id] ?: "默认",
                            maxLines = 1, fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = menuFor == entry.drama.id,
                        onDismissRequest = { menuFor = "" }) {
                        (listOf("默认", "追剧中", "待看", "已看完") +
                            state.favoriteGroups.values).distinct().forEach { group ->
                            DropdownMenuItem(text = { Text(group) }, onClick = {
                                menuFor = ""
                                model.setFavoriteGroup(entry.drama.id, group)
                            })
                        }
                        DropdownMenuItem(text = { Text("自定义分组…") }, onClick = {
                            menuFor = ""
                            customGroupFor = entry.drama.id
                            customGroupText = state.favoriteGroups[entry.drama.id].orEmpty()
                        })
                    }
                }
                if (!selecting && !isFavorites) TextButton(onClick = {
                    model.setDramaCompleted(entry.drama.id,
                        entry.drama.id !in state.completedDramaIds)
                }) {
                    Text(if (entry.drama.id in state.completedDramaIds) "恢复未看完" else "标记看完",
                        color = HongguoColors.accent, fontSize = 11.sp)
                }
                if (!selecting) TextButton(onClick = { model.remove(entry.drama.id, isFavorites) }) {
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
    if (customGroupFor.isNotBlank()) AlertDialog(
        onDismissRequest = { customGroupFor = "" },
        title = { Text("自定义收藏分组") },
        text = {
            OutlinedTextField(value = customGroupText,
                onValueChange = { customGroupText = it.take(24) },
                label = { Text("分组名称，最多24个字") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                model.setFavoriteGroup(customGroupFor, customGroupText.trim())
                customGroupFor = ""
            }, enabled = customGroupText.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { customGroupFor = "" }) { Text("取消") } },
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(if (isFavorites) "清空全部本机收藏？" else "清空全部本机历史？") },
        text = { Text("操作无法撤销，不影响另一类片单和云端账号。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                selectedIds = emptyList()
                model.clear(isFavorites)
            }) { Text("确认清空") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
    if (confirmBatch) AlertDialog(
        onDismissRequest = { confirmBatch = false },
        title = { Text("删除选中的 ${selectedIds.size} 部短剧？") },
        text = { Text("只移除本机片单记录，不删除视频文件或云端收藏。") },
        confirmButton = {
            TextButton(onClick = {
                val chosen = selectedIds.toList()
                confirmBatch = false
                selectedIds = emptyList()
                chosen.forEach { model.remove(it, isFavorites) }
            }) { Text("确认删除") }
        },
        dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } },
    )
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
