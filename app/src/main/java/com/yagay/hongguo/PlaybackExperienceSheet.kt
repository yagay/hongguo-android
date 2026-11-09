package com.yagay.hongguo

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
    autoPipEnabled: Boolean,
    manualPipButtonVisible: Boolean,
    pipSystemStatus: VideoPiPAccess.Status,
    onAutoPipEnabled: (Boolean) -> Unit,
    onManualPipButtonVisible: (Boolean) -> Unit,
    onOpenPipSettings: () -> Unit,
    onTestManualPip: () -> Unit,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onFillVideo: (Boolean) -> Unit,
    onQuality: (Int) -> Unit,
    onDownload: () -> Unit,
    initialSection: PlaybackQuickSetting = PlaybackQuickSetting.VIDEO,
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    val focused = initialSection

    val foreground = HongguoColors.ink
    val secondary = HongguoColors.muted
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
                        .background(HongguoColors.background,
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
                            HorizontalDivider(color = HongguoColors.divider)
                            HongguoToggleRow(
                                title = "离开应用自动小窗",
                                checked = autoPipEnabled,
                                onCheckedChange = onAutoPipEnabled,
                                tag = "player_external_pip_enabled",
                                description = if (autoPipEnabled)
                                    "已开启：播放中离开应用时自动进入系统画中画"
                                else "已关闭：不自动进入小窗，不影响手动小窗按钮",
                            )
                            HongguoToggleRow(
                                title = "显示手动小窗按钮",
                                checked = manualPipButtonVisible,
                                onCheckedChange = onManualPipButtonVisible,
                                tag = "player_manual_pip_button_visible",
                                description = "控制播放器顶部的画中画图标，与自动小窗独立",
                            )
                            when (pipSystemStatus) {
                                VideoPiPAccess.Status.BLOCKED -> {
                                    Text(
                                        "系统未允许红果使用画中画，应用内开关不能覆盖系统权限。",
                                        color = HongguoColors.accent,
                                        fontSize = 12.sp,
                                        modifier = Modifier.testTag("pip_permission_blocked"),
                                    )
                                    HongguoPlayerSheetAction(
                                        label = "打开系统画中画权限",
                                        onClick = onOpenPipSettings,
                                        modifier = Modifier.testTag("pip_open_system_settings"),
                                    )
                                }
                                VideoPiPAccess.Status.UNSUPPORTED -> Text(
                                    "当前设备不支持安卓系统画中画。",
                                    color = HongguoColors.muted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.testTag("pip_unsupported"),
                                )
                                VideoPiPAccess.Status.UNKNOWN -> {
                                    Text(
                                        "无法确认系统画中画权限；可直接测试或打开系统设置。",
                                        fontSize = 11.sp,
                                        color = HongguoColors.muted,
                                    )
                                    HongguoPlayerSheetAction(
                                        label = "检查系统画中画权限",
                                        onClick = onOpenPipSettings,
                                        modifier = Modifier.testTag("pip_open_system_settings"),
                                    )
                                }
                                VideoPiPAccess.Status.ALLOWED -> Text(
                                    "系统权限已允许；自动小窗和手动按钮分别由各自开关控制",
                                    fontSize = 11.sp,
                                    color = HongguoColors.muted,
                                    modifier = Modifier.testTag("pip_permission_allowed"),
                                )
                            }
                            if (pipSystemStatus != VideoPiPAccess.Status.UNSUPPORTED &&
                                pipSystemStatus != VideoPiPAccess.Status.BLOCKED) {
                                HongguoPlayerSheetAction(
                                    label = "测试手动小窗",
                                    onClick = onTestManualPip,
                                    modifier = Modifier.testTag("pip_test_now"),
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
                            HongguoToggleRow(
                                title = "弹幕设置",
                                checked = state.danmakuEnabled,
                                onCheckedChange = model::setDanmaku,
                                tag = "player_danmaku_enabled",
                            )
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
                    HorizontalDivider(color = HongguoColors.divider)
                    PlaybackToggle("自动连播下一集／下一部推荐", state.autoPlayNext,
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
                        HorizontalDivider(color = HongguoColors.divider)
                        val currentId = state.detail.drama.id
                        val inCloud = account.favorites.any {
                            it.series.id == currentId
                        }
                        HongguoAction(
                            label = if (inCloud) "从云收藏移除" else "加入云收藏",
                            enabled = !account.busy,
                            onClick = {
                                model.account.cloudFavorite(currentId, !inCloud)
                            },
                            modifier = Modifier.fillMaxWidth()
                                .testTag("player_cloud_favorite"),
                        )
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
                            HongguoAction(
                                label = "播完本集后停止",
                                onClick = { model.sleepAfterEpisodes(1) },
                                modifier = Modifier.testTag("sleep_after_episode"),
                                style = HongguoActionStyle.QUIET,
                            )
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
) = HongguoToggleRow(title, checked, onCheckedChange, tag)

@Composable
private fun PlaybackHeading(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = HongguoColors.ink)
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
 )
