package com.yagay.hongguo

import com.hongguotv.core.PlaybackDeviceRegistrationException
import com.hongguotv.core.EmptyPlaybackResponseException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProblemTest {
    @Test fun nativeRegistrationFailureHasItsOwnCode() {
        val error = PlaybackDeviceRegistrationException(UnknownHostException("hidden.domain"))
        assertTrue(PlaybackProblem.describe("播放模型", error).startsWith("DEVICE_REGISTER_DNS"))
    }

    @Test fun nativeEmptyModelIsNotGenericNetworkIo() {
        val error = EmptyPlaybackResponseException("G:b=200/0B/h2")
        assertTrue(PlaybackProblem.describe("取址", error).startsWith("PLAYBACK_EMPTY_RESPONSE"))
    }

    @Test fun retryEligibilityIsLimitedToTypedEmptyPlaybackModel() {
        assertTrue(PlaybackProblem.isEmptyPlaybackModel(EmptyPlaybackResponseException()))
        assertTrue(PlaybackProblem.isEmptyPlaybackModel(
            IOException("wrapper", EmptyPlaybackResponseException())))
        assertFalse(PlaybackProblem.isEmptyPlaybackModel(SocketTimeoutException()))
        assertFalse(PlaybackProblem.isEmptyPlaybackModel(IOException("CODEC_NOT_SUPPORTED")))
        assertFalse(PlaybackProblem.isEmptyPlaybackModel(IOException("EMPTY_RESPONSE")))
    }

    @Test fun transientTransportCanRetryWithoutRetryingProtocolOrCodecFailures() {
        assertTrue(PlaybackProblem.isTransientTransport(IOException("network disconnected")))
        assertTrue(PlaybackProblem.isTransientTransport(SocketTimeoutException()))
        assertTrue(PlaybackProblem.isTransientTransport(
            IOException("outer", UnknownHostException("example.com"))))
        assertFalse(PlaybackProblem.isTransientTransport(EmptyPlaybackResponseException()))
        assertFalse(PlaybackProblem.isTransientTransport(IOException("CODEC_NOT_SUPPORTED")))
        assertFalse(PlaybackProblem.isTransientTransport(IOException("HTTP 403")))
        assertFalse(PlaybackProblem.isTransientTransport(IOException("HTTP 429")))
        assertFalse(PlaybackProblem.isTransientTransport(UnsupportedOperationException()))
    }

    @Test fun nativeUnsupportedCodecIsDistinct() {
        assertTrue(PlaybackProblem.describe("准备播放",
            IOException("CODEC_NOT_SUPPORTED")).startsWith("CODEC_NOT_SUPPORTED"))
    }

    @Test fun timeoutClassified() {
        assertTrue(PlaybackProblem.describe("取址", SocketTimeoutException()).startsWith("NETWORK_TIMEOUT"))
    }
    @Test fun nestedDnsClassified() {
        assertTrue(PlaybackProblem.describe("注册",
            IOException("wrapped", UnknownHostException("example.com"))).startsWith("NETWORK_DNS"))
    }
    @Test fun upstreamSecretsNeverExposed() {
        val message = PlaybackProblem.describe("拉流",
            IOException("HTTP 403 https://example.com/path?api_key=SECRET Cookie=SECRET"))
        assertTrue(message.startsWith("UPSTREAM_FORBIDDEN"))
        assertFalse(message.contains("SECRET"))
        assertFalse(message.contains("example.com"))
    }
    @Test fun decoderErrorClassified() {
        assertTrue(PlaybackProblem.media("ERROR_CODE_DECODING_FAILED", "DecoderException")
            .startsWith("MEDIA_CODEC_UNSUPPORTED"))
    }
}
