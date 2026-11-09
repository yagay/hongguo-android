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
    val pagePadding = 16.dp
    val pageTopBarHeight = 56.dp
    val pageTitleSize = 22.sp
    val sectionTitleSize = 18.sp
    val pageSectionGap = 12.dp
    val primaryNavHeight = 40.dp
    val primaryNavIconSize = 27.dp
    val playbackSeekTouchHeight = 12.dp
    val playbackDockRowHeight = 34.dp
    val playbackDockTimeSize = 12.sp
    val playbackDockTitleSize = 12.sp
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
                label = { Text(label, fontSize = HongguoUi.chipTextSize) },
                shape = RoundedCornerShape(HongguoUi.controlRadius),
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
        label = { Text(label, fontSize = HongguoUi.chipTextSize) },
        shape = RoundedCornerShape(HongguoUi.controlRadius),
        colors = HongguoChoiceColors(dark),
        modifier = modifier,
    )
}

@Composable
private fun HongguoChoiceColors(dark: Boolean) =
    if (dark) FilterChipDefaults.filterChipColors(
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
    val shape = RoundedCornerShape(HongguoUi.controlRadius)
    val buttonContent: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null,
                modifier = Modifier.size(HongguoUi.leadingIconSize))
        }
        Text(label)
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
        modifier = modifier.size(HongguoUi.iconButtonSize),
        content = content,
    )
}

/**
 * One reusable icon-only primary navigation surface. The shell owns selected
 * route and all navigation actions, while this component owns only layout.
 */
@Composable
internal fun HongguoPrimaryNavigation(
    selectedTab: String,
    onSelect: (String) -> Unit,
) {
    // Keep all three primary destinations visually identical. A separate
    // browse-style bar made Theater/My taller and changed the selected colors
    // when navigating away from the reel feed.
    val height = HongguoUi.primaryNavHeight
    val backgroundColor = HongguoColors.playerChrome
    val activeColor = HongguoColors.ink
    val inactiveColor = HongguoColors.playerChromeMuted
    Surface(color = backgroundColor, shape = RoundedCornerShape(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Triple("home", "首页", Icons.Outlined.Home),
                Triple("theater", "剧场", Icons.Outlined.Theaters),
                Triple("mine", "我的", Icons.Outlined.PersonOutline),
            ).forEach { (tab, label, icon) ->
                Box(
                    modifier = Modifier.weight(1f).height(height)
                        .clickable { onSelect(tab) }
                        .testTag("primary_tab_" + tab),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(HongguoUi.primaryNavIconSize),
                        tint = if (selectedTab == tab) activeColor else inactiveColor,
                    )
                }
            }
        }
    }
}

/**
 * Common app-page title bar. Search/immersive video may replace the center
 * slot, but ordinary pages must not each implement independent title sizes
 * and header heights.
 */
@Composable
internal fun HongguoPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .height(HongguoUi.pageTopBarHeight)
            .padding(horizontal = HongguoUi.pagePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            HongguoIconAction(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回",
                    tint = HongguoColors.ink)
            }
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            fontSize = HongguoUi.pageTitleSize,
            fontWeight = FontWeight.Bold,
            color = HongguoColors.ink,
        )
        actions()
    }
}

/** Shared section title styling across normal (non-video) pages. */
@Composable
internal fun HongguoSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title, modifier = modifier, fontSize = HongguoUi.sectionTitleSize,
        fontWeight = FontWeight.SemiBold, color = HongguoColors.ink,
    )
}

/**
 * A single tab strip used for My and Account galleries. Content remains
 * screen-owned; only tab hit areas, indicator and selected typography live here.
 */
@Composable
internal fun HongguoTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "gallery_tab",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Column(
                modifier = Modifier.weight(1f)
                    .clickable { if (index != selectedIndex) onSelect(index) }
                    .testTag(tagPrefix + "_" + index),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 14.sp,
                        fontWeight = if (index == selectedIndex)
                            FontWeight.SemiBold else FontWeight.Normal,
                        color = if (index == selectedIndex)
                            HongguoColors.ink else HongguoColors.muted,
                    )
                }
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (index == selectedIndex)
                            HongguoColors.accent else Color.Transparent),
                )
            }
        }
    }
}

