package com.yagay.hongguo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Reads only signed, successfully published upstream-auto GitHub Releases.
 * Never downloads executable files automatically or installs without user action.
 */
internal object AppUpdateChecker {
    private const val RELEASES_API =
        "https://api.github.com/repos/yagay/hongguo-android/releases?per_page=30"
    private const val PREFS = "yhongguo_app_updates"
    private const val LAST_CHECK = "last_successful_check"
    private const val DISMISSED_CODE = "dismissed_version_code"
    private const val CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L
    private val releaseTag = Regex("^upstream-auto-v([1-9][0-9]{0,9})-([a-f0-9]{12})$")
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Candidate(
        val versionCode: Int,
        val upstreamSha: String,
        val versionName: String,
        val releaseUrl: String,
        val apkUrl: String,
        val notes: String,
    )

    sealed interface Result {
        data class Update(val candidate: Candidate) : Result
        data object UpToDate : Result
        data object Skipped : Result
        data class Failed(val message: String) : Result
    }

    fun wasDismissed(context: Context, versionCode: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(DISMISSED_CODE, 0) >= versionCode

    fun dismiss(context: Context, versionCode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(DISMISSED_CODE, versionCode).apply()
    }

    /** Pure JSON parser; safe to exercise with local release fixtures. */
    fun newestVerifiedRelease(json: String, installedCode: Int): Candidate? {
        val releases = JSONArray(json)
        var best: Candidate? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft", true)) continue
            val tag = releaseTag.matchEntire(release.optString("tag_name")) ?: continue
            val code = tag.groupValues[1].toIntOrNull() ?: continue
            if (code <= installedCode || code > 2100000000) continue
            val releaseUrl = release.optString("html_url")
            if (!releaseUrl.startsWith(
                    "https://github.com/yagay/hongguo-android/releases/tag/"
                )) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                if (asset.optString("name") != "YHongguo-update.apk") continue
                if (asset.optLong("size") <= 0L) continue
                val url = asset.optString("browser_download_url")
                if (url.startsWith(
                        "https://github.com/yagay/hongguo-android/releases/download/"
                    )) apkUrl = url
            }
            if (apkUrl == null) continue
            if (best == null || code > best.versionCode) {
                best = Candidate(
                    versionCode = code,
                    upstreamSha = tag.groupValues[2],
                    versionName = release.optString("name").take(100),
                    releaseUrl = releaseUrl,
                    apkUrl = apkUrl,
                    notes = release.optString("body").take(1000),
                )
            }
        }
        return best
    }

    suspend fun check(context: Context, force: Boolean): Result = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_CHECK, 0L)
        if (!force && last in 1..now && now - last < CHECK_INTERVAL_MS) {
            return@withContext Result.Skipped
        }
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "YHongguo-AppUpdate-Checker")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.Failed("检查失败：HTTP " + response.code)
                }
                val body = response.body?.string()
                    ?: return@withContext Result.Failed("更新信息为空")
                val latest = newestVerifiedRelease(body, BuildConfig.VERSION_CODE)
                prefs.edit().putLong(LAST_CHECK, now).apply()
                if (latest != null) Result.Update(latest) else Result.UpToDate
            }
        } catch (_: Exception) {
            Result.Failed("网络不可用，稍后可在设置中重新检查")
        }
    }
}
