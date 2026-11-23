package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One presentation contract for settings and preference-like pages.
 * Classic geometry is intentionally unchanged; Material 3 only changes
 * visuals via shared components, never callbacks, keys, or data state.
 */
@Composable
internal fun HongguoSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (HongguoColors.isMaterial3) 8.dp else 4.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (HongguoColors.isMaterial3) Modifier.clickable { expanded = !expanded }
                    .testTag("settings_section_toggle_$title") else Modifier)
                .padding(start = 12.dp, end = 10.dp,
                    top = if (HongguoColors.isMaterial3) 10.dp else 7.dp,
                    bottom = if (HongguoColors.isMaterial3) 4.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f),
                color = HongguoColors.muted,
                fontSize = if (HongguoColors.isMaterial3) 14.sp else 12.sp,
                fontWeight = FontWeight.Medium)
            if (HongguoColors.isMaterial3) Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = HongguoColors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (!HongguoColors.isMaterial3 || expanded) Surface(
            shape = RoundedCornerShape(if (HongguoColors.isMaterial3) 24.dp else 12.dp),
            color = HongguoColors.surface,
            tonalElevation = if (HongguoColors.isMaterial3) 1.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (HongguoColors.isMaterial3) 16.dp else 13.dp, vertical = if (HongguoColors.isMaterial3) 6.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun HongguoSettingsDivider() {
    HorizontalDivider(color = HongguoColors.divider)
}

/** All preference rows share text hierarchy, click handling and accessibility tags. */
@Composable
internal fun HongguoSettingsRow(
    title: String,
    tag: String,
    onClick: () -> Unit = {},
    value: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    expanded: Boolean = false,
    showChevron: Boolean = true,
) {
    val material3 = HongguoColors.isMaterial3
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = if (subtitle.isNullOrBlank()) {
                if (material3) 56.dp else 46.dp
            } else if (material3) 64.dp else 54.dp)
            .then(if (enabled && showChevron) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(if (material3) 12.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(
            icon,
            contentDescription = null,
            tint = HongguoColors.accent,
            modifier = Modifier.size(if (material3) 22.dp else 19.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (material3) 3.dp else 1.dp),
        ) {
            Text(
                title,
                fontSize = if (material3) 16.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) HongguoColors.ink else HongguoColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) Text(
                subtitle,
                fontSize = if (material3) 13.sp else 11.sp,
                color = HongguoColors.muted,
                maxLines = if (material3) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) Text(
            value,
            fontSize = if (material3) 12.sp else 11.sp,
            color = if (enabled) HongguoColors.muted else HongguoColors.divider,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        if (showChevron) Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = HongguoColors.muted,
            modifier = Modifier.size(if (material3) 20.dp else 17.dp),
        )
    }
}

/** Legacy preference labels map into one shared switch implementation. */
@Composable
internal fun HongguoSettingsToggle(
    title: String,
    explanation: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    testTag: String,
) = HongguoToggleRow(
    title = title,
    checked = enabled,
    onCheckedChange = onChange,
    tag = testTag,
    description = explanation,
)

/**
 * Generic single-selection sheet for settings and other option lists.
 * Test tags are constructed from the original key/value pairs.
 */
@Composable
internal fun <T> HongguoSingleChoiceSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    tagPrefix: String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    HongguoModalSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HongguoSheetHeader(title = title)
            options.forEachIndexed { index, (value, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = if (HongguoColors.isMaterial3) 56.dp else 46.dp)
                        .clickable { onSelect(value) }
                        .testTag("${tagPrefix}_${value}")
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        color = HongguoColors.ink,
                        fontSize = if (HongguoColors.isMaterial3) 16.sp else 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected == value) Icon(
                        Icons.Outlined.Check,
                        contentDescription = "已选择",
                        tint = HongguoColors.accent,
                        modifier = Modifier.size(if (HongguoColors.isMaterial3) 24.dp else 19.dp),
                    )
                }
                if (index != options.lastIndex) HongguoSettingsDivider()
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
