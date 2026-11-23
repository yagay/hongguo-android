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
