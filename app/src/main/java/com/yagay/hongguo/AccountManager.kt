package com.yagay.hongguo

import android.app.Application
import android.os.SystemClock
import com.hongguotv.core.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

/** Account data is separate from local favorites/history and diagnostics. */
data class AccountUiState(
    val checking: Boolean = true,
    val savedSessionPresent: Boolean = false,
    val busy: Boolean = false,
    val profile: CloudProfile? = null,
    val syncEnabled: Boolean = false,
    val accountRecommendations: Boolean = false,
    val qrImage: ByteArray? = null,
    val qrExpires: Int = 0,
    val qrRemainingSeconds: Int = 0,
    val waitingQr: Boolean = false,
    val smsSent: Boolean = false,
    val smsRetrySeconds: Int = 0,
    val maskedPhone: String = "",
    val message: String = "读取加密登录状态…",
    val history: List<CloudProgress> = emptyList(),
    val favorites: List<CloudFavorite> = emptyList(),
    val likes: List<CloudLike> = emptyList(),
    val likesLoaded: Boolean = false,
    val likesLoading: Boolean = false,
    val likesError: String? = null,
    val upcoming: List<CloudReservation> = emptyList(),
    val online: List<CloudReservation> = emptyList(),
    val historyLoaded: Boolean = false,
    val favoritesLoaded: Boolean = false,
    val reservationsLoaded: Boolean = false,
    val reservationsLoading: Boolean = false,
)

