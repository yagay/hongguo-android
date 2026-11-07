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
import kotlinx.coroutines.CancellationException

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
    )
    var tab by remember { mutableStateOf("new") }
    var cursor by remember { mutableStateOf("") }
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

    BackHandler { if (actorId.isNotBlank()) actorId = "" else onClose() }

    LaunchedEffect(tab, cursor, refresh) {
        loading = true
        error = null
        try {
            val page = source.browse(tab, cursor)
            rows = if (cursor.isEmpty()) page.items else
                (rows + page.items).distinctBy { it.id.ifBlank { it.title } }
            nextCursor = page.cursor
            more = page.hasMore && page.cursor.isNotBlank() && page.cursor != cursor
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "当前接口暂时不可用，可稍后重试"
            more = false
        } finally {
            loading = false
        }
    }

    LaunchedEffect(actorId, worksCursor, worksRefresh) {
        if (actorId.isBlank()) return@LaunchedEffect
        worksLoading = true
        try {
            if (worksCursor.isEmpty()) {
                actor = runCatching { source.person(actorId) }.getOrNull()
            }
            val page = source.personWorks(actorId, worksCursor)
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
                if (actorId.isBlank()) onClose() else actorId = ""
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
                                cursor = ""
                                rows = emptyList()
                                more = false
                                nextCursor = ""
                                error = null
                            }
                        },
                        label = { Text(name) })
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
                        enabled = row.actorUserId.isNotBlank() ||
                            (row.playable && row.id.matches(Regex("[1-9][0-9]{0,29}"))),
                        onClick = {
                            if (row.actorUserId.isNotBlank()) {
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
