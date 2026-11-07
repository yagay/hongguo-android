package com.hongguo.rewrite

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Opt-in account screen. All cloud reads are isolated from local shelves. */
@Composable
internal fun AccountScreen(model: DramaViewModel) {
    val account by model.account.state.collectAsStateWithLifecycle()
    // Authentication inputs must not be written to saved instance state.
    var phone by remember { mutableStateOf("+86 ") }
    var code by remember { mutableStateOf("") }
    val otpFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(account.smsSent) {
        if (account.smsSent) {
            otpFocus.requestFocus()
            keyboard?.show()
        } else code = ""
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    var loginMethod by rememberSaveable { mutableIntStateOf(0) }
    var accountMenu by remember { mutableStateOf(false) }
    BackHandler { model.closeAccount() }
    Scaffold(
        containerColor = HongguoColors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                Modifier.fillMaxWidth().height(58.dp)
                    .background(HongguoColors.surface)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = model::closeAccount) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                }
                Text("我的账号", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (account.profile != null)
                    TextButton(onClick = { model.account.refresh() },
                        enabled = !account.busy) { Text("刷新") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("account_screen"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(color = HongguoColors.surface, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            account.profile?.nickname?.let { "已登录 · " + it } ?: "未登录",
                            color = HongguoColors.ink, fontWeight = FontWeight.SemiBold,
                        )
                        Text(account.message, fontSize = 12.sp,
                            color = HongguoColors.muted,
                            modifier = Modifier.testTag("account_message"))
                        if (account.busy || account.checking)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (account.profile == null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(54.dp),
                            tint = HongguoColors.accent)
                        Text("登录红果账号", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = HongguoColors.ink)
                        Text("查看账号收藏、历史与预约，本机记录单独保留",
                            color = HongguoColors.muted, fontSize = 13.sp)
                    }
                }
                item {
                    Surface(color = HongguoColors.surface, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("手机验证码", "扫码登录").forEachIndexed { index, label ->
                                FilterChip(selected = loginMethod == index, onClick = {
                                    if (loginMethod != index) {
                                        if (index == 0) model.account.cancelQr()
                                        else model.account.cancelSms()
                                        loginMethod = index
                                    }
                                }, label = { Text(label) },
                                    modifier = Modifier.weight(1f).testTag(
                                        if (index == 0) "account_method_sms" else "account_method_qr"))
                            }
                        }
                    }
                }
                if (loginMethod == 1) item {
                    Text("官方扫码登录", fontWeight = FontWeight.Bold,
                        color = HongguoColors.ink)
                    Text("请用手机红果 App 的扫一扫扫描，并在手机上确认。扫码失败可尝试手机号验证码。",
                        fontSize = 12.sp, color = HongguoColors.muted)
                    if (account.qrImage == null) {
                        Button(
                            onClick = model.account::qrLogin,
                            enabled = !account.busy && !account.checking,
                            modifier = Modifier.fillMaxWidth().testTag("account_qr_login"),
                        ) { Text("获取登录二维码") }
                    } else {
                        val bitmap = remember(account.qrImage) {
                            account.qrImage?.let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)
                            }
                        }
                        if (bitmap != null) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "红果账号扫码二维码",
                                    modifier = Modifier.size(230.dp)
                                        .testTag("account_qr_image"),
                                )
                            }
                        }
                        Text("请在手机上确认；二维码有效期由服务端决定。",
                            fontSize = 12.sp, color = HongguoColors.muted)
                        OutlinedButton(
                            onClick = { model.account.cancelQr() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("取消扫码") }
                    }
                }
                if (loginMethod == 0) item {
                    Text("手机号验证码登录", fontWeight = FontWeight.Bold,
                        color = HongguoColors.ink)
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it.take(30) },
                        label = { Text("国际区号 + 手机号") },
                        placeholder = { Text("+86 13800138000") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                            .testTag("account_phone_input"),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Phone
                        ),
                    )
                    OutlinedButton(
                        onClick = { model.account.sendCode(phone) },
                        enabled = !account.busy && !account.checking && !account.smsSent && account.smsRetrySeconds == 0,
                        modifier = Modifier.fillMaxWidth().testTag("account_send_sms"),
                    ) { Text(if (account.smsRetrySeconds > 0) "${account.smsRetrySeconds} 秒后可重新获取" else "获取验证码") }
                    if (account.smsSent) {
                        Text("验证码已发送至 " + account.maskedPhone,
                            fontSize = 12.sp, color = HongguoColors.muted)
                        OutlinedTextField(
                            value = code, onValueChange = {
                                code = it.filter(Char::isDigit).take(8)
                            },
                            label = { Text("短信验证码") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                                .focusRequester(otpFocus).testTag("account_sms_code"),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                        )
                        Button(
                            onClick = { model.account.verifyCode(code) },
                            enabled = !account.busy && code.length in 4..8,
                            modifier = Modifier.fillMaxWidth().testTag("account_verify_sms"),
                        ) { Text(if (account.busy) "正在验证…" else "验证并登录") }
                        TextButton(onClick = { model.account.sendCode(phone) },
                            enabled = !account.busy && account.smsRetrySeconds == 0,
                            modifier = Modifier.testTag("account_resend_sms")) {
                            Text(if (account.smsRetrySeconds > 0)
                                "${account.smsRetrySeconds} 秒后可重新获取" else "重新获取验证码")
                        }
                        TextButton(onClick = {
                            code = ""
                            model.account.cancelSms()
                        }, enabled = !account.busy, modifier = Modifier.testTag("account_sms_change_phone")) {
                            Text("更换手机号")
                        }
                    }
                }
                item {
                    Text("登录为可选功能；不登录也能浏览和播放。账号数据不写入诊断日志。若平台要求滑块验证，请到官方 App 操作，当前客户端不会绕过验证。",
                        fontSize = 11.sp, color = HongguoColors.muted)
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(HongguoColors.accentSoft),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(30.dp),
                                tint = HongguoColors.accent)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(account.profile?.nickname.orEmpty().ifBlank { "红果用户" },
                                color = HongguoColors.ink, fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("account_profile"))
                            Text("红果号 ${account.profile?.id.orEmpty()}",
                                color = HongguoColors.muted, fontSize = 12.sp)
                        }
                        Box {
                            IconButton(onClick = { accountMenu = true },
                                modifier = Modifier.testTag("account_menu")) {
                                Icon(Icons.Outlined.MoreVert, "账号操作", tint = HongguoColors.ink)
                            }
                            DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                                DropdownMenuItem(text = { Text("同步此后观看进度") },
                                    trailingIcon = { Checkbox(checked = account.syncEnabled, onCheckedChange = null) },
                                    onClick = {
                                        accountMenu = false
                                        model.account.setProgressSync(!account.syncEnabled)
                                    }, modifier = Modifier.testTag("account_sync_switch"))
                                DropdownMenuItem(text = { Text("刷新账号资料") }, onClick = {
                                    accountMenu = false
                                    model.account.refresh()
                                })
                                DropdownMenuItem(text = { Text("退出账号") }, onClick = {
                                    accountMenu = false
                                    confirmLogout = true
                                }, modifier = Modifier.testTag("account_logout_menu"))
                            }
                        }
                    }
                }
                item {
                    Surface(color = HongguoColors.surface,
                        shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用账号推荐", fontWeight = FontWeight.SemiBold,
                                    color = HongguoColors.ink)
                                Text("默认关闭。启用后将账号会话用于官方推荐接口；不保证与官方 App 的个性化结果完全一致。",
                                    fontSize = 11.sp, color = HongguoColors.muted)
                            }
                            Switch(
                                checked = account.accountRecommendations,
                                onCheckedChange = model.account::setAccountRecommendations,
                                modifier = Modifier.testTag("account_recommendations"),
                            )
                        }
                    }
                }
                item {
                    Surface(color = HongguoColors.surface,
                        shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("自动同步播放进度", fontWeight = FontWeight.SemiBold,
                                    color = HongguoColors.ink)
                                Text("默认关闭。开启后观看时约每 30 秒上传一次，不覆盖本机记录。",
                                    fontSize = 11.sp, color = HongguoColors.muted)
                            }
                            Switch(
                                checked = account.syncEnabled,
                                onCheckedChange = model.account::setProgressSync,
                                modifier = Modifier.testTag("cloud_progress_sync"),
                            )
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("云端历史", "云端收藏", "我的预约").forEachIndexed { index, title ->
                            FilterChip(
                                selected = tab == index,
                                onClick = {
                                    tab = index
                                    if (index == 2 && !account.reservationsLoaded)
                                        model.account.loadReservations()
                                },
                                label = { Text(title, fontSize = 12.sp) },
                            )
                        }
                    }
                    Text(
                        when (tab) {
                            0 -> "云端历史仅展示最近最多 50 条，不覆盖本机历史"
                            1 -> "云端收藏与本机收藏分开管理"
                            else -> "预约分为已上线和待上线，待上线项目无法播放"
                        },
                        fontSize = 11.sp, color = HongguoColors.muted,
                    )
                }
                if (tab == 0) {
                    if (account.history.isEmpty()) item {
                        Text(if (account.historyLoaded) "云端暂无观看历史" else "未能读取云端历史",
                            color = HongguoColors.muted)
                    }
                    items(account.history, key = { it.series.id }) { row ->
                        AccountRow(
                            title = row.series.title,
                            subtitle = "第 " + (row.episodeIndex + 1) +
                                " 集 · " + PlaybackTimeFormatter.format(row.position),
                            actionLabel = "继续观看",
                            onAction = { model.openCloudProgress(row) },
                        )
                    }
                }
                if (tab == 1) {
                    if (account.favorites.isEmpty()) item {
                        Text(if (account.favoritesLoaded) "云端暂无收藏" else "未能读取云收藏",
                            color = HongguoColors.muted)
                    }
                    items(account.favorites, key = { it.series.id }) { row ->
                        AccountRow(
                            title = row.series.title, subtitle = row.series.badge,
                            actionLabel = "播放",
                            onAction = { model.openCloudDrama(row.series) },
                            extraLabel = "移除",
                            onExtra = { model.account.cloudFavorite(row.series.id, false) },
                        )
                    }
                }
                if (tab == 2) {
                    if (account.upcoming.isEmpty() && account.online.isEmpty()) item {
                        Text(if (account.reservationsLoaded) "暂无预约记录" else "预约暂未读取",
                            color = HongguoColors.muted)
                    }
                    items(account.upcoming, key = { it.series.id }) { row ->
                        AccountRow(row.series.title, row.publishText.ifBlank { "待上线" },
                            "待上线", null)
                    }
                    items(account.online, key = { it.series.id }) { row ->
                        AccountRow(
                            row.series.title, "已上线", "观看",
                            { model.openCloudDrama(row.series) },
                        )
                    }
                }
                item {
                    HorizontalDivider()
                    OutlinedButton(
                        onClick = { confirmLogout = true },
                        modifier = Modifier.fillMaxWidth().testTag("account_logout"),
                    ) { Text("退出红果账号", color = HongguoColors.accent) }
                }
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("退出红果账号？") },
        text = { Text("清除本机保存的登录凭据；不会删除本机观看历史和收藏。") },
        confirmButton = {
            TextButton(onClick = {
                confirmLogout = false
                model.account.logout()
            }) { Text("退出") }
        },
        dismissButton = {
            TextButton(onClick = { confirmLogout = false }) { Text("取消") }
        },
    )
}

@Composable
private fun AccountRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    Surface(color = HongguoColors.surface, shape = RoundedCornerShape(10.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title.ifBlank { "未命名短剧" }, fontWeight = FontWeight.SemiBold,
                color = HongguoColors.ink)
            if (subtitle.isNotBlank())
                Text(subtitle, fontSize = 11.sp, color = HongguoColors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onAction != null)
                    OutlinedButton(onClick = onAction) { Text(actionLabel) }
                if (extraLabel != null && onExtra != null)
                    TextButton(onClick = onExtra) { Text(extraLabel) }
            }
        }
    }
}
