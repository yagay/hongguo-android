package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box

/**
 * Compact, informative label shared by swipe playback and its loading page.
 * Tapping explains precisely what was verified, without exposing credentials.
 */
@Composable
internal fun ReelRecommendationHint(
    status: ReelRecommendationStatus,
    modifier: Modifier = Modifier,
) {
    var explanationOpen by remember { mutableStateOf(false) }
    val tint = when {
        status.failed -> Color(0xFFFFB7AA)
        status.accountRequest && status.received -> Color(0xFF94DFB4)
        status.accountRequest -> Color(0xFFFFD89A)
        else -> Color(0xFFD5D5D5)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.57f))
            .clickable { explanationOpen = true }
            .testTag("reel_official_personal_recommendation_status")
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Text(status.shortLabel, color = Color.White, fontSize = 11.sp, maxLines = 1)
        Icon(
            Icons.Outlined.Info,
            contentDescription = "推荐来源说明",
            tint = Color(0xFFCACACA),
            modifier = Modifier.size(13.dp),
        )
    }
    if (explanationOpen) {
        AlertDialog(
            onDismissRequest = { explanationOpen = false },
            title = { Text("刷剧推荐来源") },
            text = { Text(status.explanation) },
            confirmButton = {
                TextButton(onClick = { explanationOpen = false }) {
                    Text("知道了")
                }
            },
        )
    }
}
