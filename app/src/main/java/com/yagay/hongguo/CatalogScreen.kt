package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/**
 * Flat native catalog following the four navigation concepts of the website.
 * UI-only: paging, search and filters continue to use the existing ViewModel.
 */
@Composable
internal fun CatalogScreen(
    state: AppState, model: DramaViewModel,
    onMine: () -> Unit = {}, onSearchOpen: () -> Unit = {},
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var openSourceExplore by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    LaunchedEffect(state.section, state.board, state.genre) {
        grid.scrollToItem(0)
        // Opening the filter shortcut may switch from rank/latest to BROWSE.
        // Do not close the sheet immediately on that section transition.
        if (state.section != Section.BROWSE) filtersOpen = false
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
        topBar = { CatalogHeader(state, model, onMine,
            onSearchOpen = onSearchOpen,
            onFilters = {
                if (state.section != Section.BROWSE) model.section(Section.BROWSE)
                filtersOpen = true
            },
            onReservations = { model.show(Modal.ACCOUNT) },
            hasReservations = account.profile != null &&
                (account.reservationsLoaded ||
                    account.upcoming.isNotEmpty() || account.online.isNotEmpty())) },
    ) { insets ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = grid,
            modifier = Modifier.fillMaxSize().padding(insets).testTag("web_catalog"),
            contentPadding = PaddingValues(start = HongguoUi.pagePadding, top = 10.dp,
                end = HongguoUi.pagePadding, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            // Continue-watching is a theater feature, not a RECOMMEND-only
            // widget. Use the saved cloud checkpoint when it is available,
            // otherwise open the local history entry at its recorded episode.
            val cloudRecent = account.history.firstOrNull()
            val localRecent = state.library.firstOrNull()
            if (cloudRecent != null || localRecent != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val cloud = cloudRecent
                    val local = localRecent
                    val drama = if (cloud != null)
                        Drama(cloud.series.id, cloud.series.title, cloud.series.cover)
                    else local!!.drama
                    Surface(
                        shape = RoundedCornerShape(HongguoUi.cardRadius),
                        color = HongguoColors.surface,
                    ) {
                        Row(Modifier.fillMaxWidth().clickable {
                            if (cloud != null) model.openCloudProgress(cloud)
                            else if (local != null) model.open(
                                local.drama, resumeEp = local.episode)
                        }.padding(9.dp).testTag("theater_continue_watching"),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = model.cover(drama.cover),
                                contentDescription = drama.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(48.dp).aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("继续播放", color = HongguoColors.accent,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(drama.title, color = HongguoColors.ink,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (cloud != null)
                                    "第${cloud.episodeIndex + 1}集 · " +
                                        PlaybackTimeFormatter.format(cloud.position)
                                    else "看到第${local?.episode ?: 1}集",
                                    color = HongguoColors.muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Outlined.ChevronRight,
                                contentDescription = "继续观看",
                                tint = HongguoColors.accent,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                }
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
                    HongguoEmptyState(
                        if (state.section == Section.SEARCH) "尝试搜索其他剧名"
                        else "这里暂时没有剧集",
                        modifier = Modifier.height(170.dp),
                    )
                }
            }
            items(state.items.filter { it.id !in state.hiddenRecommendationIds ||
                state.section != Section.RECOMMEND }, key = { it.id }) { drama ->
                CatalogPoster(drama, model.cover(drama.cover),
                    sourceGenre = if (state.section in setOf(Section.BROWSE, Section.LATEST))
                        state.genre else null,
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
private fun CatalogHeader(state: AppState, model: DramaViewModel,
    onMine: () -> Unit, onSearchOpen: () -> Unit,
    onFilters: () -> Unit,
    onReservations: () -> Unit,
    hasReservations: Boolean) {
    Column(Modifier.fillMaxWidth().background(HongguoColors.surface),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Search is a single entrypoint. Category selection remains within
        // the theater; it never replaces or restarts the swipe-feed player.
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
                .clickable(onClick = onSearchOpen).testTag("theater_search"),
            shape = RoundedCornerShape(HongguoUi.controlRadius),
            color = HongguoColors.background,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = null,
                    tint = HongguoColors.muted, modifier = Modifier.size(21.dp))
                Text("搜索短剧、演员或关键词", color = HongguoColors.muted,
                    fontSize = 14.sp, modifier = Modifier.weight(1f))
            }
        }
        // A single category navigation; no duplicated quick-action row.
        // Genre chips and the filter action live with the content section.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(25.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "找剧" to Section.BROWSE,
                "排行榜" to Section.RANK,
                "上新" to Section.LATEST,
            ).forEachIndexed { index, (title, section) ->
                val selected = state.section == section
                Text(
                    title,
                    color = if (selected) HongguoColors.ink else HongguoColors.muted,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable {
                        if (!selected) model.section(section)
                    }.padding(vertical = 8.dp).testTag("theater_category_$index"),
                )
            }
            if (hasReservations) {
                Text(
                    "预约",
                    fontSize = 14.sp,
                    color = HongguoColors.muted,
                    modifier = Modifier.clickable(onClick = onReservations)
                        .padding(vertical = 8.dp).testTag("theater_reservations"),
                )
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
    HongguoIconAction(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = HongguoColors.ink,
            modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CatalogSectionTools(state: AppState, model: DramaViewModel,
                                onFilters: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state.section) {
                    Section.RECOMMEND -> "为你推荐"
                    Section.RANK -> "人气榜单"
                    Section.LATEST -> "最近上新"
                    Section.BROWSE -> "发现短剧"
                    Section.SEARCH -> "搜索结果"
                },
                fontSize = HongguoUi.sectionTitleSize, fontWeight = FontWeight.Bold,
                color = HongguoColors.ink, modifier = Modifier.weight(1f),
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
            if (state.section == Section.BROWSE) {
                TextButton(onClick = onFilters,
                    modifier = Modifier.testTag("open_filters")) {
                    Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.activeFilters.isEmpty()) "筛选"
                         else "筛选 · " + state.activeFilters.size,
                         fontSize = 12.sp, color = HongguoColors.accent)
                }
            } else if (state.items.isNotEmpty()) {
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
    }
}

@Composable
private fun CompactChoices(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) = HongguoChoiceGroup(
    options = choices,
    selected = selected,
    onSelect = onSelect,
    tag = "theater_choice",
)

@Composable
private fun CatalogPoster(drama: Drama, cover: String,
    sourceGenre: String? = null, savedLater: Boolean,
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
            // The selected native category is a verified source scope.
            // Never infer a type on mixed-ranking or global-search results.
            val sourceType = when (sourceGenre) {
                "short_play" -> "真人剧"
                "comic_series" -> "漫剧"
                "ai_series" -> "AI剧"
                else -> null
            }
            if (sourceType != null) {
                Text(sourceType,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.62f),
                            RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                        .testTag("poster_source_type_" + drama.id),
                    color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium)
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
            val officialEpisodeStatus = DramaCompletion.episodeLabel(
                drama.total, drama.completionStatus, null)
            if (officialEpisodeStatus.isNotBlank()) {
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f))))
                    .padding(start = 7.dp, top = 18.dp, bottom = 6.dp)) {
                    Text(officialEpisodeStatus, color = Color.White,
                        fontWeight = FontWeight.Medium, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            drama.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = HongguoColors.ink,
        )
        if (drama.categories.isNotEmpty()) {
            // Horizontally scrollable real genre chips retain individual labels
            // without squeezing the poster or cutting off all but one label.
            Row(Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("poster_categories_" + drama.id),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                drama.categories.take(5).forEach { category ->
                    Text(category, color = HongguoColors.muted, fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.background(HongguoColors.surface,
                            RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 3.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogFiltersSheet(
    state: AppState,
    model: DramaViewModel,
    onClose: () -> Unit,
) {
    // Draft changes are intentionally local to this sheet. Closing without
    // applying never modifies the catalog's active filters or restarts paging.
    var draft by remember(state.genre) { mutableStateOf(state.activeFilters) }
    val selectedCount = draft.values.count { it.isNotBlank() }
    // Keep the apply action outside the scrolling list. On short screens
    // filter groups scroll, while the confirmation button stays visible.
    val maxOptionsHeight = (LocalConfiguration.current.screenHeightDp * 0.58f).dp

    HongguoModalSheet(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("catalog_filter_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = HongguoUi.pagePadding),
        ) {
            HongguoSheetHeader(title = "筛选短剧") {
                Text(
                    text = if (selectedCount == 0) "全部条件" else "已选 ${selectedCount} 项",
                    fontSize = 11.sp,
                    color = HongguoColors.muted,
                    modifier = Modifier.testTag("catalog_filter_selected_count"),
                )
                HongguoAction(
                    label = "重置",
                    onClick = { draft = emptyMap() },
                    enabled = selectedCount > 0,
                    style = HongguoActionStyle.QUIET,
                    modifier = Modifier.testTag("catalog_filter_reset"),
                )
            }
            HorizontalDivider(
                color = HongguoColors.divider,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (state.filters.isEmpty()) {
                Text(
                    "当前分类暂时没有可用筛选条件",
                    color = HongguoColors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = maxOptionsHeight)
                        .testTag("catalog_filter_scroll_content"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(state.filters.size, key = { state.filters[it].key }) { index ->
                        val group = state.filters[index]
                        val activeValue = draft[group.key].orEmpty()
                        val activeLabel = group.options.firstOrNull {
                            it.value.isNotBlank() && it.value == activeValue
                        }?.label
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .testTag("catalog_filter_group_${group.key}"),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    group.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HongguoColors.ink,
                                    modifier = Modifier.weight(1f),
                                )
                                if (activeLabel != null) {
                                    Text(
                                        activeLabel,
                                        fontSize = 11.sp,
                                        color = HongguoColors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Wrap chips instead of horizontalScroll: every
                            // available filter stays visible without sideways
                            // searching or a clipped right-hand edge.
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                group.options.forEachIndexed { optionIndex, option ->
                                    HongguoChoiceChip(
                                        label = option.label,
                                        selected = activeValue == option.value,
                                        onClick = {
                                            draft = draft.toMutableMap().apply {
                                                if (option.value.isBlank()) remove(group.key)
                                                else put(group.key, option.value)
                                            }
                                        },
                                        modifier = Modifier.testTag(
                                            "catalog_filter_option_${group.key}_${optionIndex}"
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(
                color = HongguoColors.divider,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            Button(
                onClick = {
                    model.applyCatalogFilters(draft)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .testTag("apply_filters"),
                shape = RoundedCornerShape(HongguoUi.controlRadius),
            ) {
                Text("查看筛选结果", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
