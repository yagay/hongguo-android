package com.hongguo.android

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiFailureMessagesTest {
    @Test fun classifiesNetworkErrors() {
        assertEquals("网络连接不可用，请检查网络后重试。", readableFailure(UnknownHostException(), "搜索"))
        assertEquals("连接超时，请稍后重试。", readableFailure(SocketTimeoutException(), "搜索"))
        assertEquals("安全连接失败，请检查网络和设备时间。", readableFailure(SSLException("bad certificate"), "搜索"))
    }

    @Test fun doesNotShowInternalDiagnosticsOrTokens() {
        val message = readableFailure(IOException("Bearer secret-account-token; https://invalid.example/path?token=abc"), "搜索")
        assertEquals("搜索失败，请检查网络后重试。", message)
        assertFalse(message.contains("secret-account-token"))
        assertFalse(message.contains("token=abc"))
    }

    @Test fun categorizesMissingEpisodes() {
        assertEquals("暂时没有获取到可播放剧集，请稍后重试。",
            readableFailure(IllegalStateException("empty_episodes"), "获取剧集"))
    }
}
