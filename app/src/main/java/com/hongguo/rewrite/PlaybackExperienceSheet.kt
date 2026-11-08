package com.hongguo.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three-dot menu is a small action panel, not a duplicate full settings
 * page. Only one relevant control group opens at a time; less-used options
 * are behind an explicit fold. Playback and all previous controls are kept.
 */
internal enum class PlaybackQuickSetting {
    VIDEO, SPEED, AUDIO, DANMAKU, TIMER, PREFERENCES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackExperienceSheet(
    state: AppState,
    model: DramaViewModel,
    speed: Float,
    fillVideo: Boolean,
    externalPipEnabled: Boolean,
    onExternalPipEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onFillVideo: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onDownload: () -> Unit,
    initialSection: PlaybackQuickSetting = PlaybackQuickSetting.VIDEO,
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    val focused = initialSection

    val white = Color.White
    val secondary = Color(0xFFC5C3C9)
    val qualityAvailable = state.backend.isBlank()
    val qualityLabel = if (state.qualityLimit == 0) "自动"
        else "${state.qualityLimit}P"
    val speedLabel = if (speed == speed.toInt().toFloat())
        "${speed.toInt()}×" else "${speed}×"


    HongguoPlayerSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("playback_settings_sheet"),
    ) {
        HongguoPlayerPanel(
            modifier = Modifier.heightIn(max = 530.dp)
                .verticalScroll(rememberScrollState()),
            title = when (focused) {
                PlaybackQuickSetting.VIDEO -> "画面设置"
                PlaybackQuickSetting.SPEED -> "倍速"
                PlaybackQuickSetting.AUDIO -> "音效"
                PlaybackQuickSetting.DANMAKU -> "弹幕设置"
                PlaybackQuickSetting.TIMER -> "定时停止"
                PlaybackQuickSetting.PREFERENCES -> "播放偏好"
            },
            actions = {
                HongguoPlayerSheetAction(label = "完成", onClick = onDismiss)
            },
        ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF302E35),
                            RoundedCornerShape(HongguoUi.controlRadius))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    when (focused) {
                        PlaybackQuickSetting.VIDEO -> {
                            if (qualityAvailable) {
                                PlaybackHeading("清晰度上限")
                                PlaybackChoices(
                                    listOf(0 to "自动", 360 to "360P", 480 to "480P",
                                        720 to "720P", 1080 to "1080P"),
                                    state.qualityLimit, onQuality, "video_quality",
                                )
                            }
                            PlaybackHeading("画面比例")
                            PlaybackChoices(
                                listOf(false to "适应画面", true to "铺满画面"),
                                fillVideo, onFillVideo, "video_frame_mode",
                            )
                            HorizontalDivider(color = Color(0xFF4B4A50))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("应用外悬浮播放", fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium)
                                    Text("离开应用时自动画中画，也控制播放器上的画中画入口",
                                        color = secondary, fontSize = 11.sp,
                                        lineHeight = 15.sp)
                                }
                                Switch(
                                    checked = externalPipEnabled,
                                    onCheckedChange = onExternalPipEnabled,
                                    modifier = Modifier.testTag("player_external_pip_enabled"),
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = HongguoColors.accent,
                                        checkedThumbColor = Color.White,
                                    ),
                                )
                            }
                            PlaybackHeading("屏幕亮度")
                            PlaybackChoices(
                                listOf(-1 to "系统", 65 to "65%", 80 to "80%",
                                    90 to "90%", 100 to "100%"),
                                state.videoBrightnessPercent,
                                model::setVideoBrightnessPercent, "player_brightness",
                            )
                        }
                        PlaybackQuickSetting.SPEED -> {
                            PlaybackHeading("播放倍速")
                            PlaybackChoices(
                                listOf(0.75f to "0.75×", 1f to "1×", 1.25f to "1.25×",
                                    1.5f to "1.5×", 2f to "2×", 3f to "3×"),
                                speed, onSpeed, "video_speed",
                            )
                        }
                        PlaybackQuickSetting.AUDIO -> {
                            PlaybackHeading("声音增强")
                            PlaybackChoices(
                                listOf(0 to "原音", 3 to "+3dB", 6 to "+6dB"),
                                state.playbackAudioBoostDb,
                                model::setPlaybackAudioBoostDb, "audio_boost",
                            )
                        }
                        PlaybackQuickSetting.DANMAKU -> {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                PlaybackHeading("弹幕设置")
                                Spacer(Modifier.weight(1f))
                                Switch(
                                    checked = state.danmakuEnabled,
                                    onCheckedChange = model::setDanmaku,
                                    modifier = Modifier.testTag("player_danmaku_enabled"),
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = HongguoColors.accent,
                                        checkedThumbColor = Color.White,
                                    ),
                                )
                            }
                            PlaybackHeading("移动速度")
                            PlaybackChoices(
                                listOf(0 to "慢", 1 to "标准", 2 to "快"),
                                state.danmakuSpeed, model::setDanmakuSpeed,
                                "danmaku_speed",
                            )
                            PlaybackHeading("轨道数量")
                            PlaybackChoices(
                                (1..5).map { it to "${it}轨" },
                                state.danmakuDensity, model::setDanmakuDensity,
                                "danmaku_lanes",
                            )
                            PlaybackHeading("字体大小")
                            PlaybackChoices(
                                listOf(12, 14, 16, 18).map { it to "$it sp" },
                                state.danmakuFontSize, model::setDanmakuFontSize,
                                "danmaku_font",
                            )
                        }
                        PlaybackQuickSetting.PREFERENCES -> {
                    if (state.feedMode) {
                        PlaybackHeading("刷剧内容")
                        PlaybackChoices(
                            listOf("all" to "全部推荐", "real" to "只看真人"),
                            state.reelsMode, model::setReelsMode, "official_reels_mode",
                        )
                    }
                    HorizontalDivider(color = Color(0xFF4B4A50))
                    PlaybackToggle("自动连播下一集", state.autoPlayNext,
                        model::setAutoPlayNext, "player_auto_next")
                    PlaybackToggle("预加载下一集", state.preloadNext,
                        model::setPreloadNext, "player_preload_next")
                    PlaybackToggle("播放失败自动重试", state.retryPlaybackAutomatically,
                        model::setAutomaticPlaybackRetry, "player_auto_retry")

                    PlaybackHeading("跳过片头")
                    PlaybackChoices(
                        listOf(0 to "关闭", 10 to "10秒", 20 to "20秒",
                            30 to "30秒", 60 to "60秒"),
                        state.skipIntroSeconds, model::setSkipIntroSeconds,
                        "player_skip_intro",
                    )
                    PlaybackHeading("跳过片尾")
                    PlaybackChoices(
                        listOf(0 to "关闭", 5 to "5秒", 10 to "10秒",
                            15 to "15秒", 30 to "30秒"),
                        state.skipOutroSeconds, model::setSkipOutroSeconds,
                        "player_skip_outro",
                    )

                    if (account.profile != null && state.detail != null) {
                        HorizontalDivider(color = Color(0xFF4B4A50))
                        val currentId = state.detail.drama.id
                        val inCloud = account.favorites.any {
                            it.series.id == currentId
                        }
                        OutlinedButton(
                            enabled = !account.busy,
                            onClick = {
                                model.account.cloudFavorite(currentId, !inCloud)
                            },
                            modifier = Modifier.fillMaxWidth()
                                .height(40.dp)
                                .testTag("player_cloud_favorite"),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = white,
                            ),
                        ) {
                            Text(if (inCloud) "从云收藏移除" else "加入云收藏",
                                fontSize = 13.sp)
                        }
                        if (account.message.isNotBlank()) {
                            Text(account.message, color = secondary, fontSize = 11.sp)
                        }
                    }
                        }
                        PlaybackQuickSetting.TIMER -> {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                PlaybackHeading("定时停止")
                                Spacer(Modifier.weight(1f))
                                Text("当前：${state.sleepTimerLabel}", fontSize = 11.sp,
                                    color = secondary)
                            }
                            PlaybackChoices(
                                listOf(0 to "关闭", 15 to "15分", 30 to "30分",
                                    60 to "60分"),
                                if (state.sleepTimerLabel == "关闭") 0 else -1,
                                model::sleepAfterMinutes, "sleep_timer",
                            )
                            TextButton(
                                onClick = { model.sleepAfterEpisodes(1) },
                                modifier = Modifier.testTag("sleep_after_episode"),
                            ) {
                                Text("播完本集后停止", color = white, fontSize = 12.sp)
                            }
                        }
                        null -> Unit
                    }
                }
        }
    }
}

@Composable
private fun PlaybackToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) = HongguoToggleRow(title, checked, onCheckedChange, tag, dark = true)

@Composable
private fun PlaybackHeading(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = Color.White)
}

@Composable
private fun <T> PlaybackChoices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: String,
) = HongguoChoiceGroup(
    options = options,
    selected = selected,
    onSelect = onSelect,
    tag = tag,
    dark = true,
)
