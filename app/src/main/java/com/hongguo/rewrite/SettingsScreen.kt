package com.hongguo.rewrite

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
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
    var showLogPreview by rememberSaveable { mutableStateOf(false) }
    val account by model.account.state.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) model.exportDiagnostics(uri) }

    LaunchedEffect(Unit) { model.refreshDiagnostics() }
    BackHandler { model.closeModal() }

    Scaffold(
        containerColor = HongguoColors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(HongguoColors.surface)
                    .height(60.dp).padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = model::closeModal) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回",
                        tint = HongguoColors.ink)
                }
                Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = HongguoColors.ink, modifier = Modifier.weight(1f))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings_page"),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp,
                end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroup("账号与隐私") {
                    SettingsNavRow(
                        icon = Icons.Outlined.PersonOutline,
                        title = "红果账号",
                        subtitle = account.profile?.nickname?.let { "已登录 · $it" }
                            ?: if (account.checking) "正在检查登录状态" else "登录方式、云片单与账号管理",
                        tag = "open_account",
                        onClick = { model.show(Modal.ACCOUNT) },
                    )
                    if (account.profile != null) {
                        HorizontalDivider()
                        SettingToggle("账号推荐", "默认关闭，按本人账号推荐", account.accountRecommendations,
                            model.account::setAccountRecommendations, "settings_account_recommendations")
                        HorizontalDivider()
                        SettingToggle("同步观看进度", "默认关闭，仅上报此后播放进度", account.syncEnabled,
                            model.account::setProgressSync, "settings_account_sync")
                    }
                }
            }
            item {
                SettingsGroup("播放偏好") {
                    SettingToggle("自动连播下一集",
                        "当前集正常播放结束后自动切换下一集；手动切集不受影响",
                        state.autoPlayNext, model::setAutoPlayNext, "settings_autoplay")
                    HorizontalDivider(color = HongguoColors.divider)
                    SettingsNavRow(
                        icon = Icons.Outlined.PlayCircleOutline,
                        title = "播放器设置",
                        subtitle = "画质、倍速、弹幕、定时停止等在播放页右上角调整",
                        tag = "settings_player_preferences",
                        onClick = model::closeModal,
                    )
                }
            }
            item {
                SettingsGroup("内容来源") {
                    Text(if (state.backend.isBlank()) "原生内容模式" else "使用自定义 API",
                        fontWeight = FontWeight.SemiBold,
                        color = HongguoColors.ink)
                    Text("默认使用手机内置的原生模块。可选连接自建网页版服务；视频播放仍受上游接口可用性影响。",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("替代 API 地址（可选）") },
                        placeholder = { Text("https://your-server.example") },
                        modifier = Modifier.fillMaxWidth().testTag("backend_address"),
                        shape = RoundedCornerShape(13.dp),
                        singleLine = true,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Button(
                            onClick = { model.saveBackend(address) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("save_backend"),
                        ) { Text("保存连接") }
                        OutlinedButton(
                            onClick = {
                                address = ""
                                model.saveBackend("")
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("恢复原生模式") }
                    }
                    state.settingsMessage?.let { Text(it, color = HongguoColors.accent,
                        fontSize = 12.sp) }
                    Text("公网 API 请使用 HTTPS；手机不能通过 127.0.0.1 访问电脑上的服务。",
                        color = HongguoColors.muted, fontSize = 11.sp)
                }
            }
            item {
                SettingsGroup("推荐管理") {
                    Text("已隐藏 ${state.hiddenRecommendationIds.size} 部剧集",
                        color = HongguoColors.ink, fontWeight = FontWeight.SemiBold)
                    Text("在首页推荐封面右上角选择「不再推荐」后，该剧不会再出现在推荐列表中；其他分类不受影响。",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = model::restoreHiddenRecommendations,
                        enabled = state.hiddenRecommendationIds.isNotEmpty(),
                        modifier = Modifier.testTag("settings_restore_recommendations"),
                    ) { Text("恢复全部隐藏推荐") }
                }
            }
            item {
                SettingsGroup("诊断与日志") {
                    Text("遇到播放失败时，可在这里导出诊断日志。日志包含请求阶段、HTTP 状态及错误码，不采集 API 密钥、搜索内容或视频地址。",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    SettingToggle(
                        title = "记录诊断事件",
                        explanation = if (state.logEnabled) "已启用 · 本机最多保留 256 KB"
                                      else "已停用 · 原有日志仍保留",
                        enabled = state.logEnabled,
                        onChange = model::setLoggingEnabled,
                        testTag = "log_switch",
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Button(
                            onClick = {
                                val stamp = LocalDateTime.now()
                                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                                exporter.launch("Hongguo-diagnostic-$stamp.txt")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("export_diagnostic"),
                        ) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(5.dp))
                            Text("导出 TXT")
                        }
                        OutlinedButton(onClick = model::refreshDiagnostics,
                            shape = RoundedCornerShape(12.dp)) { Text("刷新") }
                        OutlinedButton(onClick = { confirmClear = true },
                            shape = RoundedCornerShape(12.dp)) { Text("清空") }
                    }
                    state.logNotice?.let { Text(it, color = HongguoColors.accent,
                        fontSize = 12.sp) }
                    TextButton(onClick = {
                        showLogPreview = !showLogPreview
                        if (showLogPreview) model.refreshDiagnostics()
                    }) {
                        Text(if (showLogPreview) "收起日志预览" else "展开日志预览")
                        Icon(if (showLogPreview) Icons.Outlined.ExpandLess
                             else Icons.Outlined.ExpandMore, null)
                    }
                    if (showLogPreview) {
                        Card(Modifier.fillMaxWidth().height(270.dp)) {
                            Text(
                                state.logText.ifBlank { "暂无诊断记录" }.takeLast(14000),
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp).testTag("diagnostic_preview"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup("关于") {
                    Text("红果短剧 · Native Rewrite",
                        color = HongguoColors.ink,
                        fontWeight = FontWeight.SemiBold)
                    Text("版本 ${BuildConfig.VERSION_NAME} · Jetpack Compose + Media3",
                        color = HongguoColors.muted, fontSize = 12.sp)
                    Text("部分视频和评论接口可能因网络或上游限制不可用；可在诊断中查看具体阶段。",
                        color = HongguoColors.muted, fontSize = 11.sp)
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空诊断日志？") },
        text = { Text("不会影响收藏、播放历史和设置。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                model.clearDiagnostics()
            }) { Text("清空") }
        },
        dismissButton = {
            TextButton(onClick = { confirmClear = false }) { Text("取消") }
        },
    )
}

@Composable
private fun SettingsNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(color = HongguoColors.accentSoft, shape = RoundedCornerShape(10.dp)) {
            androidx.compose.foundation.layout.Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = HongguoColors.accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = HongguoColors.ink)
            Text(subtitle, fontSize = 12.sp, lineHeight = 19.sp, color = HongguoColors.muted)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null,
            modifier = Modifier.size(20.dp), tint = HongguoColors.muted)
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = HongguoColors.muted, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = HongguoColors.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    explanation: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = HongguoColors.ink)
            Text(explanation, fontSize = 11.sp, color = HongguoColors.muted)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}
