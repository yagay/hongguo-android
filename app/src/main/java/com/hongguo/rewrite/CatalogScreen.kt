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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Flat native catalog following the four navigation concepts of the website.
 * UI-only: paging, search and filters continue to use the existing ViewModel.
 */
@Composable
internal fun CatalogScreen(state: AppState, model: DramaViewModel, onMine: () -> Unit = {}) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var openSourceExplore by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(state.section, state.board, state.genre) {
        grid.scrollToItem(0)
        filtersOpen = false
    }
    if (openSourceExplore) {
        OpenHongguoExploreScreen(model, onClose = { openSourceExplore = false })
        return
    }
    // Recommendation pagination is intentionally manual.
    // The user taps the existing "加载更多" button to fetch the next three.
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = HongguoColors.background,
        topBar = { CatalogHeader(state, model, onMine) },
    ) { insets ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            state = grid,
            modifier = Modifier.fillMaxSize().padding(insets).testTag("web_catalog"),
            contentPadding = PaddingValues(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            if (state.section == Section.RECOMMEND && state.library.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("接着看", color = HongguoColors.ink,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.library.take(8).forEach { history ->
                                Box(Modifier.width(114.dp)) {
                                    CatalogPoster(history.drama,
                                        model.cover(history.drama.cover),
                                        savedLater = state.watchLater.any { it.id == history.drama.id },
                                        savedFavorite = state.favorites.any { it.drama.id == history.drama.id },
                                        savedLike = history.drama.id in state.likedDramaIds,
                                        onLater = { model.toggleWatchLater(history.drama) },
                                        onFavorite = { model.toggleFavoriteForDrama(history.drama) },
                                        onLike = { model.toggleLocalLikeForDrama(history.drama.id) },
                                        onHide = null,
                                    ) { model.open(history.drama, resumeEp = history.episode) }
                                }
                            }
                        }
                    }
                }
            }
            if (state.section == Section.RECOMMEND && state.trackedEpisodeUpdates.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("追剧有更新", color = HongguoColors.ink,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        state.trackedEpisodeUpdates.entries.take(4).forEach { (id, count) ->
                            val drama = (state.favorites.map { it.drama } + state.watchLater)
                                .firstOrNull { it.id == id }
                            if (drama != null) Row(
                                Modifier.fillMaxWidth().clickable { model.open(drama) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(drama.title, Modifier.weight(1f), color = HongguoColors.ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("+$count 集", color = HongguoColors.accent)
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                CatalogSectionTools(state, model, onFilters = { filtersOpen = true })
            }
            if (state.section == Section.RECOMMEND || state.section == Section.RANK) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { openSourceExplore = true },
                        modifier = Modifier.testTag("openhongguo_discovery_entry")) {
                        Text("发现更多 · 上新 / 真人榜 / 演员榜 / 漫剧 / 热搜",
                            color = HongguoColors.accent, fontSize = 12.sp)
                    }
                }
            }
            if (state.loading && state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = HongguoColors.accent)
                    }
                }
            }
            state.error?.let { error ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(color = HongguoColors.accentSoft, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("暂时无法加载", color = HongguoColors.ink,
                                fontWeight = FontWeight.SemiBold)
                            Text(error, color = Color(0xFF8B4434), fontSize = 12.sp)
                            TextButton(onClick = model::refresh) { Text("重试") }
                        }
                    }
                }
            }
            if (!state.loading && state.items.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(170.dp),
                        contentAlignment = Alignment.Center) {
                        Text(if (state.section == Section.SEARCH) "尝试搜索其他剧名"
                             else "这里暂时没有剧集", color = HongguoColors.muted)
                    }
                }
            }
            items(state.items.filter { it.id !in state.hiddenRecommendationIds ||
                state.section != Section.RECOMMEND }, key = { it.id }) { drama ->
                CatalogPoster(drama, model.cover(drama.cover),
                    savedLater = state.watchLater.any { it.id == drama.id },
                    savedFavorite = state.favorites.any { it.drama.id == drama.id },
                    savedLike = drama.id in state.likedDramaIds,
                    onLater = { model.toggleWatchLater(drama) },
                    onFavorite = { model.toggleFavoriteForDrama(drama) },
                    onLike = { model.toggleLocalLikeForDrama(drama.id) },
                    onHide = if (state.section == Section.RECOMMEND)
                        ({ model.hideRecommendation(drama) }) else null
                ) { model.open(drama) }
            }
            if (state.hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = model::more, enabled = !state.moreLoading) {
                            if (state.moreLoading) {
                                CircularProgressIndicator(Modifier.size(18.dp),
                                    strokeWidth = 2.dp, color = HongguoColors.accent)
                            } else Text("加载更多")
                        }
                    }
                }
            } else if (state.items.isNotEmpty() && !state.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("—  已经到底了  —", color = HongguoColors.muted,
                        fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(15.dp))
                }
            }
        }
    }
    if (filtersOpen && state.section == Section.BROWSE) {
        CatalogFiltersSheet(state, model, onClose = { filtersOpen = false })
    }
}

