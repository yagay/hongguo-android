package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keep the identical, interactive reel navigation on loading and playback pages. */
@Composable
internal fun ReelFeedHeader(
    reelsMode: String,
    onComic: () -> Unit,
    onReal: () -> Unit,
    onRecommend: () -> Unit,
    onOpenAccountDrawer: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val white = Color.White
    // Official-inspired navigation: only Recommend and Real correspond to
    // live official reel feeds. Other tabs open real existing collections.
    // Keep the edge controls independent of tab count. The reference
    // screenshot places the menu/search near the edges while the
    // category labels form a compact, centred group, not weighted
    // columns stretched over the entire available width.
    val tabs = buildList {
        FeatureAvailability.followingAction()?.let { openFollowing ->
            add(Triple("关注", false, openFollowing))
        }
        add(Triple("漫剧", reelsMode == "comic", onComic))
        add(Triple("真人剧", reelsMode == "real", onReal))
        add(Triple("推荐", reelsMode == "all", onRecommend))
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(56.dp)
            .padding(horizontal = 8.dp),
    ) {
        val tabWidth = minOf(60.dp, (maxWidth - 88.dp) / tabs.size)
        HongguoPlayerTopAction(
            icon = Icons.Outlined.Menu,
            label = "打开账号与隐私侧边栏",
            onClick = onOpenAccountDrawer,
            modifier = Modifier.align(Alignment.TopStart)
                .testTag("reel_menu"),
            iconSize = 23.dp,
        )
        Row(
            modifier = Modifier.align(Alignment.TopCenter)
                .height(52.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.Center,
        ) {
            tabs.forEach { (label, active, action) ->
                Column(
                    modifier = Modifier.width(tabWidth)
                        .clickable(onClick = action)
                        .padding(top = 3.dp)
                        .testTag("reel_tab_" + label),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(label,
                        color = if (active) white else Color(0xFFE3E3E3),
                        fontSize = 18.sp, lineHeight = 23.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Clip)
                    Box(Modifier.width(20.dp).height(2.dp)
                        .background(if (active) white else Color.Transparent,
                            RoundedCornerShape(2.dp)))
                }
            }
        }
        IconButton(
            onClick = onSearch,
            modifier = Modifier.align(Alignment.TopEnd)
                .size(40.dp).testTag("reel_search"),
        ) {
            Icon(Icons.Outlined.Search, "搜索短剧",
                tint = white, modifier = Modifier.size(27.dp))
        }
    }
}
