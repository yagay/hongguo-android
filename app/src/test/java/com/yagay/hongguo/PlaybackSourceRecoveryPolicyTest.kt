package com.yagay.hongguo

import com.hongguotv.core.EmptyPlaybackResponseException
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackSourceRecoveryPolicyTest {
    @Test fun SDKAnonymousLimitUsesIndependentNativeSourceNotRepeatedSdkCalls() {
        val error = OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED,
            "anonymous empty model")
        assertTrue(PlaybackSourceRecoveryPolicy.nativeFallback(error))
        assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(error, 1))
        assertTrue(PlaybackProblem.describe("准备播放", error)
            .startsWith("PLAYBACK_ANONYMOUS_LIMITED"))
    }

    @Test fun onlyOneAdditionalOuterSdkAttemptAfterInternalRetries() {
        val error = OpenhongguoException(ErrorCode.TRANSPORT,
            "network temporarily failed", retryable = true)
        assertTrue(PlaybackSourceRecoveryPolicy.retrySdk(error, 1))
        assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(error, 2))
        assertEquals(1500L, PlaybackSourceRecoveryPolicy.retryDelayMs(error))
        assertTrue(PlaybackSourceRecoveryPolicy.nativeFallback(error))
        assertTrue(PlaybackSourceRecoveryPolicy.nativeFallback(
            IOException("outer", SocketTimeoutException())))
    }

    @Test fun explicitDenialsAndRateLimitsNeverFailOverOrSpin() {
        for (code in listOf(
            ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.SIGNATURE_REJECTED,
            ErrorCode.CONTENT_UNAVAILABLE, ErrorCode.RATE_LIMITED,
            ErrorCode.CHALLENGE_REQUIRED,
        )) {
            val error = OpenhongguoException(code, "denied", retryable = true,
                retryAfterMs = 12_000L)
            assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(error, 1))
            assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(error))
        }
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(IOException("HTTP 403")))
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(IOException("HTTP 429")))
    }

    @Test fun preserveThePreciseSdkFailureWhenLegacyPlaybackReturnsEmpty() {
        val primary = OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED,
            "anonymous video model returned empty")
        val legacy = EmptyPlaybackResponseException("route=old-player-api")
        assertSame(primary, PlaybackSourceRecoveryPolicy
            .preservePrimaryOnLegacyEmpty(primary, legacy))
        assertSame(legacy, PlaybackSourceRecoveryPolicy
            .preservePrimaryOnLegacyEmpty(null, legacy))
        val transport = IOException("backup CDN unavailable")
        assertSame(transport, PlaybackSourceRecoveryPolicy
            .preservePrimaryOnLegacyEmpty(primary, transport))
    }

    @Test fun incompatiblePrimaryMp4ContainerCanTryNativeWithoutLooping() {
        assertTrue(PlaybackSourceRecoveryPolicy.nativeFallback(
            IOException("该集没有开心果支持的单文件 MP4 视频")))
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(
            IOException("CODEC_NOT_SUPPORTED")))
        assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(
            IOException("CODEC_NOT_SUPPORTED"), 1))
    }
}
