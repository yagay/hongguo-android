package com.hongguo.rewrite

import com.hongguotv.core.NoNewRecommendationsException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialReelsFailureTest {
    @Test fun emptyRecommendationIsNotReportedAsNetworkIo() {
        val failure = NoNewRecommendationsException()
        assertEquals("NO_NEW", OfficialReelsFailure.code(failure))
        assertTrue(OfficialReelsFailure.userMessage(failure).contains("没有返回新的"))
    }

    @Test fun accountRejectedIsNotMistakenForConnectivity() {
        assertEquals("ACCOUNT_ACCESS_REJECTED",
            OfficialReelsFailure.code(IOException("REELS_ACCOUNT_ACCESS_REJECTED")))
        assertEquals("ACCOUNT_ACCESS_REJECTED",
            OfficialReelsFailure.code(IOException("推荐暂不可用（错误码 110）")))
    }

    @Test fun transientFailuresAreSeparated() {
        assertEquals("SERVER_UNAVAILABLE",
            OfficialReelsFailure.code(IOException("推荐请求失败（HTTP 503）")))
        assertEquals("NETWORK_TIMEOUT",
            OfficialReelsFailure.code(SocketTimeoutException()))
        assertEquals("EMPTY_RESPONSE",
            OfficialReelsFailure.code(IOException("推荐接口返回空响应，请稍后重试")))
    }

    @Test fun upstreamSecretsCannotLeakToUi() {
        val userText = OfficialReelsFailure.userMessage(
            IOException("hidden https://private.example/?cookie=SECRET"))
        assertFalse(userText.contains("SECRET"))
        assertFalse(userText.contains("private.example"))
    }
}
