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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One scrollable grid for every episode AND related works.
 *
 * The previous nested verticalScroll + LazyVerticalGrid clipped the last row
 * and made the related section scroll separately from the episode grid.
 * The title/current episode stay visible while the one grid scrolls.
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
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val maxGridHeight = (screenHeight * 0.64f).dp
    val currentIndex = state.episodeIndex.coerceIn(0, detail.episodes.lastIndex)
    val currentEpisode = detail.episodes[currentIndex].index
    val initialItem = if (currentIndex > 25) (currentIndex / 5 - 1) * 5 else 0
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = initialItem)

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
                Text("点击集数切换", color = HongguoColors.muted, fontSize = 11.sp)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                state = gridState,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = maxGridHeight)
                    .testTag("episode_and_related_grid"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(
                    detail.episodes,
                    key = { index, _ -> "episode_${index}" },
                ) { index, episode ->
                    HongguoEpisodeCell(
                        label = episode.index.toString(),
                        selected = index == state.episodeIndex,
                        onClick = { onSelectEpisode(index) },
                        modifier = Modifier.fillMaxWidth().testTag("episode_" + index),
                        cellHeight = 40.dp,
                    )
                }

                item(key = "related_heading", span = { GridItemSpan(5) }) {
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

                item(key = "related_grid", span = { GridItemSpan(5) }) {
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
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HongguoUi.controlRadius))
            .background(HongguoColors.background)
            .clickable(enabled = !work.upcoming, onClick = onClick)
            .padding(7.dp)
            .testTag("related_work_" + work.drama.id),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HongguoPosterCover(
            cover = cover,
            title = work.drama.title,
            modifier = Modifier.size(width = 57.dp, height = 83.dp),
        )
        Column(
            Modifier.weight(1f).heightIn(min = 83.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                work.drama.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = HongguoColors.ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                work.season.ifBlank { "相关作品" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = HongguoColors.muted,
            )
            Text(
                if (work.upcoming) "待上线" else "观看",
                fontSize = 11.sp,
                color = if (work.upcoming) HongguoColors.muted
                    else HongguoColors.accent,
            )
        }
    }
}
