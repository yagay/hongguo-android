package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI-only controls. The parent owns playback and retry lifecycles. */
@Composable
internal fun PlaybackErrorOverlay(
    error: String,
    recoveryStatus: String?,
    feedMode: Boolean,
    onRetry: () -> Unit,
    onStopRetry: () -> Unit,
    onOpenOfficialWebsite: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (recoveryStatus != null)
                "正在等待自动恢复"
             else "本集暂时不能播放", color = Color.White,
            fontWeight = FontWeight.Bold)
        Text(error, color = Color(0xFFD9D4D1), fontSize = 13.sp)
        HongguoVideoRetryActions(
            onRetry = {
                onRetry()
            },
            retryLabel = if (feedMode) "刷新推荐列表" else "立即重试",
            recoveryStatus = recoveryStatus,
            onStopRetry = onStopRetry,
            retryTag = "playback_manual_retry",
            statusTag = "periodic_playback_retry_status",
            stopTag = "periodic_playback_retry_stop",
        )
        if (onOpenOfficialWebsite != null) {
            OfficialPlaybackWebsiteButton(onOpenOfficialWebsite)
        }
        Text(if (feedMode)
                "继续上滑也会请求新的推荐，不再循环播放失效列表"
             else "可重新尝试或切换其他剧集",
            color = Color.LightGray, fontSize = 12.sp)
    }
}

@Composable
internal fun PlayerClearScreenDialog(
    hiddenChromeMask: Int,
    onMaskChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    HongguoContentDialog(
        onDismissRequest = onDismiss,
        title = "清屏设置",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("选择要从视频画面上隐藏的内容",
                    color = HongguoColors.muted, fontSize = 12.sp)
                listOf(
                    Triple(1, "顶部按钮", "推荐分类、返回、画中画和设置"),
                    Triple(2, "右侧按钮", "收藏、评论、喜欢、播放量"),
                    Triple(4, "剧名", "剧名和进入详情的入口"),
                    Triple(8, "演员与标签", "分类、演员及 AI 提示"),
                    Triple(16, "简介文案", "视频下方的剧情介绍"),
                    Triple(32, "弹幕", "画面弹幕与弹幕开关"),
                ).forEach { (bit, label, description) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onMaskChange(hiddenChromeMask xor bit)
                            }.padding(vertical = 2.dp)
                            .testTag("clear_screen_option_$bit"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = (hiddenChromeMask and bit) != 0,
                            onCheckedChange = { hide ->
                                onMaskChange(
                                    if (hide) hiddenChromeMask or bit
                                    else hiddenChromeMask and bit.inv()
                                )
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(label, color = HongguoColors.ink, fontSize = 14.sp)
                            Text(description, color = HongguoColors.muted,
                                fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
                Text("进度条、选集、底部导航始终保留。",
                    color = HongguoColors.muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("player_clear_screen_toggle"),
            ) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = { onMaskChange(
                if (hiddenChromeMask == 63) 0 else 63
            ) }) { Text(if (hiddenChromeMask == 63) "全部显示" else "全部清屏") }
        },
        modifier = Modifier.testTag("player_clear_screen_dialog"),
    )
}
