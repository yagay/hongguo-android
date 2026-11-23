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
internal fun HongguoSheetListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(RoundedCornerShape(HongguoUi.controlRadius))
            .background(HongguoColors.background)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

/** Full-width expand/collapse row, including a single consistent trailing chevron. */
@Composable
internal fun HongguoExpandableRow(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HongguoUi.chipGap),
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp,
            fontWeight = FontWeight.Medium, color = HongguoColors.ink)
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            modifier = Modifier.size(HongguoUi.leadingIconSize),
            tint = HongguoColors.muted,
        )
    }
}

/** Reusable confirmation surface; caller controls dismissal and side effects. */
@Composable
internal fun HongguoConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = "取消",
) {
    val material3 = HongguoColors.isMaterial3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = if (material3)
            MaterialTheme.colorScheme.onSurface else HongguoColors.ink) },
        text = { Text(message, color = if (material3)
            MaterialTheme.colorScheme.onSurfaceVariant else HongguoColors.muted) },
        confirmButton = {
            if (destructive) {
                TextButton(onClick = onConfirm,
                    shape = RoundedCornerShape(if (material3) 24.dp
                        else HongguoUi.controlRadius)) {
                    Text(confirmLabel, color = if (material3)
                        MaterialTheme.colorScheme.error else Color(0xFFD94B40))
                }
            } else {
                HongguoAction(label = confirmLabel, onClick = onConfirm,
                    style = HongguoActionStyle.PRIMARY)
            }
        },
        dismissButton = {
            HongguoAction(label = cancelLabel, onClick = onDismiss,
                style = HongguoActionStyle.QUIET)
        },
        shape = RoundedCornerShape(if (material3) 28.dp
            else HongguoUi.cardRadius),
        containerColor = if (material3)
            MaterialTheme.colorScheme.surfaceContainerHigh else HongguoColors.surface,
    )
}



/** Reusable themed dialog for checkbox editors and rich explanations. */
@Composable
internal fun HongguoContentDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = content,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        modifier = modifier,
        shape = if (HongguoColors.isMaterial3)
            MaterialTheme.shapes.extraLarge else RoundedCornerShape(HongguoUi.cardRadius),
        containerColor = if (HongguoColors.isMaterial3)
            MaterialTheme.colorScheme.surfaceContainerHigh else HongguoColors.surface,
        titleContentColor = HongguoColors.ink,
        textContentColor = HongguoColors.muted,
    )
}
