package com.hongguo.rewrite

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.openhongguo.Person
import dev.openhongguo.Work
import dev.openhongguo.FilterRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Independent read-only discovery built on the published openhongguo Android SDK.
 * Never changes the app's home 3-card pagination, official reel queue, or account.
 */
@Composable
internal fun OpenHongguoExploreScreen(
    model: DramaViewModel,
    onClose: () -> Unit,
) {
    val source = model.openHongguoDiscovery
    val tabs = listOf(
        "new" to "上新",
        "human" to "真人榜",
        "actor" to "演员榜",
        "comic" to "漫剧榜",
        "ai" to "AI榜",
        "hot" to "热搜",
        "filter" to "真人筛选",
        "filterComic" to "漫剧筛选",
        "filterAI" to "AI筛选",
        "discover" to "书城频道",
        "search" to "SDK搜索",
        "rankGroups" to "书城榜单",
        "activity" to "活动榜",
        "category" to "书籍分类",
        "calendar" to "指定日期",
    )
    var tab by remember { mutableStateOf("new") }
    var cursor by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    var rankCell by remember { mutableStateOf("") }
    var filterRows by remember { mutableStateOf(emptyList<FilterRow>()) }
    var selectedFilters by remember { mutableStateOf(emptyMap<String, List<String>>()) }
    var rows by remember { mutableStateOf(emptyList<OpenHongguoBrowseItem>()) }
    var more by remember { mutableStateOf(false) }
    var nextCursor by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var actorId by remember { mutableStateOf("") }
    var actor by remember { mutableStateOf<Person?>(null) }
    var works by remember { mutableStateOf(emptyList<Work>()) }
    var worksCursor by remember { mutableStateOf("") }
    var worksMore by remember { mutableStateOf(false) }
    var nextWorksCursor by remember { mutableStateOf("") }
    var worksLoading by remember { mutableStateOf(false) }
    var worksRefresh by remember { mutableIntStateOf(0) }
    var suggestionRequest by remember { mutableIntStateOf(0) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }

    BackHandler {
        when {
            actorId.isNotBlank() -> actorId = ""
            rankCell.isNotBlank() -> { rankCell = ""; cursor = ""; rows = emptyList() }
            else -> onClose()
        }
    }

    LaunchedEffect(tab) {
        filterRows = emptyList()
        if (tab in setOf("filter", "filterComic", "filterAI")) {
            try {
                val genre = when (tab) {
                    "filterComic" -> "comic_series"
                    "filterAI" -> "ai_series"
                    else -> "short_play"
                }
                filterRows = withContext(Dispatchers.IO) {
                    source.filterPanel(genre)
                }.filter { it.key != "genre" }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Filter defaults stay usable. */ }
        }
    }

    LaunchedEffect(tab, cursor, refresh, activeQuery, rankCell, selectedFilters) {
        if (tab in setOf("search", "calendar", "category") && activeQuery.isBlank()) {
            rows = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val page = if (tab in setOf("discover", "search", "calendar",
                    "category", "rankGroups") || rankCell.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    source.browseExtended(
                        if (rankCell.isNotBlank()) "rankRows" else tab,
                        if (rankCell.isNotBlank()) rankCell else activeQuery,
                        cursor)
                }
            } else withContext(Dispatchers.IO) {
                source.browse(tab, cursor, selectedFilters)
            }
            rows = if (cursor.isEmpty()) page.items else
                (rows + page.items).distinctBy { it.id.ifBlank { it.title } }
            nextCursor = page.cursor
            more = page.hasMore && page.cursor.isNotBlank() && page.cursor != cursor
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            error = e.message ?: "查询参数有误"
            more = false
        } catch (_: Exception) {
            error = "当前接口暂时不可用，可稍后重试"
            more = false
        } finally {
            loading = false
        }
    }

    LaunchedEffect(suggestionRequest) {
        if (suggestionRequest == 0 || tab != "search") return@LaunchedEffect
        try {
            suggestions = if (query.trim().isBlank())
                withContext(Dispatchers.IO) { source.searchCues() }.take(10)
            else withContext(Dispatchers.IO) { source.suggest(query.trim()) }.map {
                it.keyword.ifBlank { it.text }
            }.filter(String::isNotBlank).distinct().take(10)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { suggestions = emptyList() }
    }

    LaunchedEffect(actorId, worksCursor, worksRefresh) {
        if (actorId.isBlank()) return@LaunchedEffect
        worksLoading = true
        try {
            if (worksCursor.isEmpty()) {
                actor = runCatching { withContext(Dispatchers.IO) { source.person(actorId) } }
                    .onFailure { if (it is CancellationException) throw it }.getOrNull()
            }
            val page = withContext(Dispatchers.IO) {
                source.personWorks(actorId, worksCursor)
            }
            works = if (worksCursor.isEmpty()) page.items else
                (works + page.items).distinctBy(Work::seriesId)
            nextWorksCursor = page.cursor
            worksMore = page.hasMore && page.cursor.isNotBlank() &&
                page.cursor != worksCursor
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "演员作品暂不可用"
            worksMore = false
        } finally {
            worksLoading = false
        }
    }

    Column(Modifier.fillMaxSize().background(HongguoColors.background)
        .testTag("openhongguo_explore")) {
        Row(Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                when {
                    actorId.isNotBlank() -> actorId = ""
                    rankCell.isNotBlank() -> {
                        rankCell = ""; cursor = ""; rows = emptyList()
                    }
                    else -> onClose()
                }
            }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回",
                    tint = HongguoColors.ink)
            }
            Text(if (actorId.isBlank()) "更多发现" else "演员作品",
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                color = HongguoColors.ink)
            Spacer(Modifier.weight(1f))
            if (actorId.isBlank()) TextButton(onClick = { refresh++ },
                enabled = !loading) {
                Text("刷新", color = HongguoColors.accent)
            }
        }
        if (actorId.isBlank()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                tabs.forEach { (id, name) ->
                    FilterChip(selected = tab == id,
                        onClick = {
                            if (id != tab) {
                                tab = id
                                query = ""
                                activeQuery = ""
                                rankCell = ""
                                cursor = ""
                                rows = emptyList()
                                more = false
                                nextCursor = ""
                                error = null
                                selectedFilters = emptyMap()
                                suggestions = emptyList()
                            }
                        },
                        label = { Text(name) })
                }
            }
        }
        if (actorId.isBlank() && tab in setOf("filter", "filterComic", "filterAI") &&
            filterRows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                filterRows.take(8).forEach { row ->
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, fontSize = 12.sp, color = HongguoColors.muted)
                        FilterChip(selected = selectedFilters[row.key].isNullOrEmpty(),
                            onClick = {
                                selectedFilters = selectedFilters - row.key
                                cursor = ""; rows = emptyList(); nextCursor = ""
                            }, label = { Text("全部") })
                        row.options.take(12).forEach { option ->
                            FilterChip(
                                selected = selectedFilters[row.key]?.contains(option.value) == true,
                                onClick = {
                                    val old = selectedFilters[row.key].orEmpty()
                                    selectedFilters = if (option.value in old)
                                        selectedFilters - row.key
                                    else selectedFilters + (row.key to listOf(option.value))
                                    cursor = ""; rows = emptyList(); nextCursor = ""
                                },
                                label = { Text(option.name) },
                            )
                        }
                    }
                }
            }
        }
        if (actorId.isBlank() && tab in setOf("search", "discover",
                "calendar", "category", "activity")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(120) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(when (tab) {
                        "search" -> "搜索关键词"
                        "discover" -> "频道（空为推荐）"
                        "calendar" -> "日期 YYYYMMDD"
                        "activity" -> "活动ID:榜单ID（留空使用 SDK 默认值）"
                        else -> "书籍分类 ID"
                    }) },
                )
                if (tab == "search") TextButton(onClick = { suggestionRequest++ }) {
                    Text("联想", fontSize = 12.sp, color = HongguoColors.accent)
                }
                OutlinedButton(onClick = {
                    activeQuery = query.trim()
                    cursor = ""
                    rows = emptyList()
                    nextCursor = ""
                    refresh++
                }) { Text("查询") }
            }
        }
        if (actorId.isBlank() && tab == "search" && suggestions.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { phrase ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            query = phrase
                            activeQuery = phrase
                            suggestions = emptyList()
                            cursor = ""; rows = emptyList(); nextCursor = ""
                        },
                        label = { Text(phrase.take(40)) },
                    )
                }
            }
        }
        if (actorId.isNotBlank()) {
            actor?.let { person ->
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (person.avatarUrl.isNotBlank()) AsyncImage(
                        model = model.cover(person.avatarUrl),
                        contentDescription = person.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(76.dp).clip(RoundedCornerShape(38.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(person.name, color = HongguoColors.ink,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        if (person.description.isNotBlank()) Text(person.description,
                            color = HongguoColors.muted, fontSize = 12.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (person.tags.isNotEmpty()) Text(person.tags.joinToString(" · "),
                            color = HongguoColors.accent, fontSize = 11.sp)
                    }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (actorId.isBlank()) {
                items(rows, key = { "browse_${tab}_${it.id}_${it.title}" }) { row ->
                    BrowseEntry(
                        title = row.title, cover = model.cover(row.cover),
                        subtitle = row.subtitle.ifBlank {
                            if (row.episodes > 0) "全 ${row.episodes} 集" else ""
                        },
                        enabled = (tab == "rankGroups" && rankCell.isBlank() &&
                                row.id.isNotBlank()) ||
                            row.searchQuery.isNotBlank() ||
                            row.actorUserId.isNotBlank() ||
                            (row.playable && row.id.matches(Regex("[1-9][0-9]{0,29}"))),
                        onClick = {
                            if (row.searchQuery.isNotBlank()) {
                                tab = "search"
                                query = row.searchQuery
                                activeQuery = row.searchQuery
                                cursor = ""; rows = emptyList(); nextCursor = ""
                            } else if (tab == "rankGroups" && rankCell.isBlank()) {
                                rankCell = row.id
                                cursor = ""
                                rows = emptyList()
                                nextCursor = ""
                            } else if (row.actorUserId.isNotBlank()) {
                                actorId = row.actorUserId
                                actor = null
                                works = emptyList()
                                worksCursor = ""
                                worksMore = false
                                nextWorksCursor = ""
                                error = null
                            } else {
                                onClose()
                                model.open(Drama(row.id, row.title, row.cover,
                                    row.episodes))
                            }
                        },
                    )
                }
            } else {
                items(works, key = { "work_${it.seriesId}" }) { work ->
                    BrowseEntry(
                        title = work.title, cover = model.cover(work.coverUrl),
                        subtitle = work.tags.joinToString(" · "),
                        enabled = work.seriesId.matches(Regex("[1-9][0-9]{0,29}")),
                        onClick = {
                            onClose()
                            model.open(Drama(work.seriesId, work.title,
                                work.coverUrl, work.episodeCount, work.description))
                        },
                    )
                }
            }
            if (loading || worksLoading) item {
                Box(Modifier.fillMaxWidth().padding(22.dp),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HongguoColors.accent)
                }
            }
            error?.let { message -> item {
                Column(Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, fontSize = 13.sp, color = HongguoColors.muted)
                    OutlinedButton(onClick = {
                        if (actorId.isBlank()) refresh++
                        else worksRefresh++
                    }) { Text("重试") }
                }
            } }
            if (actorId.isNotBlank() && worksMore && !worksLoading) item {
                Box(Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = {
                        if (nextWorksCursor.isNotBlank()) worksCursor = nextWorksCursor
                    }) { Text("加载更多作品") }
                }
            }
            if (actorId.isBlank() && more && !loading) item {
                Box(Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = { if (nextCursor.isNotBlank())
                        cursor = nextCursor }) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
private fun BrowseEntry(
    title: String, cover: String, subtitle: String,
    enabled: Boolean, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (cover.isNotBlank()) AsyncImage(model = cover,
            contentDescription = title, contentScale = ContentScale.Crop,
            modifier = Modifier.width(72.dp).height(102.dp)
                .clip(RoundedCornerShape(11.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp, color = HongguoColors.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle,
                fontSize = 12.sp, color = HongguoColors.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!enabled) Text("尚未上线 / 无可用入口",
                color = HongguoColors.muted, fontSize = 11.sp)
        }
    }
}
