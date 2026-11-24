package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Independent tabs for episodes and related works. Each page keeps its own
 * lazy grid scroll position and never nests independently scrolling grids.
 */
@Composable
internal fun ReelEpisodeSelectionSheet(
    detail: DramaDetail,
    state: AppState,
    model: DramaViewModel,
    onDismiss: () -> Unit,
    onDownloadFive: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onRelatedDrama: (RelatedDrama) -> Unit,
    onReserveUpcoming: (RelatedDrama) -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val maxGridHeight = (screenHeight * 0.64f).dp
    var relatedTab by rememberSaveable(detail.drama.id) { mutableStateOf(false) }
    val currentIndex = state.episodeIndex.coerceIn(0, detail.episodes.lastIndex)
    val currentEpisode = detail.episodes[currentIndex].index
    val episodeColumns = if (HongguoColors.isMaterial3)
        HongguoThemePolicy.episodeColumns(
            LocalConfiguration.current.screenWidthDp,
            LocalDensity.current.fontScale > 1.3f)
        else 5
    val initialItem = if (currentIndex > episodeColumns * 5)
        ((currentIndex / episodeColumns) - 1) * episodeColumns else 0
    val episodeGridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialItem)
    val relatedGridState = rememberLazyGridState()

    HongguoPlayerSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reel_episodes_sheet"),
    ) {
        HongguoPlayerPanel(
            title = "选集",
            actions = {
                Text(
                    "共${detail.episodes.size}集",
                    color = HongguoColors.muted,
                    fontSize = 12.sp,
                )
                HongguoPlayerSheetAction(
                    label = "下载5集",
                    onClick = onDownloadFive,
                    modifier = Modifier.testTag("batch_download_5"),
                )
            },
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(HongguoUi.controlRadius))
                        .background(HongguoColors.accentSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("episode_current_indicator"),
                ) {
                    Text(
                        "当前 · 第${currentEpisode}集",
                        color = HongguoColors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(if (relatedTab) "相关作品" else "点击集数切换",
                    color = HongguoColors.muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth().testTag("episode_section_tabs"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HongguoChoiceChip(
                    label = "选集",
                    selected = !relatedTab,
                    onClick = { relatedTab = false },
                    modifier = Modifier.testTag("episode_tab_episodes"),
                )
                HongguoChoiceChip(
                    label = "相关推荐",
                    selected = relatedTab,
                    onClick = {
                        relatedTab = true
                        if (state.relatedSeries != detail.drama.id && !state.relatedLoading) {
                            model.loadRelated(detail.drama.id)
                        }
                    },
                    modifier = Modifier.testTag("episode_tab_related"),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(episodeColumns),
                state = if (relatedTab) relatedGridState else episodeGridState,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = maxGridHeight)
                    .testTag("episode_and_related_grid"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (!relatedTab) itemsIndexed(
                    detail.episodes,
                    key = { index, _ -> "episode_${index}" },
                ) { index, episode ->
                    HongguoEpisodeCell(
                        label = episode.index.toString(),
                        selected = index == state.episodeIndex,
                        onClick = { onSelectEpisode(index) },
                        modifier = Modifier.fillMaxWidth().testTag("episode_" + index),
                        cellHeight = if (HongguoColors.isMaterial3) 48.dp else 40.dp,
                    )
                }

                if (relatedTab) item(
                    key = "related_heading", span = { GridItemSpan(episodeColumns) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        HongguoMenuSeparator()
                        HongguoSheetHeader(title = "其他季 / 相关作品") {
                            if (state.relatedLoading && state.relatedSeries == detail.drama.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = HongguoColors.accent,
                                )
                            } else {
                                HongguoIconAction(
                                    onClick = { model.loadRelated(detail.drama.id) },
                                    modifier = Modifier.testTag("related_refresh"),
                                ) {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = "换一批相关作品",
                                        tint = HongguoColors.ink,
                                    )
                                }
                            }
                        }
                    }
                }

                if (relatedTab) item(
                    key = "related_grid", span = { GridItemSpan(episodeColumns) }) {
                    val currentRelated = state.relatedSeries == detail.drama.id
                    val related = if (currentRelated) state.relatedWorks.take(15)
                        else emptyList()
                    if (related.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().testTag("related_two_column_grid"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            related.chunked(2).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    row.forEach { work ->
                                        ReelRelatedDramaCard(
                                            work = work,
                                            cover = model.cover(work.drama.cover),
                                            modifier = Modifier.weight(1f),
                                            onClick = { onRelatedDrama(work) },
                                            onReserve = { onReserveUpcoming(work) },
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        Text(
                            when {
                                !currentRelated || state.relatedLoading -> "正在获取相关作品…"
                                else -> state.relatedError ?: "暂无官方关联作品"
                            },
                            color = HongguoColors.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}

/** Real poster/title/season data, rather than an unlabelled single-line link. */
@Composable
private fun ReelRelatedDramaCard(
    work: RelatedDrama,
    cover: Any?,
    modifier: Modifier,
    onClick: () -> Unit,
    onReserve: () -> Unit,
) {
    val canReserve = work.upcoming && work.reservationItemId != null
    HongguoPosterInfoRow(
        cover = cover,
        title = work.drama.title,
        subtitle = work.season.ifBlank { "相关作品" },
        status = when {
            canReserve -> "待上线 · 预约"
            work.upcoming -> "待上线"
            else -> "观看"
        },
        statusHighlighted = canReserve || !work.upcoming,
        size = HongguoPosterRowSize.COMPACT,
        modifier = modifier
            .clip(RoundedCornerShape(HongguoUi.controlRadius))
            .background(HongguoColors.background)
            .clickable(enabled = !work.upcoming || canReserve) {
                if (work.upcoming) onReserve() else onClick()
            }
            .padding(7.dp)
            .testTag("related_work_" + work.drama.id),
    )
}
