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
    @Test fun SDKAnonymousLimitDoesNotHammerRejectedLegacyModelRoutes() {
        val error = OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED,
            "anonymous empty model")
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(error))
        assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(error, 1))
        assertTrue(PlaybackProblem.describe("准备播放", error)
            .startsWith("PLAYBACK_ANONYMOUS_LIMITED"))
    }

    @Test fun emptyHttp200DoesNotMasqueradeAsAnonymousDenial() {
        val empty = OpenhongguoException(ErrorCode.RESPONSE_EMPTY,
            "synthetic HTTP 200 empty model")
        assertTrue(PlaybackSourceRecoveryPolicy.nativeFallback(empty))
        assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(empty, 1))
        assertEquals("PLAYBACK_MODEL_EMPTY",
            PlaybackProblem.describe("播放模型", empty).substringBefore(" · "))
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(
            OpenhongguoException(ErrorCode.DEVICE_REJECTED, "device denied")))
        assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(
            OpenhongguoException(ErrorCode.ANONYMOUS_LIMITED, "explicit guest access denied")))
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

    @Test fun explicitSdkErrorsHaveDistinctNonRetryableDiagnosticCodes() {
        val cases = mapOf(
            ErrorCode.SIGNATURE_REJECTED to "UPSTREAM_SIGNATURE_REJECTED",
            ErrorCode.CHALLENGE_REQUIRED to "UPSTREAM_VERIFICATION_REQUIRED",
            ErrorCode.DRM_PROTECTED to "CONTENT_PROTECTED",
            ErrorCode.SIGNER_UNAVAILABLE to "SDK_SIGNER_UNAVAILABLE",
        )
        for ((code, expected) in cases) {
            val error = OpenhongguoException(code, "private server response", retryable = true)
            assertEquals(expected, PlaybackProblem.describe("准备播放", error).substringBefore(" · "))
            assertFalse(PlaybackSourceRecoveryPolicy.nativeFallback(error))
            assertFalse(PlaybackSourceRecoveryPolicy.retrySdk(error, 1))
            assertFalse(PeriodicPlaybackRetryPolicy.recoverableSource(error))
        }
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
