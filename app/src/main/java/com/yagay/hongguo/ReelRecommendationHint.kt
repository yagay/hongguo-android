package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
        // Candidate field semantics have not been verified against the official APK.
        status == ReelRecommendationStatus.ACCOUNT_SERVER_ENABLED -> Color(0xFFFFD89A)
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
                Column(
                    modifier = Modifier.heightIn(max = 410.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(status.explanation)
                    if (status.accountRequest) {
                        Text("账号请求成功不等于个性化排序已获证实。可使用与刷剧相同的签名链路，读取两组独立账号／游客样本，核对作品重合、排序以及两种来源本身的变化。")
                        if (comparing) Text("正在依次读取账号一、游客一、账号二、游客二，共四组独立样本…")
                        if (compareError != null) Text(compareError)
                        if (comparison != null) {
                            val overlapPercent = { common: Int, left: Int, right: Int ->
                                (100 * common / minOf(left, right).coerceAtLeast(1)).coerceIn(0, 100)
                            }
                            Text("设备签名成功的账号样本：${comparison.signedAccountSamples}/2" +
                                if (comparison.signedAccountSamples < 2)
                                    "（其余可能使用账号 Cookie 回退）" else "（两次均成功）")
                            Text("第 1 组：账号 ${comparison.accountCount} 部、游客 ${comparison.anonymousCount} 部，重合 ${comparison.overlapCount} 部（${overlapPercent(comparison.overlapCount, comparison.accountCount, comparison.anonymousCount)}%）；顺序${if (comparison.identicalOrder) "一致" else "不同"}。")
                            Text("第 2 组：账号 ${comparison.secondAccountCount} 部、游客 ${comparison.secondAnonymousCount} 部，重合 ${comparison.secondAccountGuestOverlap} 部（${overlapPercent(comparison.secondAccountGuestOverlap, comparison.secondAccountCount, comparison.secondAnonymousCount)}%）；顺序${if (comparison.secondAccountGuestIdenticalOrder) "一致" else "不同"}。")
                            Text("账号自身两次重合 ${comparison.accountBaselineOverlap} 部（${overlapPercent(comparison.accountBaselineOverlap, comparison.accountCount, comparison.secondAccountCount)}%）；顺序${if (comparison.accountBaselineIdenticalOrder) "一致" else "不同"}。")
                            Text("游客自身两次重合 ${comparison.anonymousBaselineOverlap} 部（${overlapPercent(comparison.anonymousBaselineOverlap, comparison.anonymousCount, comparison.secondAnonymousCount)}%）；顺序${if (comparison.anonymousBaselineIdenticalOrder) "一致" else "不同"}。")
                            Text("以上结果仅反映样本差异。即使账号与游客差异较大，也可能来自随机推荐、时间变化或实验分组；不能单凭对照断言官方已按个人喜好排序。")
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
                    ) { Text(if (comparing) "检查中…" else "双轮账号／匿名对照") }
                }
            },
        )
    }
}
