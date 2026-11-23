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
    /** Keeps 0% and 100% outside the OS back-gesture edge zone. */
    val playbackSeekHorizontalInset = 24.dp
    val playbackDockRowHeight = 34.dp
    val playbackDockTimeSize = 12.sp
    val playbackDockTitleSize = 12.sp
}

/**
 * Shared coordinate mapping for the progress bar and its wider gesture row.
 * Both use the same 24dp internal inset so the visual endpoint and the seek
 * gesture always agree. Input still covers the entire dock width.
 */
internal object PlaybackSeekGeometry {
    fun safeInsetPx(widthPx: Float, insetPx: Float): Float =
        if (!widthPx.isFinite() || widthPx <= 0f || !insetPx.isFinite()) 0f
        else insetPx.coerceIn(0f, widthPx * 0.4f)

    fun fractionAt(xPx: Float, widthPx: Float, insetPx: Float = 0f): Float {
        if (!xPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f) return 0f
        val inset = safeInsetPx(widthPx, insetPx)
        return ((xPx - inset) / (widthPx - inset * 2f)).coerceIn(0f, 1f)
    }

    fun positionAt(durationMs: Long, fraction: Float): Long =
        if (durationMs <= 0L || !fraction.isFinite()) 0L
        else (durationMs.toDouble() * fraction.coerceIn(0f, 1f).toDouble())
            .toLong().coerceIn(0L, durationMs)
}

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
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)
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
        label = { Text(label, fontSize = HongguoUi.chipTextSize) },
        shape = RoundedCornerShape(HongguoUi.controlRadius),
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
    /** Distinguish the player episode/time strip from the primary tabs. */
    separateFromPlayer: Boolean = false,
) {
    if (HongguoColors.isMaterial3 && !separateFromPlayer) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            listOf(
                Triple("home", "首页", Icons.Outlined.Home),
                Triple("theater", "剧场", Icons.Outlined.Theaters),
                Triple("mine", "我的", Icons.Outlined.PersonOutline),
            ).forEach { (tab, label, icon) ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onSelect(tab) },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                    modifier = Modifier.testTag("primary_tab_" + tab),
                )
            }
        }
        return
    }
    // Keep the 40dp immersive-player dock; enlarging it crops portrait video.
    val height = HongguoUi.primaryNavHeight
    val topDivider = if (separateFromPlayer) 1.dp else 0.dp
    val topInset = if (separateFromPlayer) 3.dp else 0.dp
    val tabsHeight = height - topDivider - topInset
    val backgroundColor = if (separateFromPlayer)
        HongguoColors.playerControlBackground else HongguoColors.playerChrome
    val activeColor = HongguoColors.ink
    val inactiveColor = HongguoColors.playerChromeMuted
    Surface(color = backgroundColor, shape = RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth().height(height)) {
            if (separateFromPlayer) {
                HorizontalDivider(
                    thickness = topDivider,
                    color = HongguoColors.divider,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(topInset))
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(tabsHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    Triple("home", "首页", Icons.Outlined.Home),
                    Triple("theater", "剧场", Icons.Outlined.Theaters),
                    Triple("mine", "我的", Icons.Outlined.PersonOutline),
                ).forEach { (tab, label, icon) ->
                    Box(
                        modifier = Modifier.weight(1f).height(tabsHeight)
                            .clickable { onSelect(tab) }
                            .testTag("primary_tab_" + tab),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(HongguoUi.primaryNavIconSize),
                            tint = if (HongguoColors.isMaterial3 && selectedTab == tab)
                                MaterialTheme.colorScheme.primary
                            else if (selectedTab == tab) activeColor else inactiveColor,
                        )
                    }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HongguoPageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    horizontalPadding: androidx.compose.ui.unit.Dp = HongguoUi.pagePadding,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (HongguoColors.isMaterial3) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (onBack != null) {
                    HongguoIconAction(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = actions,
            modifier = modifier,
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        return
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .height(HongguoUi.pageTopBarHeight)
            .padding(horizontal = horizontalPadding),
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
    if (HongguoColors.isMaterial3) {
        Row(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { if (!selected) onSelect(index) }
                        .testTag(tagPrefix + "_" + index)
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        return
    }
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

/** Shared section heading for the player's settings, speed and danmaku sheets. */
@Composable
internal fun HongguoPlayerSectionTitle(title: String) {
    Text(title,
        fontSize = if (HongguoColors.isMaterial3) 14.sp else 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = HongguoColors.ink,
    )
}

/**
 * Shared category navigation for Theater and other compact filter strips.
 * The host page owns its state and callbacks; test tags stay unchanged.
 */
@Composable
internal fun <T> HongguoCategoryNavigation(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    trailingLabel: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    trailingTag: String = "category_trailing",
) {
    val material3 = HongguoColors.isMaterial3
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (material3) 8.dp else 25.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (value, label) ->
            val active = selected == value
            if (material3) {
                HongguoChoiceChip(
                    label = label,
                    selected = active,
                    onClick = { if (!active) onSelect(value) },
                    modifier = Modifier.testTag("${tagPrefix}_${index}"),
                )
            } else {
                Text(
                    label,
                    color = if (active) HongguoColors.ink else HongguoColors.muted,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { if (!active) onSelect(value) }
                        .padding(vertical = 8.dp).testTag("${tagPrefix}_${index}"),
                )
            }
        }
        if (trailingLabel != null && onTrailingClick != null) {
            if (material3) {
                HongguoAction(
                    label = trailingLabel,
                    onClick = onTrailingClick,
                    modifier = Modifier.testTag(trailingTag),
                    style = HongguoActionStyle.QUIET,
                )
            } else {
                Text(
                    trailingLabel,
                    fontSize = 14.sp,
                    color = HongguoColors.muted,
                    modifier = Modifier.clickable(onClick = onTrailingClick)
                        .padding(vertical = 8.dp).testTag(trailingTag),
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
    classicRadius: androidx.compose.ui.unit.Dp = HongguoUi.controlRadius,
) {
    HongguoPosterArtwork(
        cover = cover, title = title, modifier = modifier, classicRadius = classicRadius,
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

/** Shared popup surface for all contextual menus (account, posters, library). */
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
        modifier = modifier.height(cellHeight)
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
    Canvas(
        modifier.fillMaxWidth()
            .height(HongguoUi.playbackSeekTouchHeight)
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
            color = HongguoColors.playerChromeTrack,
            start = Offset(inset, y),
            end = Offset(trackEnd, y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = HongguoColors.accent,
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
