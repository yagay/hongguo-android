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


internal enum class HongguoActionStyle { PRIMARY, OUTLINE, QUIET }

@Composable
internal fun HongguoAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: HongguoActionStyle = HongguoActionStyle.OUTLINE,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(
        if (HongguoColors.isMaterial3) 50.dp else HongguoUi.controlRadius
    )
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(HongguoUi.leadingIconSize))
        }
        Text(label)
    }
    if (HongguoColors.isMaterial3) {
        when (style) {
            HongguoActionStyle.PRIMARY -> Button(
                onClick = onClick, modifier = modifier, enabled = enabled,
                shape = shape, content = buttonContent,
            )
            HongguoActionStyle.OUTLINE -> OutlinedButton(
                onClick = onClick, modifier = modifier, enabled = enabled,
                shape = shape, content = buttonContent,
            )
            HongguoActionStyle.QUIET -> TextButton(
                onClick = onClick, modifier = modifier, enabled = enabled,
                shape = shape, content = buttonContent,
            )
        }
        return
    }
    when (style) {
        HongguoActionStyle.PRIMARY -> Button(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = HongguoColors.accent, contentColor = Color.White),
            content = buttonContent,
        )
        HongguoActionStyle.OUTLINE -> OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = HongguoColors.accent,
                disabledContentColor = HongguoColors.muted),
            content = buttonContent,
        )
        HongguoActionStyle.QUIET -> TextButton(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = HongguoColors.accent,
                disabledContentColor = HongguoColors.muted),
            content = buttonContent,
        )
    }
}

/** Shared click target; accessibility description is retained by the icon caller. */
@Composable
internal fun HongguoIconAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(if (HongguoColors.isMaterial3)
            LocalHongguoSpacing.current.minTouchTarget else HongguoUi.iconButtonSize),
        content = content,
    )
}

/**
 * One reusable icon-only primary navigation surface. The shell owns selected
 * route and all navigation actions, while this component owns only layout.
 */
