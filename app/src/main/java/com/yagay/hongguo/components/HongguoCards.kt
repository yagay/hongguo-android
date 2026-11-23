package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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
internal fun HongguoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (HongguoColors.isMaterial3) 24.dp else HongguoUi.cardRadius),
        color = HongguoColors.surface,
        tonalElevation = if (HongguoColors.isMaterial3) 1.dp else 0.dp,
        content = content,
    )
}

@Composable
internal fun HongguoNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (HongguoColors.isMaterial3) 56.dp else 0.dp)
            .clickable(onClick = onClick).testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = HongguoColors.accentSoft,
            shape = RoundedCornerShape(if (HongguoColors.isMaterial3) 16.dp
                else HongguoUi.controlRadius),
        ) {
            Box(Modifier.size(HongguoUi.leadingIconBox), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null,
                    modifier = Modifier.size(HongguoUi.leadingIconSize),
                    tint = HongguoColors.accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = if (HongguoColors.isMaterial3) 16.sp else HongguoUi.rowTitleSize,
                fontWeight = FontWeight.Medium, color = HongguoColors.ink) 
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = HongguoUi.rowDescriptionSize,
                    lineHeight = 19.sp, color = HongguoColors.muted)
            }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null,
            modifier = Modifier.size(20.dp), tint = HongguoColors.muted)
    }
}

@Composable
internal fun <T> HongguoChoiceGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: String,
    dark: Boolean = false,
    tagByValue: Boolean = false,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HongguoUi.chipGap),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, fontSize = if (HongguoColors.isMaterial3 && !dark)
                    14.sp else HongguoUi.chipTextSize) },
                shape = RoundedCornerShape(if (HongguoColors.isMaterial3 && !dark)
                    16.dp else HongguoUi.controlRadius),
                colors = HongguoChoiceColors(dark),
                modifier = Modifier.testTag(tag + "_" +
                    (if (tagByValue) value.toString() else index.toString())),
            )
        }
    }
}

/**
 * Standardized toggle row used by both the light settings pages and the
 * dark in-player controls. Caller keeps the actual preference state.
 */
@Composable
internal fun HongguoToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    description: String? = null,
    dark: Boolean = false,
) {
    val titleColor = if (dark) Color.White else HongguoColors.ink
    val supportingColor = if (dark) Color(0xFFC5C3C9) else HongguoColors.muted
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (HongguoColors.isMaterial3 && !dark)
            56.dp else 40.dp)
            .toggleable(value = checked, role = Role.Switch,
                onValueChange = onCheckedChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, color = titleColor,
                fontSize = if (HongguoColors.isMaterial3 && !dark) 16.sp else 13.sp,
                fontWeight = FontWeight.Medium)
            if (!description.isNullOrBlank()) {
                Text(description, color = supportingColor,
                    fontSize = if (HongguoColors.isMaterial3 && !dark) 13.sp else 11.sp)
            }
        }
        Switch(
            checked = checked,
            // The whole settings row is the single accessible switch target.
            onCheckedChange = null,
            colors = if (HongguoColors.isMaterial3) SwitchDefaults.colors()
                else SwitchDefaults.colors(
                    checkedTrackColor = HongguoColors.accent,
                    checkedThumbColor = Color.White,
                ),
        )
    }
}

/** Single option chip for independent, scrolling or weighted chip layouts. */
@Composable
internal fun HongguoChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = if (HongguoColors.isMaterial3 && !dark)
            14.sp else HongguoUi.chipTextSize) },
        shape = RoundedCornerShape(if (HongguoColors.isMaterial3 && !dark)
            16.dp else HongguoUi.controlRadius),
        colors = HongguoChoiceColors(dark),
        modifier = modifier,
    )
}

@Composable
private fun HongguoChoiceColors(dark: Boolean) =
    if (HongguoColors.isMaterial3 && !dark) FilterChipDefaults.filterChipColors()
    else if (dark) FilterChipDefaults.filterChipColors(
        containerColor = Color(0xFF38373E),
        labelColor = Color.White,
        selectedContainerColor = HongguoColors.accent,
        selectedLabelColor = Color.White,
    ) else FilterChipDefaults.filterChipColors(
        containerColor = HongguoColors.surface,
        labelColor = HongguoColors.ink,
        selectedContainerColor = HongguoColors.accentSoft,
        selectedLabelColor = HongguoColors.accent,
    )

/** Standard action button, with three semantic weights rather than custom styles. */
