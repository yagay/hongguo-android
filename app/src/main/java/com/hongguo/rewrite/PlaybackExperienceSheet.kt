package com.hongguo.rewrite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Playback-only settings: no duplicate entry in the general settings screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackExperienceSheet(
    state: AppState,
    model: DramaViewModel,
    speed: Float,
    fillVideo: Boolean,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onFillVideo: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onDownload: () -> Unit,
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    val white = Color.White
    val secondary = Color(0xFFC5C3C9)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = HongguoColors.playerSheet,
        contentColor = white,
        modifier = Modifier.testTag("playback_settings_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("播放设置", fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("完成", color = HongguoColors.accent) }
            }

            PlaybackHeading("画面比例")
            PlaybackChoices(
                listOf(false to "适应画面", true to "铺满画面"),
                fillVideo, onFillVideo, "video_frame_mode",
            )
            Text("适应模式可显示完整画面；铺满模式可能裁切画面边缘和字幕。",
                fontSize = 11.sp, color = secondary)

            PlaybackHeading("播放倍速")
            PlaybackChoices(
                listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×",
                    1.5f to "1.5×", 2f to "2×"),
                speed, onSpeed, "video_speed",
            )
            if (state.backend.isBlank()) {
                PlaybackHeading("清晰度上限")
                PlaybackChoices(
                    listOf(0 to "自动", 360 to "360P", 480 to "480P",
                        720 to "720P", 1080 to "1080P"),
                    state.qualityLimit, onQuality, "video_quality",
                )
                Text("实际清晰度取决于接口提供的视频资源与设备解码能力。",
                    fontSize = 11.sp, color = secondary)
            }

            HorizontalDivider(color = Color(0xFF4B4A50))
            PlaybackHeading("弹幕")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示弹幕", fontWeight = FontWeight.SemiBold, color = white)
                    Text("按当前剧集的播放时间显示公开弹幕", fontSize = 11.sp, color = secondary)
                }
                Switch(
                    checked = state.danmakuEnabled,
                    onCheckedChange = model::setDanmaku,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = HongguoColors.accent,
                        checkedThumbColor = white,
                    ),
                    modifier = Modifier.testTag("player_danmaku_switch"),
                )
            }
            PlaybackHeading("弹幕移动速度")
            PlaybackChoices(
                listOf(0 to "慢", 1 to "标准", 2 to "快"),
                state.danmakuSpeed, model::setDanmakuSpeed, "danmaku_speed",
            )
            PlaybackHeading("弹幕轨道数量")
            PlaybackChoices(
                (1..5).map { it to "$it 轨" },
                state.danmakuDensity, model::setDanmakuDensity, "danmaku_lanes",
            )
            PlaybackHeading("弹幕字体大小")
            PlaybackChoices(
                listOf(12, 14, 16, 18).map { it to "$it sp" },
                state.danmakuFontSize, model::setDanmakuFontSize, "danmaku_font",
            )

            HorizontalDivider(color = Color(0xFF4B4A50))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PlaybackHeading("定时停止")
                Spacer(Modifier.weight(1f))
                Text("当前：" + state.sleepTimerLabel, fontSize = 11.sp, color = secondary)
            }
            PlaybackChoices(
                listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟"),
                if (state.sleepTimerLabel == "关闭") 0 else -1,
                model::sleepAfterMinutes, "sleep_timer",
            )
            OutlinedButton(
                onClick = { model.sleepAfterEpisodes(1) },
                modifier = Modifier.fillMaxWidth().testTag("sleep_after_episode"),
            ) { Text("播完本集后停止", color = white) }

            if (account.profile != null && state.detail != null) {
                HorizontalDivider(color = Color(0xFF4B4A50))
                val currentId = state.detail.drama.id
                val inCloud = account.favorites.any { it.series.id == currentId }
                OutlinedButton(
                    enabled = !account.busy,
                    onClick = { model.account.cloudFavorite(currentId, !inCloud) },
                    modifier = Modifier.fillMaxWidth().testTag("player_cloud_favorite"),
                ) {
                    Text(if (inCloud) "从云收藏移除" else "加入云收藏", color = white)
                }
                Text(account.message, fontSize = 11.sp, color = secondary)
            }
            HorizontalDivider(color = Color(0xFF4B4A50))
            OutlinedButton(
                onClick = { onDismiss(); onDownload() },
                modifier = Modifier.fillMaxWidth().testTag("download_current_episode"),
            ) { Text("下载当前剧集", color = white) }
        }
    }
}

@Composable
private fun PlaybackHeading(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = Color.White)
}

@Composable
private fun <T> PlaybackChoices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEachIndexed { index, (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF38373E),
                    labelColor = Color.White,
                    selectedContainerColor = HongguoColors.accent,
                    selectedLabelColor = Color.White,
                ),
                modifier = Modifier.testTag(tag + "_" + index),
            )
        }
    }
}
