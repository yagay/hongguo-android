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
internal fun HongguoPageSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.background(HongguoColors.background),
        contentAlignment = Alignment.TopStart,
    ) { content() }
}

/** Standard empty state with consistent text hierarchy. */
@Composable
internal fun HongguoEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = if (HongguoColors.isMaterial3) 32.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = HongguoColors.muted,
            fontSize = if (HongguoColors.isMaterial3) 14.sp else 13.sp,
            lineHeight = if (HongguoColors.isMaterial3) 20.sp else 18.sp)
    }
}

/** Shared sheet title/toolbar. Kept independent of sheet state and dismissal. */
@Composable
internal fun HongguoSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dark: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val foreground = if (dark) Color.White else HongguoColors.ink
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = if (HongguoColors.isMaterial3) 48.dp else 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null,
                tint = if (dark) foreground else HongguoColors.accent,
                modifier = Modifier.size(20.dp))
        }
        Text(
            title, modifier = Modifier.weight(1f),
            fontSize = HongguoUi.sectionTitleSize,
            fontWeight = FontWeight.SemiBold, color = foreground,
        )
        actions()
    }
}

/** Single reusable cover presentation; caller owns size, click action and data. */
@Composable
internal fun HongguoPosterCover(
    cover: Any?,
    title: String,
    modifier: Modifier = Modifier,
    classicRadius: androidx.compose.ui.unit.Dp = HongguoUi.controlRadius,
    sharedDramaId: String? = null,
    sharedAsDetail: Boolean = false,
) {
    HongguoPosterArtwork(
        cover = cover, title = title, modifier = modifier, classicRadius = classicRadius,
        sharedDramaId = sharedDramaId, sharedAsDetail = sharedAsDetail,
    )
}

/**
 * Shared transparent top-bar action for the video/feed/loading screens.
 * Keep the full 40dp touch target but never draw a permanent circular
 * background behind the icon. Press feedback remains handled by IconButton.
 */
@Composable
internal fun HongguoPlayerTopAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 25.dp,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(HongguoUi.iconButtonSize),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

