package com.hongguo.rewrite

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual rules. Player uses the same sizes/radii with dark surfaces.
 * Keep screen-specific data and actions in their own composables.
 */
internal object HongguoUi {
    val cardRadius = 16.dp
    val controlRadius = 10.dp
    val smallRadius = 7.dp
    val iconButtonSize = 40.dp
    val leadingIconBox = 36.dp
    val leadingIconSize = 20.dp
    val rowTitleSize = 16.sp
    val rowDescriptionSize = 12.sp
    val chipTextSize = 12.sp
    val chipGap = 8.dp
}

@Composable
internal fun HongguoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(HongguoUi.cardRadius),
        color = HongguoColors.surface,
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = HongguoColors.accentSoft,
            shape = RoundedCornerShape(HongguoUi.controlRadius),
        ) {
            Box(Modifier.size(HongguoUi.leadingIconBox), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null,
                    modifier = Modifier.size(HongguoUi.leadingIconSize),
                    tint = HongguoColors.accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = HongguoUi.rowTitleSize,
                fontWeight = FontWeight.Medium, color = HongguoColors.ink)
            Text(subtitle, fontSize = HongguoUi.rowDescriptionSize,
                lineHeight = 19.sp, color = HongguoColors.muted)
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
                label = { Text(label, fontSize = HongguoUi.chipTextSize) },
                shape = RoundedCornerShape(HongguoUi.controlRadius),
                colors = if (dark) FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF38373E),
                    labelColor = Color.White,
                    selectedContainerColor = HongguoColors.accent,
                    selectedLabelColor = Color.White,
                ) else FilterChipDefaults.filterChipColors(
                    containerColor = HongguoColors.surface,
                    labelColor = HongguoColors.ink,
                    selectedContainerColor = HongguoColors.accentSoft,
                    selectedLabelColor = HongguoColors.accent,
                ),
                modifier = Modifier.testTag(tag + "_" + index),
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
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, color = titleColor, fontSize = 13.sp,
                fontWeight = FontWeight.Medium)
            if (!description.isNullOrBlank()) {
                Text(description, color = supportingColor, fontSize = 11.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedTrackColor = HongguoColors.accent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}
