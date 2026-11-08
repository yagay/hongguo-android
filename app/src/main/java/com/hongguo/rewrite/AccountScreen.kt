package com.hongguo.rewrite

import android.graphics.BitmapFactory
import com.hongguotv.core.Series
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.FavoriteBorder
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
    val libraryState by model.state.collectAsStateWithLifecycle()
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
    LaunchedEffect(tab, account.profile?.id) {
        if (tab == 2 && account.profile != null) model.account.loadLikes()
    }
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
            HongguoPageHeader(
                title = "我的账号",
                onBack = model::closeAccount,
                modifier = Modifier.background(HongguoColors.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("account_screen"),
            contentPadding = PaddingValues(
                horizontal = HongguoUi.pagePadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            webError?.let { error -> item { Text(error, color = HongguoColors.accent,
                fontSize = 13.sp, modifier = Modifier.testTag("account_web_error")) } }
            if (account.profile == null || account.busy || account.checking ||
                account.message.contains("失败")) item {
                Surface(color = HongguoColors.surface, shape = RoundedCornerShape(HongguoUi.controlRadius)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(account.message, fontSize = 12.sp,
                            color = HongguoColors.muted,
                            modifier = Modifier.testTag("account_message"))
                        if (account.busy || account.checking)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (account.profile == null && account.savedSessionPresent) item {
                HongguoCard() {
                    Column(Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("已保存的账号等待验证", fontWeight = FontWeight.SemiBold,
                            color = HongguoColors.ink)
                        Text("网络恢复后可重新验证；也可以清除已保存会话再登录。本机收藏和历史不受影响。",
                            color = HongguoColors.muted, fontSize = 12.sp)
                        HongguoAction(
                            label = "重新验证已保存账号",
                            onClick = model.account::refresh,
                            enabled = !account.busy,
                            modifier = Modifier.fillMaxWidth().testTag("account_verify_saved"),
                        )
                        HongguoAction(
                            label = "清除已保存的登录态",
                            onClick = { confirmLogout = true },
                            enabled = !account.busy,
                            modifier = Modifier.testTag("account_clear_saved"),
                            style = HongguoActionStyle.QUIET,
                        )
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
                    HongguoCard() {
                        Row(Modifier.fillMaxWidth().padding(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("手机验证码", "官网登录", "扫码登录").forEachIndexed { index, label ->
                                HongguoChoiceChip(
                                    label = label,
                                    selected = loginMethod == index,
                                    onClick = {
                                        if (loginMethod != index) {
                                            model.account.cancelActiveLogin()
                                            loginMethod = index
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag(
                                        when (index) {
                                            0 -> "account_method_sms"
                                            1 -> "account_method_web"
                                            else -> "account_method_qr"
                                        }),
                                )
                            }
                        }
                    }
                }
                if (loginMethod == 2) item {
                    HongguoCard() {
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
                            HongguoAction(
                                label = "刷新二维码", onClick = model.account::qrLogin,
                                enabled = !account.busy,
                                modifier = Modifier.testTag("account_qr_refresh"),
                            )
                            HongguoAction(
                                label = "取消登录", onClick = model.account::cancelQr,
                                modifier = Modifier.testTag("account_login_cancel"),
                                style = HongguoActionStyle.QUIET,
                            )
                        }
                    }
                        }
                    }
                }
                if (loginMethod == 1) item {
                    HongguoCard() {
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
                    HongguoCard() {
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
                    HongguoCard() {
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
                    Surface(color = HongguoColors.accentSoft, shape = RoundedCornerShape(HongguoUi.controlRadius)) {
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
                    Row(
                        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        // The current cloud profile has no avatar URL. Show a stable fallback.
                        Box(Modifier.size(58.dp).clip(CircleShape)
                            .background(HongguoColors.accentSoft),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(33.dp),
                                tint = HongguoColors.accent)
                            account.profile?.avatarUrl?.let { avatar ->
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = "红果账号头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                        .testTag("account_avatar"),
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(account.profile?.nickname.orEmpty().ifBlank { "红果用户" },
                                color = HongguoColors.ink, fontSize = 20.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.testTag("account_profile"))
                            Text("红果号 ${account.profile?.id.orEmpty()}",
                                color = HongguoColors.muted, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { accountMenu = true },
                                modifier = Modifier.testTag("account_menu")) {
                                Icon(Icons.Outlined.MoreVert, "账号操作",
                                    tint = HongguoColors.ink)
                            }
                            DropdownMenu(expanded = accountMenu,
                                onDismissRequest = { accountMenu = false }) {
                                DropdownMenuItem(text = { Text("刷短剧使用登录账号") },
                                    trailingIcon = { Checkbox(
                                        checked = account.accountRecommendations, onCheckedChange = null) },
                                    onClick = {
                                        accountMenu = false
                                        model.setAccountRecommendations(!account.accountRecommendations)
                                    })
                                DropdownMenuItem(text = { Text("自动同步观看进度") },
                                    trailingIcon = { Checkbox(
                                        checked = account.syncEnabled, onCheckedChange = null) },
                                    onClick = {
                                        accountMenu = false
                                        model.account.setProgressSync(!account.syncEnabled)
                                    }, modifier = Modifier.testTag("account_sync_switch"))
                                DropdownMenuItem(text = { Text("刷新账号资料") },
                                    onClick = { accountMenu = false; model.account.refresh() })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("退出账号") },
                                    onClick = { accountMenu = false; confirmLogout = true },
                                    modifier = Modifier.testTag("account_logout_menu"))
                            }
                        }
                    }
                }
                // Future verified account integration can provide real social
                // counts. No placeholder dashes are shown before data exists.
                FeatureAvailability.socialCounts()?.let { counts ->
                    item {
                        Surface(shape = RoundedCornerShape(HongguoUi.cardRadius),
                            color = HongguoColors.surface,
                            modifier = Modifier.fillMaxWidth().testTag("account_stats")) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 15.dp)) {
                                listOf(
                                    "关注" to counts.following,
                                    "粉丝" to counts.followers,
                                    "获赞" to counts.likes,
                                ).forEach { (label, count) ->
                                    Column(Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(count.toString(), color = HongguoColors.ink,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium)
                                        Text(label, color = HongguoColors.muted,
                                            fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("账号片单", color = HongguoColors.ink,
                            fontSize = 19.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (tab == 2) model.account.loadLikes(force = true)
                            else {
                                model.account.refresh()
                                if (tab == 3) model.account.loadReservations()
                            }
                        }, enabled = !account.busy && !account.likesLoading,
                            modifier = Modifier.testTag("account_library_refresh")) {
                            Text("刷新", color = HongguoColors.accent, fontSize = 14.sp)
                        }
                    }
                }
                item {
                    HongguoTabs(
                        labels = listOf("历史", "收藏", "点赞", "预约"),
                        selectedIndex = tab,
                        tagPrefix = "account_library_tab",
                        modifier = Modifier.testTag("account_library_tabs"),
                        onSelect = { index ->
                            tab = index
                            if (index == 3 && !account.reservationsLoaded)
                                model.account.loadReservations()
                        },
                    )
                }

                val gallery: List<AccountGalleryEntry> = when (tab) {
                    0 -> account.history.map { progress ->
                        val saved = account.favorites.any {
                            it.series.id == progress.series.id
                        }
                        AccountGalleryEntry(
                            progress.series,
                            "第 ${progress.episodeIndex + 1} 集 · " +
                                PlaybackTimeFormatter.format(progress.position),
                            onOpen = { model.openCloudProgress(progress) },
                            saved = if (account.favoritesLoaded) saved else null,
                            onSave = if (account.favoritesLoaded) ({
                                model.account.cloudFavorite(progress.series.id, !saved)
                            }) else null,
                        )
                    }
                    1 -> account.favorites.map { favorite ->
                        AccountGalleryEntry(favorite.series, favorite.series.badge,
                            onOpen = { model.openCloudDrama(favorite.series) },
                            saved = true,
                            onSave = {
                                model.account.cloudFavorite(favorite.series.id, false)
                            })
                    }
                    2 -> account.likes.map { item ->
                        AccountGalleryEntry(item.series, item.series.badge.ifBlank { "账号点赞" },
                            onOpen = { model.openCloudDrama(item.series) },
                            saved = null, onSave = null)
                    }
                    else -> (account.upcoming.map { it to false } +
                        account.online.map { it to true })
                        .map { (reservation, online) ->
                            AccountGalleryEntry(reservation.series,
                                if (online) "已上线" else
                                    reservation.publishText.ifBlank { "待上线" },
                                onOpen = if (online) ({
                                    model.openCloudDrama(reservation.series)
                                }) else null, saved = null, onSave = null)
                        }
                }
                if (gallery.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().height(190.dp),
                        contentAlignment = Alignment.Center) {
                        Text(when (tab) {
                            0 -> if (account.historyLoaded) "还没有观看历史"
                                else "历史暂未加载成功，点击刷新重试"
                            1 -> if (account.favoritesLoaded) "还没有收藏的短剧"
                                else "收藏暂未加载成功，点击刷新重试"
                            2 -> if (account.likesLoading) "正在读取账号点赞…"
                                else if (account.likesError != null) account.likesError.orEmpty()
                                else if (account.likesLoaded) "账号暂无点赞作品"
                                else "点赞记录尚未读取，请点击刷新"
                            else -> if (account.reservationsLoaded) "暂无预约短剧"
                                else "预约尚未加载，点击刷新重试"
                        }, color = HongguoColors.muted, fontSize = 13.sp)
                    }
                }
                items(gallery.chunked(3)) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.Top) {
                        line.forEach { item ->
                            AccountPoster(
                                series = item.series, subtitle = item.subtitle,
                                onOpen = item.onOpen, saved = item.saved,
                                onSave = item.onSave,
                                liked = item.series.id in libraryState.likedDramaIds,
                                onLike = if (tab == 2) null else ({
                                    model.toggleLocalLikeForDrama(item.series.id)
                                }), modifier = Modifier.weight(1f))
                        }
                        repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
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

private data class AccountGalleryEntry(
    val series: Series,
    val subtitle: String,
    val onOpen: (() -> Unit)?,
    val saved: Boolean?,
    val onSave: (() -> Unit)?,
)

@Composable
private fun AccountRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
) {
    HongguoCard() {
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
                    HongguoAction(label = actionLabel, onClick = onAction)
                if (extraLabel != null && onExtra != null)
                    HongguoAction(
                        label = extraLabel, onClick = onExtra,
                        style = HongguoActionStyle.QUIET,
                    )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountPoster(
    series: Series,
    subtitle: String,
    onOpen: (() -> Unit)?,
    saved: Boolean?,
    onSave: (() -> Unit)?,
    liked: Boolean,
    onLike: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(HongguoColors.accentSoft)
                    .combinedClickable(
                        enabled = onOpen != null || onSave != null,
                        onClick = { onOpen?.invoke() },
                        onLongClick = { menuOpen = true }
                    ).testTag("account_poster_${series.id}"),
            ) {
                if (series.cover.isNotBlank()) {
                    AsyncImage(
                        model = series.cover,
                        contentDescription = series.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else Icon(Icons.Outlined.PersonOutline, contentDescription = null,
                    tint = HongguoColors.muted,
                    modifier = Modifier.align(Alignment.Center).size(28.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onOpen != null) DropdownMenuItem(
                    text = { Text("继续观看") },
                    onClick = { menuOpen = false; onOpen() })
                if (saved != null && onSave != null) DropdownMenuItem(
                    text = { Text(if (saved) "取消云端收藏" else "加入云端收藏") },
                    onClick = { menuOpen = false; onSave() })
                if (onLike != null) DropdownMenuItem(
                    text = { Text(if (liked) "取消本机点赞" else "本机点赞") },
                    onClick = { menuOpen = false; onLike() })
            }
        }
        Text(series.title.ifBlank { "未命名短剧" },
            maxLines = 2, minLines = 1, overflow = TextOverflow.Ellipsis,
            color = HongguoColors.ink, fontSize = 13.sp,
            lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().clickable { onOpen?.invoke() })
        if (subtitle.isNotBlank()) Text(
            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = HongguoColors.muted, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth())
    }
}
