package com.hongguo.rewrite

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Actual, navigable settings screen; diagnostics are outside the video player. */
@Composable
internal fun SettingsScreen(state: AppState, model: DramaViewModel) {
    var address by remember(state.backend) { mutableStateOf(state.backend) }
    var confirmClear by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> if (uri != null) model.exportDiagnostics(uri) }
    LaunchedEffect(Unit) { model.refreshDiagnostics() }
    BackHandler { model.closeModal() }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = model::closeModal) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Icon(Icons.Outlined.Settings, contentDescription = null)
                Text(" 设置", style = MaterialTheme.typography.titleLarge)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings_page"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("内容来源", style = MaterialTheme.typography.titleMedium)
                Text("默认读取官网公开的目录信息。完整视频播放和部分分类目前需要另行连接 hongguo-web 服务。",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("网页版 API 地址（可选）") },
                    placeholder = { Text("https://your-server.example") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backend_address")
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.saveBackend(address) },
                        modifier = Modifier.testTag("save_backend")) {
                        Text("保存连接")
                    }
                    OutlinedButton(onClick = {
                        address = ""
                        model.saveBackend("")
                    }) { Text("使用官网目录") }
                }
                state.settingsMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text("局域网 HTTP 仅供调试；公网 API 请使用 HTTPS。手机无法直接连接电脑的 127.0.0.1。",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { HorizontalDivider() }
            item {
                Text("诊断日志", style = MaterialTheme.typography.titleMedium)
                Text("记录目录、剧集、播放和下载的操作阶段、错误代码、响应状态及耗时。不采集账号、搜索关键词、剧名、视频地址或 API 密钥。",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("记录诊断事件")
                        Text(if (state.logEnabled) "已启用" else "已停用",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = state.logEnabled, onCheckedChange = model::setLoggingEnabled,
                        modifier = Modifier.testTag("log_switch"))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val stamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            exporter.launch("Hongguo-diagnostic-$stamp.txt")
                        },
                        modifier = Modifier.testTag("export_diagnostic")
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Text(" 导出 TXT")
                    }
                    OutlinedButton(onClick = model::refreshDiagnostics) {
                        Text("刷新日志")
                    }
                    OutlinedButton(onClick = { confirmClear = true }) {
                        Text("清空")
                    }
                }
                state.logNotice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Text("最近日志预览", style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth().height(270.dp)) {
                    Text(
                        state.logText.ifBlank { "暂无日志，尝试加载剧集后刷新。" }.takeLast(14000),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
                            .testTag("diagnostic_preview"),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            item {
                HorizontalDivider()
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text("Hongguo Native Rewrite · Debug 开发版",
                    style = MaterialTheme.typography.bodySmall)
                Text("日志仅存储在本机应用私有目录，最多 256 KB。只有你主动选择导出位置时才会保存 TXT 文件。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空诊断日志？") },
        text = { Text("只删除本应用保存的诊断事件，不会影响历史、收藏或设置。") },
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
