package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
internal fun HongguoPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        content = content,
    )
}

/** Shared themed dropdown for profile and management actions. */
@Composable
internal fun HongguoActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    HongguoPopupMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(244.dp)
            .background(HongguoColors.surface, RoundedCornerShape(
                if (HongguoColors.isMaterial3) 24.dp else HongguoUi.cardRadius)),
        content = content,
    )
}

@Composable
internal fun HongguoMenuAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    destructive: Boolean = false,
) {
    val foreground = if (destructive && HongguoColors.isMaterial3)
        MaterialTheme.colorScheme.error
        else if (destructive) Color(0xFFD94B40) else HongguoColors.ink
    DropdownMenuItem(
        modifier = modifier.heightIn(min = 48.dp),
        text = {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = foreground, maxLines = 2)
        },
        leadingIcon = {
            Icon(icon, contentDescription = null,
                tint = foreground, modifier = Modifier.size(HongguoUi.leadingIconSize))
        },
        trailingIcon = if (checked == null) null else ({
            Checkbox(checked = checked, onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = HongguoColors.accent))
        }),
        onClick = onClick,
    )
}

@Composable
internal fun HongguoMenuSeparator(dark: Boolean = false) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = HongguoUi.pagePadding, vertical = 4.dp),
        color = HongguoColors.divider,
    )
}

/**
 * One modal surface for Library, player settings, episodes and comments.
 * It follows the same palette and radius as ordinary app cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HongguoModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = if (HongguoColors.isMaterial3)
            MaterialTheme.colorScheme.surfaceContainerLow else HongguoColors.surface,
        contentColor = if (HongguoColors.isMaterial3)
            MaterialTheme.colorScheme.onSurface else HongguoColors.ink,
        shape = RoundedCornerShape(
            topStart = if (HongguoColors.isMaterial3) 28.dp else HongguoUi.cardRadius,
            topEnd = if (HongguoColors.isMaterial3) 28.dp else HongguoUi.cardRadius,
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = if (HongguoColors.isMaterial3)
                MaterialTheme.colorScheme.onSurfaceVariant else HongguoColors.muted)
        },
        modifier = modifier,
        content = content,
    )
}

/** Kept for existing player call sites; now uses the same modal as Library. */
@Composable
internal fun HongguoPlayerSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = HongguoModalSheet(onDismissRequest, modifier, content)

/** Same quiet action style as regular menus and Settings. */
@Composable
internal fun HongguoPlayerSheetAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = HongguoAction(
    label = label, onClick = onClick, modifier = modifier,
    enabled = enabled, style = HongguoActionStyle.QUIET,
)

/** One active/inactive episode selection control across player episode grids. */
@Composable
internal fun HongguoEpisodeCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cellHeight: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val material3 = HongguoColors.isMaterial3
    Box(
        modifier = modifier.height(
                if (material3) maxOf(cellHeight, LocalHongguoSpacing.current.minTouchTarget)
                else cellHeight
            )
            .clip(RoundedCornerShape(if (material3) 16.dp
                else HongguoUi.controlRadius))
            .background(when {
                material3 && selected -> MaterialTheme.colorScheme.secondaryContainer
                material3 -> MaterialTheme.colorScheme.surfaceContainerHigh
                selected -> HongguoColors.accent
                else -> HongguoColors.playerControlBackground
            })
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = when {
            material3 && selected -> MaterialTheme.colorScheme.onSecondaryContainer
            material3 -> MaterialTheme.colorScheme.onSurface
            selected -> Color.White
            else -> HongguoColors.ink
        },
            fontSize = if (material3) 14.sp else 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1)
    }
}

/**
 * Unified layout for all player sheets: equal horizontal gutters,
 * a consistent heading/action row and the same supporting-text treatment.
 *
 * Content owns its own scrolling; the layout never creates another scroll
 * container around LazyColumn/LazyVerticalGrid.
 */
@Composable
internal fun HongguoPlayerPanel(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .padding(horizontal = HongguoUi.pagePadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(HongguoUi.pageSectionGap),
    ) {
        HongguoSheetHeader(
            title = title,
            icon = icon,
            actions = actions,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                fontSize = HongguoUi.rowDescriptionSize,
                color = HongguoColors.muted,
            )
        }
        content()
    }
}

/**
 * Reusable playback progress surface. Gesture state belongs to the control;
 * caller receives the drag preview to keep its time label synchronized.
 * A real Media3 seek happens on release/tap only, never on every drag frame.
 */
