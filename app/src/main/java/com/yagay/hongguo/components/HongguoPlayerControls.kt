package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
internal fun HongguoPlayerSeekTrack(
    durationMs: Long,
    playedFraction: Float,
    onSeek: (Long) -> Unit,
    onPreview: (Float?) -> Unit,
    externalPreviewFraction: Float? = null,
    modifier: Modifier = Modifier,
) {
    var dragging by remember(durationMs) { mutableStateOf<Float?>(null) }
    val currentSeek by rememberUpdatedState(onSeek)
    val currentPreview by rememberUpdatedState(onPreview)
    val seekInsetPx = with(LocalDensity.current) {
        HongguoUi.playbackSeekHorizontalInset.toPx()
    }
    // DrawScope is not composable; read colors before entering Canvas.
    val trackColor = HongguoColors.playerChromeTrack
    val playedColor = HongguoColors.accent
    Canvas(
        modifier.fillMaxWidth()
            .height(HongguoUi.playbackSeekTouchHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    playedFraction.coerceIn(0f, 1f), 0f..1f,
                )
                setProgress { value ->
                    if (durationMs <= 0L) false
                    else {
                        currentSeek(PlaybackSeekGeometry.positionAt(durationMs, value))
                        true
                    }
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { point ->
                    if (durationMs > 0L && size.width > 0) {
                        val fraction = PlaybackSeekGeometry.fractionAt(
                            point.x, size.width.toFloat(), seekInsetPx)
                        currentSeek(PlaybackSeekGeometry.positionAt(durationMs, fraction))
                    }
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { start ->
                        if (durationMs > 0L && size.width > 0) {
                            val value = PlaybackSeekGeometry.fractionAt(
                                start.x, size.width.toFloat(), seekInsetPx)
                            dragging = value
                            currentPreview(value)
                        }
                    },
                    onDragEnd = {
                        dragging?.let {
                            currentSeek(PlaybackSeekGeometry.positionAt(durationMs, it))
                        }
                        dragging = null
                        currentPreview(null)
                    },
                    onDragCancel = {
                        dragging = null
                        currentPreview(null)
                    },
                    onDrag = { change, _ ->
                        if (durationMs > 0L && size.width > 0) {
                            val value = PlaybackSeekGeometry.fractionAt(
                                change.position.x, size.width.toFloat(), seekInsetPx)
                            dragging = value
                            currentPreview(value)
                        }
                        change.consume()
                    },
                )
            },
    ) {
        val y = size.height / 2f
        val inset = PlaybackSeekGeometry.safeInsetPx(size.width, seekInsetPx)
        val trackEnd = size.width - inset
        drawLine(
            color = trackColor,
            start = Offset(inset, y),
            end = Offset(trackEnd, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = playedColor,
            start = Offset(inset, y),
            end = Offset(
                inset + (trackEnd - inset) *
                    (dragging ?: externalPreviewFraction ?: playedFraction)
                        .coerceIn(0f, 1f), y,
            ),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** The player episode entry is a control, not part of the read-only time. */
@Composable
internal fun HongguoPlayerEpisodeEntry(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // The entire dock slot stays clickable; only its CONTENT uses
        // intrinsic width so the chevron sits next to the episode count.
        modifier = modifier.heightIn(min = HongguoUi.playbackDockRowHeight)
            .clickable(onClick = onClick)
            .padding(start = HongguoUi.pagePadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            fontSize = HongguoUi.playbackDockTitleSize,
            fontWeight = FontWeight.Medium,
            color = HongguoColors.ink,
            // fill=false keeps short labels compact while still allowing
            // long labels to ellipsize before they collide with the clock.
            modifier = Modifier.weight(1f, fill = false)
                .testTag("reel_episode_status"),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = "打开选集",
            modifier = Modifier.size(18.dp),
            tint = HongguoColors.ink,
        )
    }
}

@Composable
internal fun HongguoPlayerTimeLabel(
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        PlaybackTimeFormatter.label(positionMs, durationMs),
        modifier = modifier,
        color = HongguoColors.playerChromeMuted,
        fontSize = HongguoUi.playbackDockTimeSize,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

/** Same inset/background for comment rows and related-series rows. */
