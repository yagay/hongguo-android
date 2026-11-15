package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    comparison: RecommendationComparison? = null,
    comparing: Boolean = false,
    compareError: String? = null,
    onCompare: (() -> Unit)? = null,
) {
    var explanationOpen by remember { mutableStateOf(false) }
    val tint = when {
        status.failed -> Color(0xFFFFB7AA)
        status == ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED -> Color(0xFF94DFB4)
        status == ReelRecommendationStatus.ACCOUNT_SERVER_DISABLED -> Color(0xFFFFB7AA)
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
            title = { Text("刷剧推荐来源与个性化核验") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status.explanation)
                    if (status.accountRequest) {
                        Text("校验方式：先检查推荐响应有无明确的服务端个性化标志；再可主动对照同一官方推荐接口的账号与匿名样本。对照结果不同也不能单独证明服务端使用了个人偏好。")
                        if (comparing) Text("正在读取账号与匿名两组推荐样本…")
                        if (compareError != null) Text(compareError)
                        if (comparison != null) {
                            Text("对照样本：账号 ${comparison.accountCount} 部；匿名 ${comparison.anonymousCount} 部；重复 ${comparison.overlapCount} 部。")
                            Text(if (comparison.identicalOrder)
                                "两组作品与顺序一致：未发现差异，但不能证明未个性化。"
                            else
                                "两组作品或顺序存在差异：可能受账号影响，也可能来自随机推荐、时间变化或实验分组；尚不能独立确认个性化。")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { explanationOpen = false }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                if (status.accountRequest && status.received && onCompare != null) {
                    TextButton(
                        onClick = onCompare,
                        enabled = !comparing,
                        modifier = Modifier.testTag("reel_personalization_compare"),
                    ) { Text(if (comparing) "检查中…" else "账号／匿名对照") }
                }
            },
        )
    }
}
