package com.hongguotv.core

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationTransientIoTest {
    @Test fun transientConnectionErrorsCanRotateToNextHost() {
        assertTrue(RecommendationFeed.transientFeedIo(SocketTimeoutException()))
        assertTrue(RecommendationFeed.transientFeedIo(ConnectException()))
        assertTrue(RecommendationFeed.transientFeedIo(UnknownHostException()))
        assertTrue(RecommendationFeed.transientFeedIo(EOFException()))
        assertTrue(RecommendationFeed.transientFeedIo(
            IOException("推荐接口返回空响应，请稍后重试")))
        assertTrue(RecommendationFeed.transientFeedIo(
            IOException("推荐请求失败（HTTP 503）")))
    }

    @Test fun authorizationAndRateLimitDoNotTriggerBlindHostRetry() {
        assertFalse(RecommendationFeed.transientFeedIo(
            IOException("推荐请求失败（HTTP 401）")))
        assertFalse(RecommendationFeed.transientFeedIo(
            IOException("推荐请求失败（HTTP 403）")))
        assertFalse(RecommendationFeed.transientFeedIo(
            IOException("推荐请求失败（HTTP 429）")))
        assertFalse(RecommendationFeed.transientFeedIo(
            IOException("REELS_ACCOUNT_ACCESS_REJECTED")))
        assertFalse(RecommendationFeed.transientFeedIo(
            IOException("推荐数据格式已变化")))
    }
}
