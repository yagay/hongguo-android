// SPDX-License-Identifier: GPL-3.0-only
package com.yagay.hongguo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Independent, anonymous website source. Only explicit public full-episode
 * eligibility is accepted. No credentials, identity changes, decryption,
 * protected-content playback, or redirect following. Adapted from the
 * public visitor checks in OOIUa/hongguo-android (GPL-3.0-only), 1808382.
 */
internal class PublicEpisodePlaybackSource(private val diagnostics: DiagnosticLog? = null) {
    class Unavailable : IOException("Public episode unavailable")
    class Limited : IOException("Public source rate limited")
    class Invalid : IOException("Invalid public episode response")

    suspend fun resolve(seriesId: String, vid: String): String = withContext(Dispatchers.IO) {
        if (!ID.matches(seriesId) || !ID.matches(vid)) throw Invalid()
        val html = load(Request.Builder()
            .url("https://hongguoduanju.com/player/$seriesId/$vid")
            .header("Accept", "text/html").build(), 4 * 1024 * 1024)
        val root = try { NativeWebsiteSource.extractRouter(html) }
            catch (_: Exception) { throw Invalid() }
        requirePublicEpisode(root, seriesId, vid)
        diagnostics?.record("PLAYBACK", "PUBLIC_WEBSITE_ELIGIBILITY_CONFIRMED")
        val body = JSONObject().put("item_id", vid).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val text = load(Request.Builder()
            .url("https://hongguoduanju.com/api/video/protected/fallback-play-info")
            .header("Accept", "application/json").post(body).build(), 64 * 1024)
        val response = try { JSONObject(text) } catch (_: Exception) { throw Invalid() }
        checkedMedia(response, System.currentTimeMillis() / 1000L)
    }

    private suspend fun load(request: Request, limit: Int): String =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(IOException("Public source network failure"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            checkStatus(it)
                            val body = it.body ?: throw Invalid()
                            if (body.contentLength() > limit) throw Invalid()
                            val data = body.source()
                            if (data.request(limit.toLong() + 1)) throw Invalid()
                            data.readUtf8()
                        }
                        if (cont.isActive) cont.resume(value)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(
                            if (e is Unavailable || e is Limited || e is Invalid) e
                            else IOException("Public source read failure"))
                    }
                }
            })
        }

    companion object {
        private val ID = Regex("[0-9]{1,30}")
        private val domains = listOf("qznovelvod.com", "fqnovelvod.com", "douyinvod.com")
        private fun checkStatus(response: Response) {
            if (response.code == 429 || !response.header("Retry-After").isNullOrBlank())
                throw Limited()
            if (response.code in 300..399 || response.code in listOf(401, 403, 404))
                throw Unavailable()
            if (response.code != 200) throw Invalid()
        }

        private val http = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(6, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                try { checkStatus(response); response }
                catch (e: IOException) { response.close(); throw e }
            }.build()

        internal val mediaClient = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                if (!validMediaUrl(chain.request().url.toString())) throw Invalid()
                val response = chain.proceed(chain.request())
                if (response.code == 429 || !response.header("Retry-After").isNullOrBlank()) {
                    response.close()
                    throw Limited()
                }
                response
            }.build()

        internal fun requirePublicEpisode(root: JSONObject, seriesId: String, vid: String) {
            val page = root.optJSONObject("loaderData")?.optJSONObject("player_(series_id)/(vid)/page")
                ?: throw Invalid()
            if (page.opt("isSuccess") != true || page.optString("series_id") != seriesId ||
                page.optString("vid") != vid) throw Invalid()
            val series = page.optJSONObject("seriesDetail") ?: throw Invalid()
            if (series.optString("series_id") != seriesId) throw Invalid()
            val req = page.optJSONObject("req") ?: throw Unavailable()
            if (req.opt("pcLoginRequired") != false ||
                req.opt("pcAuthenticatedPlayback") != false) throw Unavailable()
            val rows = series.optJSONArray("vid_list") ?: throw Invalid()
            val count = series.optString("accessible_episode_cnt").toIntOrNull()
                ?.takeIf { it in 1..rows.length() } ?: throw Unavailable()
            if ((0 until count).none { rows.optString(it) == vid }) throw Unavailable()
            // More conservative than the reference: require explicit public
            // metadata for the same episode and a positive advertised duration.
            val seo = page.optJSONObject("publicVideoSeo") ?: throw Unavailable()
            if (seo.opt("available") != true) throw Unavailable()
            if (seo.optString("seriesId") != seriesId || seo.optString("itemId") != vid)
                throw Invalid()
            val duration = seo.optDouble("durationSeconds")
            if (!duration.isFinite() || duration <= 0) throw Invalid()
        }

        internal fun checkedMedia(response: JSONObject, nowSeconds: Long): String {
            val status = response.optJSONObject("base_resp")?.optString("StatusCode")
                ?: response.optString("code")
            if (status != "0") throw Unavailable()
            if (response.has("url_expire")) {
                val expires = response.optDouble("url_expire")
                if (!expires.isFinite() || expires <= nowSeconds + 5) throw Invalid()
            }
            for (field in listOf("protected", "drm", "encrypted", "need_login")) {
                if (response.has(field) && response.opt(field) != false &&
                    response.opt(field) != 0 && response.opt(field) != "0") throw Unavailable()
            }
            val url = response.opt("main_url") as? String ?: throw Invalid()
            if (!validMediaUrl(url)) throw Invalid()
            return url
        }

        internal fun validMediaUrl(raw: String): Boolean {
            val url = raw.toHttpUrlOrNull() ?: return false
            return url.isHttps && url.port == 443 && url.username.isEmpty() &&
                url.password.isEmpty() && url.fragment == null &&
                domains.any { url.host == it || url.host.endsWith(".$it") }
        }
    }
}