@Composable
private fun CatalogHeader(state: AppState, model: DramaViewModel, onMine: () -> Unit) {
    var searchFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(HongguoColors.surface)) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 15.dp, end = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("红果短剧", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = HongguoColors.accent)
                Text("发现好故事", fontSize = 10.sp, color = HongguoColors.muted)
            }
            HeaderIcon(Icons.Outlined.PersonOutline, "我的") { onMine() }
            HeaderIcon(Icons.Outlined.History, "播放历史") { model.show(Modal.HISTORY) }
            HeaderIcon(Icons.Outlined.StarBorder, "收藏") { model.show(Modal.FAVORITES) }
            HeaderIcon(Icons.Outlined.Download, "下载") {
                model.updateDownloads()
                model.show(Modal.DOWNLOAD)
            }
            HeaderIcon(Icons.Outlined.Share, "分享解析") { model.show(Modal.SHARE) }
            HeaderIcon(Icons.Outlined.Settings, "设置") { model.show(Modal.SETTINGS) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.search,
                onValueChange = model::setSearch,
                modifier = Modifier.fillMaxWidth().height(53.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .testTag("search_input"),
                placeholder = { Text("搜索剧名、演员或关键词", fontSize = 13.sp,
                    color = HongguoColors.muted) },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = HongguoColors.muted) },
                trailingIcon = {
                    TextButton(onClick = model::search,
                        modifier = Modifier.testTag("search_submit")) {
                        Text("搜索", color = HongguoColors.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.Bold)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.search() }),
            )
        }
        if (searchFocused && (state.suggestions.isNotEmpty() ||
                state.suggestionsLoading ||
                (state.search.isBlank() && state.recentSearches.isNotEmpty()))) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 164.dp)
                    .padding(horizontal = 15.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val rows = if (state.search.isBlank()) state.recentSearches else state.suggestions
                if (state.search.isBlank()) Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("最近搜索", color = HongguoColors.muted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = model::clearSearchHistory) {
                        Text("清空", fontSize = 11.sp)
                    }
                }
                if (state.suggestionsLoading) {
                    Text("正在获取联想…", color = HongguoColors.muted, fontSize = 11.sp)
                }
                rows.take(if (state.search.isBlank()) 2 else 4).forEach { text ->
                    Text(
                        text, modifier = Modifier.fillMaxWidth()
                            .clickable { model.chooseSuggestion(text) }
                            .padding(vertical = 4.dp, horizontal = 6.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp, color = HongguoColors.ink
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Section.entries.filter { it != Section.SEARCH }.forEach { item ->
                val selected = state.section == item
                Column(
                    Modifier.weight(1f).clickable { model.section(item) }
                        .testTag("tab_" + item.name.lowercase()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(item.title, fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) HongguoColors.accent else HongguoColors.ink)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(28.dp).height(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (selected) HongguoColors.accent else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = HongguoColors.divider)
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(39.dp)) {
        Icon(icon, contentDescription = label, tint = HongguoColors.ink,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CatalogSectionTools(state: AppState, model: DramaViewModel,
                                onFilters: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.section) {
                    Section.RECOMMEND -> "为你推荐"
                    Section.RANK -> "人气榜单"
                    Section.LATEST -> "最近上新"
                    Section.BROWSE -> "发现短剧"
                    Section.SEARCH -> "搜索结果"
                },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HongguoColors.ink,
                modifier = Modifier.weight(1f),
            )
            if (state.section == Section.RECOMMEND) {
                TextButton(
                    onClick = { model.startFeed() },
                    modifier = Modifier.testTag("start_video_feed"),
                ) {
                    Text("刷短剧", fontSize = 13.sp, color = HongguoColors.accent,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.items.isNotEmpty()) {
                Text("已加载 " + state.items.size + " 部", fontSize = 11.sp,
                    color = HongguoColors.muted)
            }
        }
        if (state.section == Section.RECOMMEND && state.feedMode &&
            state.feedError != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.feedError, color = HongguoColors.accent,
                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = model::retryOfficialReels,
                    enabled = !state.feedLoading,
                    modifier = Modifier.testTag("retry_official_reels_home")) {
                    Text("重试官方推荐")
                }
            }
        }
        if (state.section == Section.RANK) {
            CompactChoices(
                choices = if (state.backend.isBlank())
                    listOf("all" to "综合", "real" to "真人", "comic" to "漫剧", "ai" to "AI")
                else listOf("hot" to "热播", "new" to "新剧", "recommend" to "推荐"),
                selected = state.board, onSelect = model::board,
            )
        }
        if (state.section == Section.LATEST || state.section == Section.BROWSE) {
            CompactChoices(
                choices = listOf("short_play" to "真人", "comic_series" to "漫剧",
                    "ai_series" to "AI 剧"),
                selected = state.genre, onSelect = model::genre,
            )
        }
        if (state.section == Section.BROWSE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("按题材、背景、状态等筛选",
                    color = HongguoColors.muted, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onFilters, modifier = Modifier.testTag("open_filters")) {
                    Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (state.activeFilters.isEmpty()) "筛选条件"
                         else "筛选 · " + state.activeFilters.size,
                         fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CompactChoices(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label, fontSize = 12.sp) },
                shape = RoundedCornerShape(10.dp),
            )
        }
    }
}

@Composable
private fun CatalogPoster(drama: Drama, cover: String, savedLater: Boolean,
    savedFavorite: Boolean, savedLike: Boolean,
    onLater: () -> Unit, onFavorite: () -> Unit, onLike: () -> Unit,
    onHide: (() -> Unit)?, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .testTag("drama_" + drama.id),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8E6E5)),
        ) {
            if (cover.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("红果短剧", color = HongguoColors.muted, fontSize = 12.sp)
                }
            } else {
                AsyncImage(
                    model = cover, contentDescription = drama.title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true },
                    modifier = Modifier.testTag("poster_menu_" + drama.id)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "剧集管理", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (savedLater) "移出稍后看" else "加入稍后看") },
                        onClick = { menuOpen = false; onLater() })
                    DropdownMenuItem(text = { Text(if (savedFavorite) "取消本机收藏" else "本机收藏") },
                        onClick = { menuOpen = false; onFavorite() })
                    DropdownMenuItem(text = { Text(if (savedLike) "取消本机点赞" else "本机点赞") },
                        onClick = { menuOpen = false; onLike() })
                    if (onHide != null) DropdownMenuItem(text = { Text("不再推荐") },
                        onClick = { menuOpen = false; onHide() })
                }
            }
            if (drama.total > 0) {
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f))))
                    .padding(start = 7.dp, top = 18.dp, bottom = 6.dp)) {
                    Text("全 " + drama.total + " 集", color = Color.White,
                        fontWeight = FontWeight.Medium, fontSize = 10.sp)
                }
            }
        }
        Text(
            drama.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = HongguoColors.ink,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogFiltersSheet(
    state: AppState,
    model: DramaViewModel,
    onClose: () -> Unit,
) {
    var draft by remember(state.genre) { mutableStateOf(state.activeFilters) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = HongguoColors.surface,
        modifier = Modifier.testTag("catalog_filter_sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("筛选短剧", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), color = HongguoColors.ink)
                TextButton(onClick = { draft = emptyMap() }) { Text("重置") }
            }
            if (state.filters.isEmpty()) {
                Text("当前分类暂时没有可用筛选条件", color = HongguoColors.muted)
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.filters.forEach { group ->
                    item(key = group.key) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(group.name, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = HongguoColors.ink)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                group.options.forEach { option ->
                                    FilterChip(
                                        selected = draft[group.key].orEmpty() == option.value,
                                        onClick = {
                                            draft = draft.toMutableMap().apply {
                                                if (option.value.isBlank()) remove(group.key)
                                                else put(group.key, option.value)
                                            }
                                        },
                                        label = { Text(option.label, fontSize = 12.sp) },
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    (state.activeFilters.keys + draft.keys).forEach { key ->
                        model.chooseFilter(key, draft[key].orEmpty())
                    }
                    model.refresh()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .testTag("apply_filters"),
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("查看筛选结果", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
