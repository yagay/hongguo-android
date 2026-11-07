package com.hongguo.rewrite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

private val Orange = Color(0xFFE85B34)
private val Background = Color(0xFFFFF9F6)
private val Ink = Color(0xFF201C1A)
private val Muted = Color(0xFF77716C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ApplicationRoot() }
    }
}

@Composable
private fun ApplicationRoot(model: DramaViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val scheme = lightColorScheme(
        primary = Orange, onPrimary = Color.White,
        background = Background, surface = Background,
        onBackground = Ink, onSurface = Ink,
    )
    MaterialTheme(colorScheme = scheme) {
        BackHandler(enabled = state.detail != null || state.preparingDetail || state.modal != Modal.NONE) {
            when {
                state.modal != Modal.NONE -> model.closeModal()
                else -> model.closePlayer()
            }
        }
        when {
            state.modal == Modal.SETTINGS -> SettingsScreen(state, model)
            state.detail != null -> NativePlayer(state, model)
            state.preparingDetail -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator()
                    Text("正在读取剧集…")
                    TextButton(onClick = model::closePlayer) { Text("取消") }
                }
            }
            else -> CatalogPageScreen(state, model)
        }
        WebDialogs(state, model)
    }
}

@Composable
private fun CatalogPageScreen(state: AppState, model: DramaViewModel) {
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(Background)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("红果", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Orange)
                    Text(" · 短剧", fontSize = 13.sp, color = Muted)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { model.show(Modal.SETTINGS) },
                        modifier = Modifier.testTag("open_settings")) {
                        Icon(Icons.Outlined.Settings, contentDescription = null,
                            modifier = Modifier.size(20.dp))
                        Text(" 设置")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.End) {
                    SmallAction(Icons.Outlined.History, "播放历史") { model.show(Modal.HISTORY) }
                    SmallAction(Icons.Outlined.StarBorder, "收藏") { model.show(Modal.FAVORITES) }
                    SmallAction(Icons.Outlined.Download, "下载进度") {
                        model.updateDownloads()
                        model.show(Modal.DOWNLOAD)
                    }
                    SmallAction(Icons.Outlined.Share, "分享解析") { model.show(Modal.SHARE) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Section.entries.filter { it != Section.SEARCH }.forEach { section ->
                        FilterChip(
                            selected = state.section == section,
                            onClick = { model.section(section) },
                            label = { Text(section.title) },
                            modifier = Modifier.testTag("tab_" + section.name.lowercase()),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.search, onValueChange = model::setSearch,
                        modifier = Modifier.weight(1f).testTag("search_input"),
                        placeholder = { Text("搜索剧名…") }, singleLine = true,
                        shape = RoundedCornerShape(22.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { model.search() }),
                    )
                    IconButton(onClick = model::search, modifier = Modifier.testTag("search_submit")) {
                        Icon(Icons.Outlined.Search, contentDescription = "搜索")
                    }
                }
                HorizontalDivider(color = Color(0xFFEFE6DF))
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("web_catalog"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.section.title, fontSize = 21.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (state.items.isNotEmpty()) Text(
                            state.items.size.toString() + " 部", color = Muted, fontSize = 12.sp)
                    }
                    if (state.section == Section.RANK) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("hot" to "热播", "new" to "新剧", "recommend" to "推荐").forEach { (key, label) ->
                                FilterChip(selected = state.board == key, onClick = { model.board(key) },
                                    label = { Text(label) })
                            }
                        }
                    }
                    if (state.section == Section.LATEST || state.section == Section.BROWSE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧")
                                .forEach { (key, label) ->
                                    FilterChip(selected = state.genre == key, onClick = { model.genre(key) },
                                        label = { Text(label) })
                                }
                        }
                    }
                    if (state.section == Section.BROWSE) {
                        state.filters.forEach { group ->
                            Text(group.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.options.forEach { option ->
                                    FilterChip(
                                        selected = state.activeFilters[group.key].orEmpty() == option.value,
                                        onClick = { model.chooseFilter(group.key, option.value) },
                                        label = { Text(option.label) },
                                    )
                                }
                            }
                        }
                        OutlinedButton(onClick = model::refresh) { Text("应用筛选") }
                    }
                    if (state.loading && state.items.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(28.dp),
                            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    state.error?.let { error ->
                        Surface(color = Color(0xFFFFEAE3), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(error, color = Color(0xFF8C2B17), fontSize = 13.sp)
                                TextButton(onClick = model::refresh) { Text("重试") }
                            }
                        }
                    }
                    if (!state.loading && state.items.isEmpty() && state.error == null) {
                        Text("暂无剧集，试试更换分类或搜索", color = Muted)
                    }
                }
            }
            items(state.items, key = { it.id }) { drama ->
                DramaPoster(drama, model.cover(drama.cover)) { model.open(drama) }
            }
            if (state.hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = model::more, enabled = !state.moreLoading) {
                        if (state.moreLoading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Text("加载更多")
                    }
                }
            }
            if (state.items.isNotEmpty() && !state.hasMore && !state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("已经到底了", modifier = Modifier.fillMaxWidth(),
                        color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DramaPoster(drama: Drama, cover: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).testTag("drama_" + drama.id),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFE8DFDA)) {
            Box(Modifier.fillMaxWidth().height(166.dp), contentAlignment = Alignment.Center) {
                if (cover.isBlank()) Text("无封面", color = Muted, fontSize = 12.sp)
                else AsyncImage(
                    model = cover, contentDescription = drama.title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
        }
        Text(drama.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        if (drama.total > 0) Text("全 " + drama.total + " 集", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(39.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun WebDialogs(state: AppState, model: DramaViewModel) {
    when (state.modal) {
        Modal.NONE -> Unit
        Modal.SETTINGS -> Unit
        Modal.SHARE -> {
            var share by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = model::closeModal, title = { Text("分享解析") },
                text = { OutlinedTextField(value = share, onValueChange = { share = it },
                    label = { Text("粘贴红果分享文案或链接") }, minLines = 3) },
                confirmButton = { TextButton(onClick = { model.share(share) }, enabled = share.isNotBlank()) {
                    Text("解析并播放")
                } },
                dismissButton = { TextButton(onClick = model::closeModal) { Text("关闭") } })
        }
        Modal.HISTORY, Modal.FAVORITES -> {
            val favorites = state.modal == Modal.FAVORITES
            val rows = if (favorites) state.favorites else state.library
            AlertDialog(onDismissRequest = model::closeModal,
                title = { Text(if (favorites) "我的收藏" else "播放历史") },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(rows, key = { it.drama.id }) { entry ->
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable {
                                    model.closeModal()
                                    model.open(entry.drama, entry.episode)
                                }) {
                                    Text(entry.drama.title, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("看到第 " + entry.episode + " 集", color = Muted, fontSize = 12.sp)
                                }
                                TextButton(onClick = { model.remove(entry.drama.id, favorites) }) {
                                    Text("移除")
                                }
                            }
                            HorizontalDivider()
                        }
                        if (rows.isEmpty()) item { Text("暂无记录") }
                    }
                },
                confirmButton = { TextButton(onClick = model::closeModal) { Text("关闭") } },
                dismissButton = {
                    if (rows.isNotEmpty()) TextButton(onClick = { model.clear(favorites) }) {
                        Text("清空")
                    }
                })
        }
        Modal.DOWNLOAD -> AlertDialog(onDismissRequest = model::closeModal,
            title = { Text("下载任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("任务运行在所连接的网页版服务器，文件不会自动保存到本手机。",
                        color = Muted, fontSize = 12.sp)
                    state.downloadTasks.forEach {
                        Text(it.status + " · " + it.done + "/" + it.total + " 集")
                    }
                    if (state.downloadTasks.isEmpty()) Text("暂无任务")
                    OutlinedButton(onClick = model::updateDownloads) { Text("刷新进度") }
                }
            },
            confirmButton = { TextButton(onClick = model::closeModal) { Text("关闭") } })
    }
}
