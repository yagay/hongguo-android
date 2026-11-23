package com.yagay.hongguo

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage


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
    // Follow the same selected palette as Theater/My/Settings instead of
    // maintaining an unrelated hard-coded search light/dark palette.
    val SearchBg = HongguoColors.background
    val SearchTile = HongguoColors.surface
    val SearchInk = HongguoColors.ink
    val SearchMuted = HongguoColors.muted
    val browse by model.searchBrowse.collectAsStateWithLifecycle()
    val posterColumns = if (HongguoColors.isMaterial3)
        HongguoThemePolicy.galleryColumns(LocalConfiguration.current.screenWidthDp,
            LocalDensity.current.fontScale) else 3
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.loadSearchLanding() }
    fun submit(text: String) {
        if (text.isBlank()) return
        model.searchBrowseQuery(text)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SearchBg)
            .testTag("search_landing"),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(HongguoUi.pageSectionGap),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = HongguoUi.pagePadding, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HongguoIconAction(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                        tint = SearchInk)
                }
                HongguoSearchField(
                    value = state.search,
                    onValueChange = model::setSearch,
                    modifier = Modifier.weight(1f).height(55.dp)
                        .testTag("search_landing_input"),
                    compact = true,
                    onSearch = { submit(state.search) },
                )
                if (HongguoColors.isMaterial3) {
                    HongguoAction(
                        label = "搜索",
                        onClick = { submit(state.search) },
                        modifier = Modifier.testTag("search_landing_submit"),
                        style = HongguoActionStyle.QUIET,
                    )
                } else {
                    Text("搜索", color = SearchInk, fontSize = 15.sp,
                        modifier = Modifier.clickable { submit(state.search) }
                            .padding(horizontal = 6.dp, vertical = 12.dp)
                            .testTag("search_landing_submit"))
                }
            }
        }
        if (browse.section == Section.SEARCH && browse.query.isNotBlank() &&
            state.search.trim() == browse.query) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = HongguoUi.pagePadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(browse.section?.title.orEmpty(), color = SearchInk,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        HongguoAction(
                            label = "刷新", onClick = model::refreshSearchBrowse,
                            style = HongguoActionStyle.QUIET,
                        )
                    }
                    Text("关键词：${browse.query}",
                        color = SearchMuted, fontSize = 12.sp)
                    if (browse.loading && browse.items.isEmpty())
                        HongguoInlineLoading()
                    browse.error?.let { Text(it, color = SearchMuted, fontSize = 13.sp) }
                    if (!browse.loading && browse.items.isEmpty() && browse.error == null)
                        HongguoEmptyState(
                            if (browse.hasMore) "当前页没有匹配作品，可加载更多"
                            else "没有找到匹配作品",
                        )
                }
            }
            items(browse.items.chunked(posterColumns)) { dramas ->
                Row(Modifier.fillMaxWidth().padding(horizontal = HongguoUi.pagePadding),
                    horizontalArrangement = Arrangement.spacedBy(
                        if (HongguoColors.isMaterial3) 12.dp else 9.dp)) {
                    dramas.forEach { drama ->
                        Column(Modifier.weight(1f)
                            .then(if (HongguoColors.isMaterial3)
                                Modifier.clip(RoundedCornerShape(18.dp))
                                    .background(SearchTile)
                                    .padding(7.dp)
                            else Modifier)
                            .clickable { onOpenDrama(drama); onClose() }
                            .testTag("search_browse_drama_" + drama.id),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HongguoPosterCover(
                                cover = model.cover(drama.cover),
                                title = drama.title,
                                modifier = if (HongguoColors.isMaterial3)
                                    Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                else Modifier.fillMaxWidth().height(150.dp),
                            )
                            Text(drama.title, color = SearchInk,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                fontSize = if (HongguoColors.isMaterial3) 14.sp else 12.sp,
                                lineHeight = if (HongguoColors.isMaterial3) 19.sp else 16.sp)
                        }
                    }
                    repeat(posterColumns - dramas.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (browse.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HongguoAction(
                        label = if (browse.moreLoading) "正在加载…" else "加载更多",
                        onClick = model::moreSearchBrowse,
                        enabled = !browse.moreLoading,
                        modifier = Modifier.testTag("search_browse_more"),
                        style = HongguoActionStyle.QUIET,
                    )
                }
            }
        } else {
        if (state.search.isNotBlank() &&
            (state.suggestions.isNotEmpty() || state.suggestionsLoading)) {
            item {
                Column(Modifier.padding(horizontal = HongguoUi.pagePadding),
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
            Column(Modifier.fillMaxWidth().padding(horizontal = HongguoUi.pagePadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", fontSize = 17.sp,
                        color = SearchInk, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    HongguoIconAction(onClick = model::clearSearchHistory) {
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
            Column(Modifier.fillMaxWidth().padding(horizontal = HongguoUi.pagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("猜你想搜", color = SearchInk, fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold)
                    HongguoIconAction(onClick = { model.loadSearchLanding(force = true) }) {
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
        } // Search suggestions/history, not another catalog tab
    }
}
