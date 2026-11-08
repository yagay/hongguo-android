package com.hongguo.rewrite

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val SearchBg = Color(0xFF101010)
private val SearchTile = Color(0xFF222222)
private val SearchInk = Color(0xFFF5F5F5)
private val SearchMuted = Color(0xFF969696)

/**
 * Dedicated search discovery page based on the official Android search layout.
 * Ranking is sourced from real native ranking results, never screenshot fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchLandingScreen(
    state: AppState,
    model: DramaViewModel,
    onClose: () -> Unit,
    onResults: () -> Unit,
    onOpenDrama: (Drama) -> Unit = { model.open(it) },
) {
    val systemDark = isSystemInDarkTheme()
    val night = when (state.themeMode) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }
    // The search page honors the saved appearance mode independently of the
    // content theme preset. Values shadow the original dark screenshot palette.
    val SearchBg = if (night) Color(0xFF101010) else Color(0xFFFFFBF7)
    val SearchTile = if (night) Color(0xFF222222) else Color(0xFFF2EAE3)
    val SearchInk = if (night) Color(0xFFF5F5F5) else Color(0xFF30231F)
    val SearchMuted = if (night) Color(0xFF969696) else Color(0xFF806D63)
    var selectedBoard by remember { mutableIntStateOf(0) }
    var showActors by remember { mutableStateOf(false) }
    val browse by model.searchBrowse.collectAsStateWithLifecycle()
    var filtersOpen by remember { mutableStateOf(false) }
    var filterDraft by remember(browse.genre, filtersOpen) {
        mutableStateOf(browse.activeFilters)
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.loadSearchLanding() }
    if (showActors) {
        OpenHongguoExploreScreen(model,
            onClose = { showActors = false },
            initialTab = "actor",
            onOpenDrama = { drama ->
                onOpenDrama(drama)
                onClose()
            })
        return
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        model.searchBrowseQuery(text)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SearchBg)
            .testTag("search_landing"),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                        tint = SearchInk)
                }
                OutlinedTextField(
                    value = state.search,
                    onValueChange = model::setSearch,
                    modifier = Modifier.weight(1f).height(55.dp)
                        .testTag("search_landing_input"),
                    placeholder = {
                        Text("搜索短剧、演员或关键词",
                            color = SearchMuted, fontSize = 13.sp,
                            maxLines = 1)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, null, tint = SearchMuted,
                            modifier = Modifier.size(19.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SearchInk,
                        unfocusedTextColor = SearchInk,
                        cursorColor = SearchInk,
                        focusedContainerColor = SearchTile,
                        unfocusedContainerColor = SearchTile,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(state.search) }),
                )
                Text("搜索", color = SearchInk, fontSize = 15.sp,
                    modifier = Modifier.clickable { submit(state.search) }
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                        .testTag("search_landing_submit"))
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("推荐" to Section.RECOMMEND, "排行" to Section.RANK,
                    "上新" to Section.LATEST, "筛选" to Section.BROWSE,
                    "演员" to Section.SEARCH).forEach { (name, section) ->
                    Surface(
                        onClick = {
                            if (name == "演员") showActors = true
                            else model.searchBrowseSection(
                                if (browse.section == section) null else section)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (browse.section == section)
                            (if (night) Color(0xFF4A3530) else Color(0xFFE9D4C5))
                            else SearchTile,
                    ) {
                        Text(name, Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                            color = SearchInk, fontSize = 14.sp,
                            fontWeight = if (browse.section == section)
                                FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (browse.section != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(browse.section?.title.orEmpty(), color = SearchInk,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = model::refreshSearchBrowse) { Text("刷新") }
                    }
                    if (browse.section == Section.RANK) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            (if (state.backend.isBlank()) listOf(
                                "all" to "综合", "real" to "真人",
                                "comic" to "漫剧", "ai" to "AI")
                            else listOf("hot" to "热播", "new" to "新剧",
                                "recommend" to "推荐")).forEach { (value, label) ->
                                FilterChip(selected = browse.board == value,
                                    onClick = { model.searchBrowseBoard(value) },
                                    label = { Text(label) })
                            }
                        }
                    }
                    if (browse.section == Section.LATEST || browse.section == Section.BROWSE) {
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("short_play" to "真人", "comic_series" to "漫剧",
                                "ai_series" to "AI剧").forEach { (value, label) ->
                                FilterChip(selected = browse.genre == value,
                                    onClick = { model.searchBrowseGenre(value) },
                                    label = { Text(label) })
                            }
                        }
                    }
                    if (browse.section == Section.BROWSE) {
                        TextButton(onClick = { filtersOpen = true },
                            modifier = Modifier.testTag("search_browse_filters")) {
                            Text(if (browse.activeFilters.isEmpty()) "筛选条件"
                                else "筛选条件 · ${browse.activeFilters.size}")
                        }
                    }
                    if (browse.loading && browse.items.isEmpty())
                        CircularProgressIndicator(Modifier.size(26.dp))
                    browse.error?.let { Text(it, color = SearchMuted, fontSize = 13.sp) }
                    if (!browse.loading && browse.items.isEmpty() && browse.error == null)
                        Text("这里暂时没有剧集", color = SearchMuted)
                }
            }
            items(browse.items.chunked(3)) { dramas ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    dramas.forEach { drama ->
                        Column(Modifier.weight(1f)
                            .clickable { onOpenDrama(drama); onClose() }
                            .testTag("search_browse_drama_" + drama.id),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AsyncImage(model = model.cover(drama.cover),
                                contentDescription = drama.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(SearchTile))
                            Text(drama.title, color = SearchInk,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp)
                        }
                    }
                    repeat(3 - dramas.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (browse.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = model::moreSearchBrowse,
                        enabled = !browse.moreLoading,
                        modifier = Modifier.testTag("search_browse_more")) {
                        Text(if (browse.moreLoading) "正在加载…" else "加载更多")
                    }
                }
            }
        } else {
        if (state.search.isNotBlank() &&
            (state.suggestions.isNotEmpty() || state.suggestionsLoading)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("搜索建议", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = SearchInk)
                    if (state.suggestionsLoading)
                        Text("正在获取建议…", color = SearchMuted, fontSize = 12.sp)
                    state.suggestions.take(6).forEach { suggestion ->
                        Text(suggestion, color = SearchInk,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { submit(suggestion) }
                                .padding(vertical = 5.dp), fontSize = 13.sp)
                    }
                }
            }
        }
        if (state.recentSearches.isNotEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", fontSize = 17.sp,
                        color = SearchInk, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = model::clearSearchHistory) {
                        Icon(Icons.Outlined.DeleteOutline, "清空历史",
                            tint = SearchInk, modifier = Modifier.size(19.dp))
                    }
                }
                state.recentSearches.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { term ->
                            Text(term, modifier = Modifier.weight(1f)
                                .clickable { submit(term) }
                                .padding(vertical = 5.dp),
                                fontSize = 13.sp, color = SearchInk,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (state.landingTerms.isNotEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("猜你想搜", color = SearchInk, fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { model.loadSearchLanding(force = true) }) {
                        Icon(Icons.Outlined.Refresh, "刷新热搜", tint = SearchInk)
                    }
                }
                state.landingTerms.take(6).chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { term ->
                            Text(term, Modifier.weight(1f)
                                .clickable { submit(term) }
                                .padding(vertical = 5.dp),
                                fontSize = 13.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = SearchInk)
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 15.dp,
                    topEnd = 15.dp))
                    .background(Brush.verticalGradient(listOf(
                        if (night) Color(0xFF35251F) else Color(0xFFF4E5D9), SearchBg)))
                    .padding(top = 15.dp, start = 14.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf("热搜榜", "热点话题榜", "预约榜").forEachIndexed { index, title ->
                        Text(title, color = if (selectedBoard == index) SearchInk
                            else SearchMuted,
                            fontSize = 15.sp,
                            fontWeight = if (index == selectedBoard) FontWeight.Bold
                                else FontWeight.Normal,
                            modifier = Modifier.clickable { selectedBoard = index })
                    }
                }
                if (state.landingLoading && state.landingRank.isEmpty())
                    CircularProgressIndicator(Modifier.size(23.dp))
                if (state.landingError != null)
                    Text(state.landingError, color = SearchMuted, fontSize = 12.sp)
                if (selectedBoard == 1) {
                    if (state.landingTerms.isEmpty())
                        Text("暂无热点话题", color = SearchMuted)
                    else state.landingTerms.take(8).forEach { term ->
                        Text(term, color = SearchInk,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { submit(term) }.padding(vertical = 6.dp))
                    }
                } else if (selectedBoard == 2) {
                    Text("暂无已核实的预约榜数据",
                        color = SearchMuted, fontSize = 13.sp)
                }
            }
        }
        if (selectedBoard == 0) {
            items(state.landingRank.take(18).chunked(3)) { dramas ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    dramas.forEachIndexed { index, drama ->
                        val rank = state.landingRank.indexOfFirst { it.id == drama.id } + 1
                        Column(Modifier.weight(1f).clickable {
                            onOpenDrama(drama)
                            onClose()
                        }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.fillMaxWidth().height(160.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(SearchTile)) {
                                AsyncImage(
                                    model = model.cover(drama.cover),
                                    contentDescription = drama.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Text("$rank", color = Color.White, fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopStart)
                                        .background(Color(0x66000000))
                                        .padding(horizontal = 7.dp, vertical = 4.dp))
                            }
                            Text(drama.title, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = SearchInk, fontSize = 13.sp)
                            Text(if (drama.total > 0) "全${drama.total}集" else "热门短剧",
                                color = SearchMuted, fontSize = 11.sp)
                        }
                    }
                    repeat(3 - dramas.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        } // Landing content is shown only without a selected discovery tab
    }
    if (filtersOpen && browse.section == Section.BROWSE) {
        ModalBottomSheet(onDismissRequest = { filtersOpen = false },
            containerColor = SearchBg) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("筛选短剧", color = SearchInk, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { filterDraft = emptyMap() }) { Text("重置") }
                }
                if (browse.filters.isEmpty())
                    Text("当前分类没有可用的筛选条件", color = SearchMuted)
                browse.filters.forEach { group ->
                    Text(group.name, color = SearchInk, fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.options.forEach { option ->
                            FilterChip(selected = filterDraft[group.key].orEmpty() == option.value,
                                onClick = {
                                    filterDraft = filterDraft.toMutableMap().apply {
                                        if (option.value.isBlank()) remove(group.key)
                                        else put(group.key, option.value)
                                    }
                                },
                                label = { Text(option.label) })
                        }
                    }
                }
                Button(onClick = {
                    model.searchBrowseApplyFilters(filterDraft)
                    filtersOpen = false
                }, modifier = Modifier.fillMaxWidth().testTag("search_browse_apply")) {
                    Text("查看筛选结果")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
