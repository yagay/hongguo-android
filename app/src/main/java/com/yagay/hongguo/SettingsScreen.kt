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
                CompactSettingsSection("账号与隐私") {
                    SettingsNavRow(
                        icon = Icons.Outlined.PersonOutline,
                        title = "红果账号",
                        subtitle = account.profile?.nickname?.let { "已登录 · $it" }
                            ?: if (account.checking) "正在检查登录状态" else "登录与账号管理",
                        tag = "open_account",
                        onClick = { model.show(Modal.ACCOUNT) },
                    )
                    if (account.profile != null) {
                        CompactSettingsDivider()
                        SettingToggle(
                            "刷短剧使用登录账号",
                            "向官方接口发送登录会话；推荐结果由服务端决定",
                            account.accountRecommendations,
                            model::setAccountRecommendations, "settings_account_recommendations")
                        CompactSettingsDivider()
                        SettingToggle("同步观看进度", "仅同步开启后产生的进度",
                            account.syncEnabled,
                            model.account::setProgressSync, "settings_account_sync")
                    }
                }
            }
            item {
                CompactSettingsSection("外观") {
                    CompactSettingsRow(
                        title = "主题样式",
                        value = when (state.themeStyle) {
                            "immersive" -> "沉浸暗场"
                            "cards" -> "内容卡片"
                            else -> "暖白精修"
                        },
                        tag = "theme_style_row",
                        onClick = { choiceSheet = "theme_style" },
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
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
                CompactSettingsSection("内容来源") {
                    CompactSettingsRow(
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
                CompactSettingsSection("推荐管理") {
                    CompactSettingsRow(
                        title = "恢复隐藏的推荐",
                        value = "已隐藏 ${state.hiddenRecommendationIds.size} 部",
                        tag = "settings_restore_recommendations",
                        enabled = state.hiddenRecommendationIds.isNotEmpty(),
                        onClick = model::restoreHiddenRecommendations,
                    )
                }
            }
            item {
                CompactSettingsSection("追剧更新提醒") {
                    SettingToggle(
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
                    CompactSettingsDivider()
                    CompactSettingsRow(
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
                CompactSettingsSection("本机数据备份") {
                    CompactSettingsRow(
                        title = "导出本机备份",
                        subtitle = "收藏、历史、稍后看和点赞（不含登录凭据）",
                        tag = "export_local_backup",
                        onClick = { localExporter.launch("Hongguo-local-backup.json") },
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
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
                CompactSettingsSection("诊断与日志") {
                    SettingToggle(
                        title = "记录诊断事件",
                        explanation = if (state.logEnabled) "已开启 · 本机最多保留 256 KB"
                                      else "已关闭 · 保留已有日志",
                        enabled = state.logEnabled,
                        onChange = model::setLoggingEnabled,
                        testTag = "log_switch",
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
                        title = "导出诊断日志",
                        value = "TXT",
                        tag = "export_diagnostic",
                        onClick = {
                            val stamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            exporter.launch("Hongguo-diagnostic-$stamp.txt")
                        },
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
                        title = "刷新诊断信息",
                        tag = "settings_refresh_diagnostics",
                        onClick = model::refreshDiagnostics,
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
                        title = "清空诊断日志",
                        tag = "settings_clear_diagnostics",
                        onClick = { confirmClear = true },
                    )
                    CompactSettingsDivider()
                    CompactSettingsRow(
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
                CompactSettingsSection("关于") {
                    CompactSettingsRow(
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
            "theme_style" -> listOf(
                "warm" to "暖白精修", "immersive" to "沉浸暗场",
                "cards" to "内容卡片",
            )
            else -> listOf(
                "light" to "白天模式", "dark" to "夜间模式",
                "system" to "跟随系统",
            )
        }
        val selected = if (choice == "theme_style") state.themeStyle else state.themeMode
        SettingsChoiceSheet(
            title = if (choice == "theme_style") "主题样式" else "显示模式",
            options = options,
            selected = selected,
            tagPrefix = choice,
            onSelect = {
                if (choice == "theme_style") model.setThemeStyle(it)
                else model.setThemeMode(it)
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

/**
 * Compact settings groups: a short section label and conventional one-line
 * Android-style preference rows. Large padded choice grids are intentionally
 * kept out of the main settings list.
 */
@Composable
private fun CompactSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            color = HongguoColors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp, top = 7.dp, bottom = 2.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = HongguoColors.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun CompactSettingsDivider() {
    HorizontalDivider(color = HongguoColors.divider)
}

@Composable
private fun CompactSettingsRow(
    title: String,
    tag: String,
    onClick: () -> Unit = {},
    value: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    expanded: Boolean = false,
    showChevron: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = if (subtitle.isNullOrBlank()) 46.dp else 54.dp)
            .then(if (enabled && showChevron) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(
            icon, contentDescription = null,
            tint = HongguoColors.accent,
            modifier = Modifier.size(19.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (enabled) HongguoColors.ink else HongguoColors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, fontSize = 11.sp, color = HongguoColors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!value.isNullOrBlank()) Text(
            value,
            fontSize = 11.sp,
            color = if (enabled) HongguoColors.muted else HongguoColors.divider,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
        )
        if (showChevron) Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = HongguoColors.muted,
            modifier = Modifier.size(17.dp),
        )
    }
}

@Composable
private fun SettingsChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    tagPrefix: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    HongguoModalSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HongguoSheetHeader(title = title)
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clickable { onSelect(value) }
                        .testTag("${tagPrefix}_${value}")
                        .padding(horizontal = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label, color = HongguoColors.ink,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected == value) Icon(
                        Icons.Outlined.Check, contentDescription = "已选择",
                        tint = HongguoColors.accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (value != options.last().first) CompactSettingsDivider()
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) = CompactSettingsRow(title = title, subtitle = subtitle, icon = icon,
    tag = tag, onClick = onClick)

@Composable
private fun SettingToggle(
    title: String,
    explanation: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    testTag: String,
) = HongguoToggleRow(title, enabled, onChange, testTag, description = explanation)
