package com.hongguo.rewrite

import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Opt-in account screen. All cloud reads are isolated from local shelves. */
@Composable
internal fun AccountScreen(model: DramaViewModel) {
    val account by model.account.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webOpen by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf<String?>(null) }
    val webLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        webOpen = false
        if (result.resultCode == Activity.RESULT_OK) {
            val cookie = OfficialLoginActivity.consumeCookie(result.data)
            if (!cookie.isNullOrBlank()) model.account.importLoginCookie(cookie)
            else webError = "官网登录确认已过期，请重新打开页面"
        }
    }
    val openWebsite: () -> Unit = {
        if (!webOpen) {
            webError = null
            webOpen = true
            try { webLauncher.launch(Intent(context, OfficialLoginActivity::class.java)) }
            catch (_: RuntimeException) {
                webOpen = false
                webError = "无法打开官网登录，请稍后重试"
            }
        }
    }
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
    // Like main: entering the QR method prepares one QR automatically, never auto-retries an expired one.
    LaunchedEffect(loginMethod) {
        if (loginMethod == 2 && account.profile == null && !account.busy &&
            account.qrImage == null) model.account.qrLogin()
    }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var importedCookie by remember { mutableStateOf("") }
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
            webError?.let { error -> item { Text(error, color = HongguoColors.accent,
                fontSize = 13.sp, modifier = Modifier.testTag("account_web_error")) } }
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
            if (account.profile == null && account.savedSessionPresent) item {
                Surface(color = HongguoColors.surface, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("已保存的账号等待验证", fontWeight = FontWeight.SemiBold,
                            color = HongguoColors.ink)
                        Text("网络恢复后可重新验证；也可以清除已保存会话再登录。本机收藏和历史不受影响。",
                            color = HongguoColors.muted, fontSize = 12.sp)
                        OutlinedButton(onClick = model.account::refresh, enabled = !account.busy,
                            modifier = Modifier.fillMaxWidth().testTag("account_verify_saved")) {
                            Text("重新验证已保存账号")
                        }
                        TextButton(onClick = { confirmLogout = true }, enabled = !account.busy,
                            modifier = Modifier.testTag("account_clear_saved")) {
                            Text("清除已保存的登录态")
                        }
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
                            listOf("手机验证码", "官网登录", "扫码登录").forEachIndexed { index, label ->
                                FilterChip(selected = loginMethod == index, onClick = {
                                    if (loginMethod != index) {
                                        model.account.cancelActiveLogin()
                                        loginMethod = index
                                    }
                                }, label = { Text(label) },
                                    modifier = Modifier.weight(1f).testTag(
                                        when (index) {
                                            0 -> "account_method_sms"
                                            1 -> "account_method_web"
                                            else -> "account_method_qr"
                                        }))
                            }
                        }
                    }
                }
                if (loginMethod == 2) item {
                    Surface(shape = RoundedCornerShape(20.dp), color = HongguoColors.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Text(if (account.qrRemainingSeconds > 0)
                                "请在手机红果 App 确认 · ${account.qrRemainingSeconds} 秒"
                            else "二维码已过期，请刷新",
                            fontSize = 12.sp, color = HongguoColors.muted,
                            modifier = Modifier.testTag("account_qr_expiry"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model.account::qrLogin,
                                enabled = !account.busy, modifier = Modifier.testTag("account_qr_refresh")) {
                                Text("刷新二维码")
                            }
                            TextButton(onClick = model.account::cancelQr,
                                modifier = Modifier.testTag("account_login_cancel")) { Text("取消登录") }
                        }
                    }
                        }
                    }
                }
                if (loginMethod == 1) item {
                    Surface(shape = RoundedCornerShape(20.dp), color = HongguoColors.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("官网登录", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                color = HongguoColors.ink)
                            Text("在官方红果页面自行完成手机号、扫码和平台滑块验证，再点击官网窗口内「完成登录」。客户端只有核实云账号身份后才会显示登录成功。",
                                color = HongguoColors.muted, fontSize = 13.sp, lineHeight = 21.sp)
                            Button(onClick = openWebsite, enabled = !webOpen && !account.busy,
                                modifier = Modifier.fillMaxWidth().testTag("account_web_login")) {
                                Text(if (webOpen) "正在打开官网…" else "打开官网登录")
                            }
                        }
                    }
                }
                if (loginMethod == 0) item {
                    Surface(shape = RoundedCornerShape(20.dp), color = HongguoColors.surface) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    }
                }
                item {
                    Surface(color = HongguoColors.surface, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = {
                                advancedOpen = !advancedOpen
                                importedCookie = ""
                            }, modifier = Modifier.testTag("account_cookie_expand")) {
                                Text(if (advancedOpen) "收起高级登录" else "高级：导入已有登录态")
                            }
                            if (advancedOpen) {
                                Text("仅导入你本人的账号 Cookie。必须通过真实云端身份验证后才会登录；不记录或导出凭据。",
                                    color = HongguoColors.muted, fontSize = 12.sp)
                                OutlinedTextField(value = importedCookie,
                                    onValueChange = { importedCookie = it.take(8192) },
                                    label = { Text("已有红果登录 Cookie") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    enabled = !account.busy,
                                    modifier = Modifier.fillMaxWidth().testTag("account_cookie_input"))
                                OutlinedButton(onClick = {
                                    val candidate = importedCookie
                                    importedCookie = ""
                                    model.account.importLoginCookie(candidate)
                                }, enabled = !account.busy && importedCookie.isNotBlank(),
                                    modifier = Modifier.testTag("account_cookie_import")) {
                                    Text("验证并导入")
                                }
                            }
                        }
                    }
                }
                if (account.message.contains("滑块") || account.message.contains("人工验证")) item {
                    Surface(color = HongguoColors.accentSoft, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("需要平台验证，请到官网登录完成", color = HongguoColors.ink,
                                modifier = Modifier.weight(1f), fontSize = 13.sp)
                            TextButton(onClick = openWebsite, modifier = Modifier.testTag("account_sms_to_web")) {
                                Text("去验证")
                            }
                        }
                    }
                }
                item {
                    Text("登录为可选功能；不登录也能浏览和播放。遇到滑块时请亲自在官网完成，不会自动绕过验证。账号凭据不写入日志。",
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