/** One themed content area for ordinary non-video pages and drawers. */
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
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = HongguoColors.muted, fontSize = 13.sp)
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
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
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
) {
    AsyncImage(
        model = cover,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(HongguoUi.controlRadius))
            .background(HongguoColors.accentSoft),
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

/** Shared themed dropdown for profile and management actions. */
@Composable
internal fun HongguoActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(244.dp)
            .background(HongguoColors.surface, RoundedCornerShape(HongguoUi.cardRadius)),
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
    val foreground = if (destructive) Color(0xFFD94B40) else HongguoColors.ink
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
        containerColor = HongguoColors.surface,
        contentColor = HongguoColors.ink,
        shape = RoundedCornerShape(
            topStart = HongguoUi.cardRadius,
            topEnd = HongguoUi.cardRadius,
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = HongguoColors.muted)
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
    Box(
        modifier = modifier.height(cellHeight)
            .clip(RoundedCornerShape(HongguoUi.controlRadius))
            .background(if (selected) HongguoColors.accent
                else HongguoColors.playerControlBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else HongguoColors.ink,
            fontSize = 13.sp,
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
@Composable
internal fun HongguoPlayerSeekTrack(
    durationMs: Long,
    playedFraction: Float,
    onSeek: (Long) -> Unit,
    onPreview: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember(durationMs) { mutableStateOf<Float?>(null) }
    val currentSeek by rememberUpdatedState(onSeek)
    val currentPreview by rememberUpdatedState(onPreview)
    Canvas(
        modifier.fillMaxWidth()
            .height(HongguoUi.playbackSeekTouchHeight)
            .pointerInput(durationMs) {
                detectTapGestures { point ->
                    if (durationMs > 0L && size.width > 0) {
                        val fraction = (point.x / size.width).coerceIn(0f, 1f)
                        currentSeek((durationMs * fraction).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { start ->
                        if (durationMs > 0L && size.width > 0) {
                            val value = (start.x / size.width).coerceIn(0f, 1f)
                            dragging = value
                            currentPreview(value)
                        }
                    },
                    onDragEnd = {
                        dragging?.let { currentSeek((durationMs * it).toLong()) }
                        dragging = null
                        currentPreview(null)
                    },
                    onDragCancel = {
                        dragging = null
                        currentPreview(null)
                    },
                    onDrag = { change, _ ->
                        if (durationMs > 0L && size.width > 0) {
                            val value = (change.position.x / size.width).coerceIn(0f, 1f)
                            dragging = value
                            currentPreview(value)
                        }
                        change.consume()
                    },
                )
            },
    ) {
        val y = size.height / 2f
        drawLine(
            color = HongguoColors.playerChromeTrack,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = HongguoColors.accent,
            start = Offset(0f, y),
            end = Offset(size.width * (dragging ?: playedFraction).coerceIn(0f, 1f), y),
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
        modifier = modifier.heightIn(min = HongguoUi.playbackDockRowHeight)
            .clickable(onClick = onClick)
            .padding(start = HongguoUi.pagePadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            fontSize = HongguoUi.playbackDockTitleSize,
            fontWeight = FontWeight.Medium,
            color = HongguoColors.ink,
            modifier = Modifier.weight(1f).testTag("reel_episode_status"),
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

/** The only ordinary settings group: one title + theme card + consistent spacing. */
@Composable
internal fun HongguoSettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HongguoUi.pageSectionGap)) {
        Text(
            title,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = HongguoColors.muted,
        )
        HongguoCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(HongguoUi.pagePadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = HongguoColors.ink) },
        text = { Text(message, color = HongguoColors.muted) },
        confirmButton = {
            if (destructive) {
                TextButton(onClick = onConfirm,
                    shape = RoundedCornerShape(HongguoUi.controlRadius)) {
                    Text(confirmLabel, color = Color(0xFFD94B40))
                }
            } else {
                HongguoAction(label = confirmLabel, onClick = onConfirm,
                    style = HongguoActionStyle.PRIMARY)
            }
        },
        dismissButton = {
            HongguoAction(label = "取消", onClick = onDismiss,
                style = HongguoActionStyle.QUIET)
        },
        shape = RoundedCornerShape(HongguoUi.cardRadius),
        containerColor = HongguoColors.surface,
    )
}
