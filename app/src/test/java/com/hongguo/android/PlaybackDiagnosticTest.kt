package com.hongguo.android

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticTest {
    @Test fun codecIsNotReportedAsNetworkFailure() {
        val failure = PlaybackDiagnostic.fromPreparationFailure(
            IllegalStateException("本集仅提供播放器不支持的 ByteVC2 编码，当前无法播放"))
        assertEquals("CODEC_UNSUPPORTED", failure.code)
        assertEquals("codec_select", failure.stage)
        assertFalse(failure.message.contains("检查网络"))
    }

    @Test fun malformedModelIsDistinguishedFromNetwork() {
        val failure = PlaybackDiagnostic.fromPreparationFailure(
            IllegalStateException("App 接口未返回所选分集"))
        assertEquals("VIDEO_MODEL_INVALID", failure.code)
        assertEquals("multi_video_model", failure.stage)
    }

    @Test fun classifiesKnownTransportErrors() {
        assertEquals("PLAYBACK_DNS_FAILED", PlaybackDiagnostic.fromPreparationFailure(
            UnknownHostException("secret.provider.test")).code)
        assertEquals("PLAYBACK_TIMEOUT", PlaybackDiagnostic.fromPreparationFailure(
            SocketTimeoutException("https://internal.test?token=secret")).code)
        assertEquals("PLAYBACK_TLS_FAILED", PlaybackDiagnostic.fromPreparationFailure(
            SSLException("invalid certificate")).code)
    }

    @Test fun unknownErrorsAreNotAutomaticallyNetworkErrors() {
        assertEquals("MEDIA_PREPARE_IO_FAILED", PlaybackDiagnostic.fromPreparationFailure(
            IOException("local I/O")).code)
        assertEquals("PLAYBACK_PREPARE_FAILED", PlaybackDiagnostic.fromPreparationFailure(
            IllegalStateException("something unexpected")).code)
    }

    @Test fun supportReportIsStrictlyAllowlisted() {
        val failure = PlaybackDiagnostic.fromPreparationFailure(
            IOException("Bearer secret-cookie signed_url=https://provider.test/video?token=private"))
        val report = failure.safeReport()
        assertEquals("PLAYBACK_PREPARE_FAILED", failure.code)
        assertTrue(report.contains("code=PLAYBACK_PREPARE_FAILED"))
        for (secret in listOf("secret-cookie", "provider.test", "signed_url", "token=private")) {
            assertFalse(secret, report.contains(secret))
            assertFalse(secret, failure.message.contains(secret))
        }
    }

    @Test fun playerDecoderFailureHasDedicatedStage() {
        val failure = PlaybackDiagnostic.playerFailure(true)
        assertEquals("PLAYER_DECODER_UNSUPPORTED", failure.code)
        assertEquals("media_decoder", failure.stage)
        assertEquals("PLAYER_PLAYBACK_FAILED", PlaybackDiagnostic.playerFailure(false).code)
    }

    @Test fun reportOnlyEmitsBoundedMetadata() {
        val issue = PlaybackDiagnostic(
            code = "VIDEO_MODEL_HTTP", stage = "multi_video_model", stageLabel = "播放接口",
            message = "接口失败", attempts = 4, httpStatus = 403, responseBytes = 0)
        val report = issue.safeReport()
        assertTrue(report.contains("attempts=4"))
        assertTrue(report.contains("http_status=403"))
        assertTrue(report.contains("response_bytes=0"))
    }
}