internal class AccountManager(app: Application, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val cloud = CloudAccountClient(http)
    private val store = SecureAccountStore(app)
    private val prefs = app.getSharedPreferences("web_rewrite_settings", 0)
    private val mutable = MutableStateFlow(AccountUiState(
        syncEnabled = prefs.getBoolean("cloud_progress_opt_in", false),
        accountRecommendations = prefs.getBoolean("account_recommend_opt_in", false),
    ))
    val state = mutable.asStateFlow()
    private var credentials: AccountCredentials? = null
    private var webLogin: WebLogin? = null
    private var phoneLogin: PhoneLogin? = null
    private var authJob: Job? = null
    private var dataJob: Job? = null
    private var reservationsJob: Job? = null
    private var likesJob: Job? = null
    private var uploadJob: Job? = null
    private var queuedUpload: Pair<CloudProgress, Int>? = null
    private var lastUploadTimeMs = 0L
    private var epoch = 0L

    private fun smsRemainingSeconds(): Int =
        ((prefs.getLong("account_sms_retry_at", 0L) - System.currentTimeMillis() + 999) / 1000)
            .coerceIn(0, 3600).toInt()

    init {
        scope.launch {
            while (isActive) {
                val seconds = smsRemainingSeconds()
                if (mutable.value.smsRetrySeconds != seconds)
                    mutable.update { it.copy(smsRetrySeconds = seconds) }
                delay(if (seconds > 0) 1_000L else 5_000L)
            }
        }
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { store.read() } }
            if (saved.isFailure) {
                withContext(Dispatchers.IO) { runCatching { store.clear() } }
                mutable.update { it.copy(checking = false, message = "会话解密失败，请重新登录") }
            } else {
                credentials = saved.getOrNull()
                mutable.update { it.copy(checking = false, savedSessionPresent = credentials != null,
                    message = if (credentials == null) "尚未登录" else "正在校验账号…") }
                if (credentials != null) refresh()
            }
        }
    }

    private fun explain(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "账号连接超时"
        is java.net.UnknownHostException -> "账号域名无法连接"
        else -> t.message.orEmpty()
            .replace(Regex("https?://\\S+"), "[地址]")
            .replace(Regex("(?i)(cookie|sessionid|token|mobile_ticket)\\s*[=:]\\s*\\S+"),
                "[敏感信息]").take(110).ifBlank { "账号接口暂不可用" }
    }

    private fun resetLogin() {
        ++epoch
        authJob?.cancel()
        webLogin?.cancel()
        webLogin = null
        phoneLogin = null
        mutable.update { it.copy(qrImage = null, qrRemainingSeconds = 0, waitingQr = false, busy = false) }
    }

    fun cancelActiveLogin() {
        resetLogin()
        mutable.update { it.copy(smsSent = false, maskedPhone = "", message = "请选择登录方式") }
    }

    fun cancelQr() {
        if (state.value.waitingQr || state.value.qrImage != null || state.value.busy) resetLogin()
    }

    fun cancelSms() {
        resetLogin()
        mutable.update { it.copy(smsSent = false, maskedPhone = "",
            message = "可更换手机号并重新获取验证码") }
    }

    fun qrLogin() {
        if (state.value.busy) return
        resetLogin()
        val token = epoch
        val session = WebLogin(http)
        webLogin = session
        mutable.update { it.copy(busy = true, smsSent = false,
            message = "正在获取红果官方二维码…") }
        authJob = scope.launch {
            try {
                val qr = withContext(Dispatchers.IO) { session.qr() }
                if (qr.image.isEmpty() || qr.image.size > 256_000)
                    throw IOException("二维码格式不正确")
                if (token != epoch) return@launch
                mutable.update { it.copy(
                    busy = false, waitingQr = true,
                    qrImage = qr.image, qrExpires = qr.expiresIn,
                    qrRemainingSeconds = qr.expiresIn.coerceIn(1, 300),
                    message = "请使用红果 App 扫码并确认登录",
                ) }
                val deadline = SystemClock.elapsedRealtime() +
                    qr.expiresIn.coerceIn(1, 300) * 1_000L
                while (isActive && token == epoch && SystemClock.elapsedRealtime() < deadline) {
                    delay(2_500L)
                    if (token != epoch) return@launch
                    val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000)
                        .coerceAtLeast(0).toInt()
                    mutable.update { it.copy(qrRemainingSeconds = remaining) }
                    if (withContext(Dispatchers.IO) { session.poll() }) {
                        val web = withContext(Dispatchers.IO) { session.credentials() }
                        accept(web, token)
                        return@launch
                    }
                }
                if (token == epoch) mutable.update { it.copy(
                    waitingQr = false, qrImage = null, qrRemainingSeconds = 0,
                    message = "二维码已过期，请重新获取",
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch) mutable.update {
                it.copy(busy = false, waitingQr = false,
                    qrImage = null, message = explain(e))
            } }
        }
    }

    fun sendCode(phoneInput: String) {
        if (state.value.busy) return
        if (smsRemainingSeconds() > 0) {
            mutable.update { it.copy(smsRetrySeconds = smsRemainingSeconds(), message = "请等待倒计时结束后再获取验证码") }
            return
        }
        val phone = try { PhoneLogin.parse(phoneInput) }
            catch (e: IllegalArgumentException) {
                mutable.update { it.copy(message = e.message ?: "请输入有效手机号") }
                return
            }
        // Persist the cooldown before sending: a timeout or process restart must not auto-resend.
        prefs.edit().putLong("account_sms_retry_at", System.currentTimeMillis() + 60_000L).commit()
        resetLogin()
        val token = epoch
        val session = PhoneLogin(http)
        phoneLogin = session
        mutable.update { it.copy(busy = true, smsSent = false,
            smsRetrySeconds = smsRemainingSeconds(), message = "正在申请验证码…") }
        authJob = scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { session.send(phone) }
                prefs.edit().putLong("account_sms_retry_at",
                    maxOf(prefs.getLong("account_sms_retry_at", 0L),
                        System.currentTimeMillis() + sent.retrySeconds.coerceIn(1, 3600) * 1000L)).apply()
                if (token == epoch) mutable.update { it.copy(
                    busy = false, smsSent = true,
                    maskedPhone = sent.maskedMobile.take(50),
                    smsRetrySeconds = smsRemainingSeconds(),
                    message = "验证码已发送，请查看手机短信",
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch) mutable.update {
                it.copy(busy = false, message = explain(e))
            } }
        }
    }

    fun verifyCode(code: String) {
        if (state.value.busy) return
        if (!state.value.smsSent || !code.matches(Regex("[0-9]{4,8}"))) {
            mutable.update { it.copy(message = "请先获取验证码，输入 4–8 位数字") }
            return
        }
        val session = phoneLogin ?: return
        val token = epoch
        mutable.update { it.copy(busy = true, message = "正在验证登录…") }
        authJob = scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) { session.verify(code) }
                accept(credentials, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch) mutable.update {
                it.copy(busy = false,
                    smsSent = (e as? PhoneLogin.Failure)?.canRetryCode == true,
                    message = explain(e))
            } }
        }
    }

    /** For web QR/SMS the official business API needs a registered App device.
     * A passport cookie alone is not evidence that cloud operations work.
     */
    private suspend fun accept(web: AccountCredentials, token: Long) {
        val pair = withContext(Dispatchers.IO) {
            // App passport SMS already provides the device used to obtain the session.
            // Website QR sessions need a separate registered business device.
            val next = if (web.deviceId.isNotBlank() && web.installId.isNotBlank()) web else {
                val device = cloud.registerDevice() ?: throw IOException("账号设备注册失败")
                web.copy(deviceId = device.deviceId, installId = device.installId)
            }
            val profile = cloud.profile(next)
            // Cancellation/log-out must not persist credentials from a superseded login.
            currentCoroutineContext().ensureActive()
            if (token != epoch) throw CancellationException("Outdated login session")
            store.save(next)
            next to profile
        }
        if (token != epoch) return
        credentials = pair.first
        webLogin = null
        phoneLogin = null
        mutable.update { it.copy(
            busy = false, waitingQr = false, qrImage = null,
            smsSent = false, profile = pair.second, savedSessionPresent = true,
            message = "登录成功，读取云端记录…",
        ) }
        refresh()
    }

    /** Advanced import: never trust a cookie until the cloud business identity is verified.
     * Raw secrets are never stored in diagnostics or plain SharedPreferences.
     */
    fun importLoginCookie(rawCookie: String) {
        if (state.value.busy || state.value.checking) return
        val cookie = rawCookie.trim()
        if (cookie.length !in 10..8192 ||
            cookie.contains('\n') || cookie.contains('\r') ||
            !Regex("(?i)(?:^|;\\s*)sessionid(?:_ss)?=").containsMatchIn(cookie)) {
            mutable.update { it.copy(message = "请输入你本人账号的有效登录 Cookie") }
            return
        }
        resetLogin()
        val token = epoch
        mutable.update { it.copy(busy = true, message = "正在验证导入的账号身份…") }
        authJob = scope.launch {
            try {
                accept(AccountCredentials(cookie), token)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (token == epoch) mutable.update {
                    it.copy(busy = false, message = explain(error))
                }
            }
        }
    }

    fun refresh() {
        val session = credentials ?: return
        val token = epoch
        dataJob?.cancel()
        mutable.update { it.copy(busy = true, message = "正在刷新账号资料…") }
        dataJob = scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { cloud.profile(session) }
                if (token != epoch) return@launch
                mutable.update { it.copy(profile = profile,
                    message = "资料已验证，正在读取云端列表…") }
                val history = runCatching {
                    withContext(Dispatchers.IO) { cloud.history(session) }
                }
                if (token != epoch) return@launch
                mutable.update { it.copy(
                    history = history.getOrDefault(emptyList()),
                    historyLoaded = history.isSuccess,
                    message = if (history.isSuccess) "云端历史已更新"
                        else "历史暂不可用：" + explain(history.exceptionOrNull()!!),
                ) }
                val favorites = runCatching {
                    withContext(Dispatchers.IO) { cloud.favorites(session) }
                }
                if (token != epoch) return@launch
                mutable.update { it.copy(
                    favorites = favorites.getOrDefault(emptyList()),
                    favoritesLoaded = favorites.isSuccess,
                    message = if (favorites.isSuccess) "账号及云端记录已更新"
                        else "云端收藏暂不可用：" + explain(favorites.exceptionOrNull()!!),
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == epoch) mutable.update {
                    it.copy(profile = null, message = explain(e))
                }
            } finally {
                if (token == epoch) mutable.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Explicit tab request, isolated from history and favorite refresh.
     * Avoids a slow/failed likes endpoint blocking the rest of the account.
     */
    fun loadLikes(force: Boolean = false) {
        val session = credentials ?: return
        val token = epoch
        if (state.value.profile == null) return
        if (state.value.likesLoading && !force) return
        if (state.value.likesLoaded && !force) return
        likesJob?.cancel()
        mutable.update { it.copy(likesLoading = true, likesError = null) }
        likesJob = scope.launch {
            try {
                val results = withContext(Dispatchers.IO) { cloud.likes(session) }
                if (token == epoch && credentials === session) mutable.update {
                    it.copy(likes = results, likesLoaded = true,
                        likesLoading = false, likesError = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (token == epoch && credentials === session) mutable.update {
                    it.copy(likesLoading = false,
                        likesError = "点赞读取失败：" + explain(error))
                }
            }
        }
    }

    /** Reservations must not cancel a profile/history/favorites refresh. */
    fun loadReservations() {
        val session = credentials ?: return
        val token = epoch
        reservationsJob?.cancel()
        mutable.update { it.copy(reservationsLoading = true, message = "读取我的预约…") }
        reservationsJob = scope.launch {
            try {
                val online = runCatching { withContext(Dispatchers.IO) {
                    cloud.reservations(session, true)
                } }
                val upcoming = runCatching { withContext(Dispatchers.IO) {
                    cloud.reservations(session, false)
                } }
                if (token == epoch && credentials === session) mutable.update { it.copy(
                    online = online.getOrDefault(emptyList()),
                    upcoming = upcoming.getOrDefault(emptyList()),
                    reservationsLoaded = online.isSuccess && upcoming.isSuccess,
                    message = if (online.isSuccess && upcoming.isSuccess)
                        "预约记录已更新" else "部分预约数据暂不可用",
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == epoch && credentials === session) mutable.update {
                    it.copy(message = "预约读取失败：" + explain(e))
                }
            } finally {
                if (token == epoch && credentials === session)
                    mutable.update { it.copy(reservationsLoading = false) }
            }
        }
    }

    /** Only explicit user action writes cloud favorites; read-back confirms it. */
    fun cloudFavorite(seriesId: String, add: Boolean) {
        val session = credentials ?: return
        val token = epoch
        if (!seriesId.matches(Regex("[0-9]{1,30}"))) return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = "正在更新云端收藏…") }
        scope.launch {
            try {
                val rows = withContext(Dispatchers.IO) {
                    cloud.setFavorite(session, seriesId, add)
                    cloud.favorites(session)
                }
                if (token == epoch) mutable.update { it.copy(
                    favorites = rows, favoritesLoaded = true,
                    message = if (rows.any { it.series.id == seriesId } == add)
                        "云端收藏更新已确认" else "服务端未确认修改，请稍后刷新",
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch) mutable.update {
                it.copy(message = "云收藏操作失败：" + explain(e))
            } }
            finally { if (token == epoch) mutable.update { it.copy(busy = false) } }
        }
    }

    /** Cookie is only passed to the official recommendation API if enabled. */
    internal fun recommendationAccount(): FeedAccount? {
        if (state.value.profile == null || !state.value.accountRecommendations) return null
        return credentials?.takeIf { it.cookie.isNotBlank() }
            ?.let { FeedAccount(it.cookie, it.device) }
    }

    fun setAccountRecommendations(enabled: Boolean) {
        if (state.value.profile == null) return
        prefs.edit().putBoolean("account_recommend_opt_in", enabled).apply()
        mutable.update { it.copy(accountRecommendations = enabled) }
    }

    /** Cloud progress is strictly opt-in. Never merge uploaded records into
     * the local library. Network writes are serialized and bounded to ~30s.
     */
    fun setProgressSync(enabled: Boolean) {
        if (state.value.profile == null) return
        prefs.edit().putBoolean("cloud_progress_opt_in", enabled).apply()
        mutable.update { it.copy(syncEnabled = enabled) }
        if (!enabled) {
            queuedUpload = null
            uploadJob?.cancel()
        }
    }

    fun uploadProgress(
        series: Series, vid: String, index: Int, position: Long,
        duration: Long, episodeCount: Int, force: Boolean = false,
    ) {
        val session = credentials ?: return
        if (state.value.profile == null || !state.value.syncEnabled) return
        if (!series.id.matches(Regex("[0-9]{1,30}")) ||
            !vid.matches(Regex("[0-9]{1,30}")) ||
            !ResumePolicy.persistable(position, duration)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUploadTimeMs < 30_000L) return
        lastUploadTimeMs = now
        queuedUpload = CloudProgress(
            series, vid, index.coerceAtLeast(0), position, duration,
            System.currentTimeMillis(), episodeCount,
        ) to episodeCount
        if (uploadJob?.isActive == true) return
        val token = epoch
        uploadJob = scope.launch {
            while (isActive && token == epoch) {
                val entry = queuedUpload ?: break
                queuedUpload = null
                try {
                    withContext(Dispatchers.IO) {
                        cloud.upload(session, entry.first, entry.second)
                    }
                    if (token == epoch) mutable.update {
                        it.copy(message = "已提交云端播放进度，请在官方 App 核对")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token == epoch) mutable.update {
                        it.copy(message = "云端进度同步失败：" + explain(e))
                    }
                }
            }
        }
    }

    fun logout() {
        resetLogin()
        dataJob?.cancel()
        reservationsJob?.cancel()
        likesJob?.cancel()
        uploadJob?.cancel()
        queuedUpload = null
        credentials = null
        prefs.edit().putBoolean("cloud_progress_opt_in", false)
            .putBoolean("account_recommend_opt_in", false).apply()
        mutable.value = AccountUiState(checking = false, message = "已退出红果账号")
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { store.clear() } }
            if (r.isFailure) mutable.update {
                it.copy(message = "退出登录后清除本机会话失败，请重试")
            }
        }
    }

    fun closeScreen() {
        if (state.value.waitingQr || state.value.qrImage != null) resetLogin()
    }
}
