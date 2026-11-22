package com.yagay.hongguo

import android.net.Uri
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Sectioned settings: existing backend configuration and diagnostics are kept. */
@Composable
internal fun SettingsScreen(state: AppState, model: DramaViewModel) {
    var address by remember(state.backend) { mutableStateOf(state.backend) }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var showLogPreview by rememberSaveable { mutableStateOf(false) }
    var backendExpanded by rememberSaveable { mutableStateOf(false) }
    var choiceSheet by rememberSaveable { mutableStateOf<String?>(null) }
    val account by model.account.state.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) model.exportDiagnostics(uri) }

    val localExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) model.exportLocalBackup(uri) }
    val localImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pendingRestore = uri }

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) model.setEpisodeUpdateNotifications(true)
    }
    LaunchedEffect(Unit) { model.refreshDiagnostics() }
    BackHandler { model.closeModal() }

    Scaffold(
        containerColor = HongguoColors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            HongguoPageHeader(
                title = "设置",
                onBack = model::closeModal,
                modifier = Modifier.background(HongguoColors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings_page"),
            contentPadding = PaddingValues(start = 12.dp,
                top = 6.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item {
                HongguoSettingsSection("账号与隐私") {
                    HongguoSettingsRow(
                        icon = Icons.Outlined.PersonOutline,
                        title = "红果账号",
                        subtitle = account.profile?.nickname?.let { "已登录 · $it" }
                            ?: if (account.checking) "正在检查登录状态" else "登录与账号管理",
                        tag = "open_account",
                        onClick = { model.show(Modal.ACCOUNT) },
                    )
                    if (account.profile != null) {
                        HongguoSettingsDivider()
                        HongguoSettingsToggle(
                            "刷短剧使用登录账号",
                            "向官方接口发送登录会话；推荐结果由服务端决定",
                            account.accountRecommendations,
                            model::setAccountRecommendations, "settings_account_recommendations")
                        HongguoSettingsDivider()
                        HongguoSettingsToggle("同步观看进度", "仅同步开启后产生的进度",
                            account.syncEnabled,
                            model.account::setProgressSync, "settings_account_sync")
                    }
                }
            }
            item {
                HongguoSettingsSection("外观") {
                    HongguoSettingsRow(
                        title = "界面风格",
                        value = if (state.uiStyle == "material3") "Material 3" else "经典红果",
                        subtitle = "经典界面完整保留，随时切换；Material 3 使用系统动态配色",
                        tag = "ui_style_row",
                        onClick = { choiceSheet = "ui_style" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "主题样式",
                        value = when (state.themeStyle) {
                            "immersive" -> "沉浸暗场"
                            "cards" -> "内容卡片"
                            else -> "暖白精修"
                        },
                        tag = "theme_style_row",
                        onClick = { choiceSheet = "theme_style" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "显示模式",
                        value = when (state.themeMode) {
                            "dark" -> "夜间模式"
                            "system" -> "跟随系统"
                            else -> "白天模式"
                        },
                        tag = "theme_mode_row",
                        onClick = { choiceSheet = "theme_mode" },
                    )
                }
            }
            // Playback brightness, audio enhancement and other video controls
            // live exclusively in the player's own PlaybackExperienceSheet.
            item {
                HongguoSettingsSection("内容来源") {
                    HongguoSettingsRow(
                        title = "内容接口",
                        value = if (state.backend.isBlank()) "原生模式" else "自定义 API",
                        tag = "backend_settings_entry",
                        onClick = { backendExpanded = !backendExpanded },
                        expanded = backendExpanded,
                    )
                    if (backendExpanded) {
                        Text(
                            "默认使用原生模块；可选连接自建接口。",
                            color = HongguoColors.muted,
                            fontSize = 11.sp,
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("替代 API 地址（可选）") },
                            placeholder = { Text("https://your-server.example") },
                            modifier = Modifier.fillMaxWidth().testTag("backend_address"),
                            shape = RoundedCornerShape(HongguoUi.controlRadius),
                            singleLine = true,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            HongguoAction(
                                label = "保存连接",
                                onClick = { model.saveBackend(address) },
                                modifier = Modifier.testTag("save_backend"),
                                style = HongguoActionStyle.PRIMARY,
                            )
                            HongguoAction(
                                label = "恢复原生模式",
                                onClick = {
                                    address = ""
                                    model.saveBackend("")
                                },
                            )
                        }
                        state.settingsMessage?.let {
                            Text(it, color = HongguoColors.accent, fontSize = 12.sp)
                        }
                        Text(
                            "公网 API 请使用 HTTPS；127.0.0.1 不指向电脑。",
                            color = HongguoColors.muted, fontSize = 11.sp,
                        )
                    }
                }
            }
            item {
                HongguoSettingsSection("视频读取") {
                    HongguoSettingsToggle(
                        "预加载下一集／下一部",
                        "提前缓存相邻视频，已完成的分块不必等整集下载完即可播放",
                        state.nextVideoPreloadEnabled,
                        model::setNextVideoPreloadEnabled,
                        "settings_next_video_preload",
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsToggle(
                        "仅在 Wi-Fi／非计费网络预加载",
                        "关闭后也可使用移动数据，较大缓存可能产生明显流量",
                        state.nextVideoPreloadWifiOnly,
                        model::setNextVideoPreloadWifiOnly,
                        "settings_preload_wifi_only",
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "每集缓存上限",
                        value = "${state.videoCachePerEpisodeMb} MiB",
                        subtitle = "可选 4–512 MiB，使用磁盘，不会整集占用内存",
                        tag = "settings_video_cache_per_episode",
                        onClick = { choiceSheet = "video_cache_per_episode" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "缓存总空间",
                        value = "${state.videoCacheTotalMb} MiB",
                        subtitle = "所有视频共享的 LRU 空间，可选最大 4 GiB",
                        tag = "settings_video_cache_total",
                        onClick = { choiceSheet = "video_cache_total" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "提前预加载数量",
                        value = "${state.videoPreloadCount} 部／集",
                        subtitle = "按播放顺序准备接下来的视频，不同时下载",
                        tag = "settings_video_preload_count",
                        onClick = { choiceSheet = "video_preload_count" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "预加载启动缓冲",
                        value = "${state.videoPreloadBufferSeconds} 秒",
                        subtitle = "当前视频已缓冲多少秒后，才允许后台预加载",
                        tag = "settings_video_preload_buffer",
                        onClick = { choiceSheet = "video_preload_buffer" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "每部预加载最长时间",
                        value = "${state.videoPreloadDeadlineSeconds} 秒",
                        subtitle = "可选最长 10 分钟；网络较慢时保留已下载部分",
                        tag = "settings_video_preload_deadline",
                        onClick = { choiceSheet = "video_preload_deadline" },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "查看当前缓存用量",
                        subtitle = "刷新本次会话实际存储的媒体字节数",
                        tag = "settings_video_cache_usage",
                        onClick = model::inspectVideoCache,
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "清理其它视频缓存",
                        subtitle = "保留正在播放的缓存，删除其它已下载数据",
                        tag = "settings_video_cache_clear_others",
                        onClick = model::clearOtherVideoCache,
                    )
                    state.settingsMessage?.takeIf { it.startsWith("缓存") }?.let {
                        Text(it, color = HongguoColors.accent, fontSize = 11.sp)
                    }
                    HongguoSettingsDivider()
                    Text(
                        "视频按块缓存原始网络字节；加密视频在解密前保存，缓冲变慢时让正在播放的视频优先，缓存不必完整即可读取。",
                        color = HongguoColors.muted, fontSize = 12.sp,
                    )
                    Text(
                        "缓存位于应用临时目录，重启应用后重新建立；不会将播放密钥或签名 URL 存盘。系统清理缓存也可能释放文件。",
                        color = HongguoColors.muted, fontSize = 11.sp,
                    )
                }
            }
            item {
                HongguoSettingsSection("推荐管理") {
                    HongguoSettingsRow(
                        title = "恢复隐藏的推荐",
                        value = "已隐藏 ${state.hiddenRecommendationIds.size} 部",
                        tag = "settings_restore_recommendations",
                        enabled = state.hiddenRecommendationIds.isNotEmpty(),
                        onClick = model::restoreHiddenRecommendations,
                    )
                }
            }
            item {
                HongguoSettingsSection("追剧更新提醒") {
                    HongguoSettingsToggle(
                        "定期检查收藏更新",
                        "联网时约每 12 小时检查，实际时间由系统决定",
                        state.episodeUpdateNotificationsEnabled,
                        { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33) {
                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else model.setEpisodeUpdateNotifications(enabled)
                        },
                        "settings_tracking_notification",
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = if (state.trackingUpdates) "正在检查…" else "立即检查追剧更新",
                        value = "手动检查",
                        tag = "settings_check_updates",
                        enabled = !state.trackingUpdates,
                        onClick = model::checkTrackedUpdates,
                    )
                    state.trackingUpdateMessage?.let {
                        Text(it, color = HongguoColors.muted, fontSize = 11.sp)
                    }
                }
            }
            item {
                HongguoSettingsSection("本机数据备份") {
                    HongguoSettingsRow(
                        title = "导出本机备份",
                        subtitle = "收藏、历史、稍后看和点赞（不含登录凭据）",
                        tag = "export_local_backup",
                        onClick = { localExporter.launch("Hongguo-local-backup.json") },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "导入并恢复",
                        tag = "import_local_backup",
                        onClick = {
                            localImporter.launch(arrayOf("application/json", "text/plain"))
                        },
                    )
                    // Keep the original backup/import success and error feedback.
                    state.settingsMessage?.let {
                        Text(it, color = HongguoColors.accent, fontSize = 11.sp)
                    }
                }
            }
            item {
                HongguoSettingsSection("诊断与日志") {
                    HongguoSettingsToggle(
                        title = "记录诊断事件",
                        explanation = if (state.logEnabled) "已开启 · 本机最多保留 256 KB"
                                      else "已关闭 · 保留已有日志",
                        enabled = state.logEnabled,
                        onChange = model::setLoggingEnabled,
                        testTag = "log_switch",
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "导出诊断日志",
                        value = "TXT",
                        tag = "export_diagnostic",
                        onClick = {
                            val stamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            exporter.launch("Hongguo-diagnostic-$stamp.txt")
                        },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "刷新诊断信息",
                        tag = "settings_refresh_diagnostics",
                        onClick = model::refreshDiagnostics,
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "清空诊断日志",
                        tag = "settings_clear_diagnostics",
                        onClick = { confirmClear = true },
                    )
                    HongguoSettingsDivider()
                    HongguoSettingsRow(
                        title = "日志预览",
                        value = if (showLogPreview) "收起" else "展开",
                        tag = "diagnostic_preview_toggle",
                        expanded = showLogPreview,
                        onClick = {
                            showLogPreview = !showLogPreview
                            if (showLogPreview) model.refreshDiagnostics()
                        },
                    )
                    if (showLogPreview) {
                        HongguoCard(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                            Text(
                                state.logText.ifBlank { "暂无诊断记录" }.takeLast(14000),
                                color = HongguoColors.ink,
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(10.dp).testTag("diagnostic_preview"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                    state.logNotice?.let {
                        Text(it, color = HongguoColors.accent, fontSize = 11.sp)
                    }
                }
            }
            item {
                HongguoSettingsSection("关于") {
                    HongguoSettingsRow(
                        title = "红果短剧 · Native Rewrite",
                        value = BuildConfig.VERSION_NAME,
                        tag = "settings_about",
                        showChevron = false,
                    )
                    Text(
                        "Jetpack Compose + Media3 · 部分内容受上游接口可用性影响",
                        color = HongguoColors.muted, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
    }

    choiceSheet?.let { choice ->
        val options = when (choice) {
            "ui_style" -> listOf(
                "classic" to "经典红果", "material3" to "Material 3",
            )
            "theme_style" -> listOf(
                "warm" to "暖白精修", "immersive" to "沉浸暗场",
                "cards" to "内容卡片",
            )
            "video_cache_per_episode" -> listOf(4, 8, 16, 32, 64, 128, 256, 512)
                .map { it.toString() to "$it MiB" }
            "video_cache_total" -> listOf(256, 512, 1024, 2048, 4096)
                .map { it.toString() to if (it >= 1024)
                    "${it / 1024} GiB" else "$it MiB" }
            "video_preload_count" -> listOf(1, 2, 3, 5)
                .map { it.toString() to "$it 部／集" }
            "video_preload_buffer" -> listOf(0, 3, 6, 12, 20, 30)
                .map { it.toString() to "$it 秒" }
            "video_preload_deadline" -> listOf(30, 60, 120, 300, 600)
                .map { it.toString() to if (it >= 60)
                    "${it / 60} 分钟" else "$it 秒" }
            else -> listOf(
                "light" to "白天模式", "dark" to "夜间模式",
                "system" to "跟随系统",
            )
        }
        val selected = when (choice) {
            "ui_style" -> state.uiStyle
            "theme_style" -> state.themeStyle
            "video_cache_per_episode" -> state.videoCachePerEpisodeMb.toString()
            "video_cache_total" -> state.videoCacheTotalMb.toString()
            "video_preload_count" -> state.videoPreloadCount.toString()
            "video_preload_buffer" -> state.videoPreloadBufferSeconds.toString()
            "video_preload_deadline" -> state.videoPreloadDeadlineSeconds.toString()
            else -> state.themeMode
        }
        HongguoSingleChoiceSheet(
            title = when (choice) {
                "ui_style" -> "界面风格"
                "theme_style" -> "主题样式"
                "video_cache_per_episode" -> "每集缓存上限"
                "video_cache_total" -> "缓存总空间"
                "video_preload_count" -> "预加载数量"
                "video_preload_buffer" -> "预加载启动缓冲"
                "video_preload_deadline" -> "每部最长预加载时间"
                else -> "显示模式"
            },
            options = options,
            selected = selected,
            tagPrefix = choice,
            onSelect = {
                when (choice) {
                    "ui_style" -> model.setUiStyle(it)
                    "theme_style" -> model.setThemeStyle(it)
                    "video_cache_per_episode" -> it.toIntOrNull()?.let(model::setVideoCachePerEpisodeMb)
                    "video_cache_total" -> it.toIntOrNull()?.let(model::setVideoCacheTotalMb)
                    "video_preload_count" -> it.toIntOrNull()?.let(model::setVideoPreloadCount)
                    "video_preload_buffer" -> it.toIntOrNull()?.let(model::setVideoPreloadBufferSeconds)
                    "video_preload_deadline" -> it.toIntOrNull()?.let(model::setVideoPreloadDeadlineSeconds)
                    else -> model.setThemeMode(it)
                }
                choiceSheet = null
            },
            onDismiss = { choiceSheet = null },
        )
    }

    pendingRestore?.let { uri ->
        HongguoConfirmDialog(
            title = "恢复本机片单？",
            message = "将替换本机收藏、历史、稍后看和本机点赞。此操作不可撤销，但不会影响云端账号。",
            confirmLabel = "确认恢复",
            destructive = true,
            onConfirm = {
                pendingRestore = null
                model.importLocalBackup(uri)
            },
            onDismiss = { pendingRestore = null },
        )
    }
    if (confirmClear) {
        HongguoConfirmDialog(
            title = "清空诊断日志？",
            message = "不会影响收藏、播放历史和设置。",
            confirmLabel = "清空",
            destructive = true,
            onConfirm = {
                confirmClear = false
                model.clearDiagnostics()
            },
            onDismiss = { confirmClear = false },
        )
    }
}
