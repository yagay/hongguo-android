package com.hongguo.rewrite

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
    val busy: Boolean = false,
    val profile: CloudProfile? = null,
    val qrImage: ByteArray? = null,
    val qrExpires: Int = 0,
    val waitingQr: Boolean = false,
    val smsSent: Boolean = false,
    val maskedPhone: String = "",
    val message: String = "读取加密登录状态…",
    val history: List<CloudProgress> = emptyList(),
    val favorites: List<CloudFavorite> = emptyList(),
    val upcoming: List<CloudReservation> = emptyList(),
    val online: List<CloudReservation> = emptyList(),
    val historyLoaded: Boolean = false,
    val favoritesLoaded: Boolean = false,
    val reservationsLoaded: Boolean = false,
)

internal class AccountManager(app: Application, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val cloud = CloudAccountClient(http)
    private val store = SecureAccountStore(app)
    private val mutable = MutableStateFlow(AccountUiState())
    val state = mutable.asStateFlow()
    private var credentials: AccountCredentials? = null
    private var webLogin: WebLogin? = null
    private var authJob: Job? = null
    private var dataJob: Job? = null
    private var epoch = 0L

    init {
        scope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { store.read() } }
            if (saved.isFailure) {
                withContext(Dispatchers.IO) { runCatching { store.clear() } }
                mutable.update { it.copy(checking = false, message = "会话解密失败，请重新登录") }
            } else {
                credentials = saved.getOrNull()
                mutable.update { it.copy(checking = false,
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
        mutable.update { it.copy(qrImage = null, waitingQr = false, busy = false) }
    }

    fun cancelQr() {
        if (state.value.waitingQr) resetLogin()
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
                    message = "请使用红果 App 扫码并确认登录",
                ) }
                val deadline = SystemClock.elapsedRealtime() +
                    qr.expiresIn.coerceIn(1, 300) * 1_000L
                while (isActive && token == epoch && SystemClock.elapsedRealtime() < deadline) {
                    delay(2_500L)
                    if (withContext(Dispatchers.IO) { session.poll() }) {
                        val web = withContext(Dispatchers.IO) { session.credentials() }
                        accept(web, token)
                        return@launch
                    }
                }
                if (token == epoch) mutable.update { it.copy(
                    waitingQr = false, qrImage = null,
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
        val phone = phoneInput.trim()
        if (!phone.matches(Regex("^\\+[0-9][0-9 ()-]{5,23}$"))) {
            mutable.update { it.copy(message = "请输入国际区号和手机号，例如 +86 13800138000") }
            return
        }
        resetLogin()
        val token = epoch
        val session = WebLogin(http)
        webLogin = session
        mutable.update { it.copy(busy = true, smsSent = false,
            message = "正在申请验证码…") }
        authJob = scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) { session.send(phone) }
                if (token == epoch) mutable.update { it.copy(
                    busy = false, smsSent = true,
                    maskedPhone = sent.maskedMobile.take(50),
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
        val session = webLogin ?: return
        val token = epoch
        mutable.update { it.copy(busy = true, message = "正在验证登录…") }
        authJob = scope.launch {
            try {
                val credentials = withContext(Dispatchers.IO) { session.verify(code) }
                accept(credentials, token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch) mutable.update {
                it.copy(busy = false, message = explain(e))
            } }
        }
    }

    /** For web QR/SMS the official business API needs a registered App device.
     * A passport cookie alone is not evidence that cloud operations work.
     */
    private suspend fun accept(web: AccountCredentials, token: Long) {
        val pair = withContext(Dispatchers.IO) {
            val device = cloud.registerDevice() ?: throw IOException("账号设备注册失败")
            val next = web.copy(deviceId = device.deviceId, installId = device.installId)
            val profile = cloud.profile(next)
            store.save(next)
            next to profile
        }
        if (token != epoch) return
        credentials = pair.first
        webLogin = null
        mutable.update { it.copy(
            busy = false, waitingQr = false, qrImage = null,
            smsSent = false, profile = pair.second,
            message = "登录成功，读取云端记录…",
        ) }
        refresh()
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

    fun loadReservations() {
        val session = credentials ?: return
        val token = epoch
        dataJob?.cancel()
        mutable.update { it.copy(busy = true, message = "读取我的预约…") }
        dataJob = scope.launch {
            try {
                val online = runCatching { withContext(Dispatchers.IO) {
                    cloud.reservations(session, true)
                } }
                val upcoming = runCatching { withContext(Dispatchers.IO) {
                    cloud.reservations(session, false)
                } }
                if (token == epoch) mutable.update { it.copy(
                    online = online.getOrDefault(emptyList()),
                    upcoming = upcoming.getOrDefault(emptyList()),
                    reservationsLoaded = online.isSuccess && upcoming.isSuccess,
                    message = if (online.isSuccess && upcoming.isSuccess)
                        "预约记录已更新" else "部分预约数据暂不可用",
                ) }
            } catch (e: CancellationException) { throw e }
            finally { if (token == epoch) mutable.update { it.copy(busy = false) } }
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

    fun logout() {
        resetLogin()
        dataJob?.cancel()
        credentials = null
        mutable.value = AccountUiState(checking = false, message = "已退出红果账号")
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { store.clear() } }
            if (r.isFailure) mutable.update {
                it.copy(message = "退出登录后清除本机会话失败，请重试")
            }
        }
    }

    fun closeScreen() {
        if (state.value.waitingQr) resetLogin()
    }
}
