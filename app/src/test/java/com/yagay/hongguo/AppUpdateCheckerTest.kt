package com.yagay.hongguo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateCheckerTest {
    private fun release(code: Int, sha: String = "0123456789ab",
                        hasApk: Boolean = true, draft: Boolean = false): JSONObject {
        val tag = "upstream-auto-v" + code + "-" + sha
        val assets = JSONArray()
        if (hasApk) assets.put(JSONObject()
            .put("name", "YHongguo-update.apk")
            .put("size", 1200)
            .put("browser_download_url",
                "https://github.com/yagay/hongguo-android/releases/download/" +
                    tag + "/YHongguo-update.apk"))
        return JSONObject().put("tag_name", tag)
            .put("draft", draft).put("prerelease", true)
            .put("name", "YHongguo " + code)
            .put("html_url",
                "https://github.com/yagay/hongguo-android/releases/tag/" + tag)
            .put("assets", assets)
    }

    @Test fun picksHighestNewerSignedRelease() {
        val input = JSONArray().put(release(1000100)).put(release(1000102))
            .put(release(1000101)).toString()
        val result = AppUpdateChecker.newestVerifiedRelease(input, 1000100)
        assertEquals(1000102, result?.versionCode)
        assertEquals("0123456789ab", result?.upstreamSha)
    }

    @Test fun neverPromptsForCurrentOrOlderVersion() {
        assertNull(AppUpdateChecker.newestVerifiedRelease(
            JSONArray().put(release(1000102)).toString(), 1000102))
    }

    @Test fun ignoresDraftMissingApkAndMismatchedRepo() {
        val bad = release(1000300)
            .put("html_url", "https://attacker.example/releases/tag/upstream-auto-v1000300-0123456789ab")
        val array = JSONArray().put(release(1000200, hasApk = false))
            .put(release(1000201, draft = true)).put(bad)
        assertNull(AppUpdateChecker.newestVerifiedRelease(array.toString(), 1))
    }

    @Test fun ignoresUnexpectedTagsAndDownloadOrigins() {
        val wrongTag = release(1000500).put("tag_name", "debug-1000500")
        val valid = release(1000501)
        valid.put("assets", JSONArray().put(JSONObject()
            .put("name", "YHongguo-update.apk")
            .put("size", 2048)
            .put("browser_download_url", "https://unknown.example/fake.apk")))
        assertNull(AppUpdateChecker.newestVerifiedRelease(
            JSONArray().put(wrongTag).put(valid).toString(), 1))
    }
}
