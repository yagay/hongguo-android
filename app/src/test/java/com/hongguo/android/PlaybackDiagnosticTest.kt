package com.hongguo.android

import com.hongguo.api.service.PlaybackDevice
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackDiagnosticTest {
    @Test fun registrationUsesSafeFixedCategory() {
        val constructor = PlaybackDevice.RegistrationException::class.java.getDeclaredConstructor(Throwable::class.java)
        constructor.isAccessible = true
        val error = constructor.newInstance(ConnectException("https://private.example/?cookie=abc"))
        val result = PlaybackDiagnostic.fromPreparationFailure(error)
        assertEquals("DEVICE_REGISTER_CONNECT_FAILED", result.code)
        assertEquals("device_register", result.stage)
        assertFalse(result.safeReport().contains("cookie"))
        assertFalse(result.safeReport().contains("private.example"))
    }

    @Test fun dnsFailureClassifiesWithoutServerData() {
        val result = PlaybackDiagnostic.fromPreparationFailure(UnknownHostException("token-secret"))
        assertEquals("PLAYBACK_DNS_FAILED", result.code)
        assertFalse(result.safeReport().contains("token-secret"))
    }

    @Test fun unknownFailureDoesNotExposeRawMessage() {
        val result = PlaybackDiagnostic.fromPreparationFailure(IllegalStateException("token=abc"))
        assertEquals("PLAYBACK_PREPARE_FAILED", result.code)
        assertFalse(result.safeReport().contains("token=abc"))
    }

    @Test fun decoderFailureIsDistinctFromTransportFailure() {
        assertEquals("PLAYER_DECODER_UNSUPPORTED", PlaybackDiagnostic.playerFailure(true).code)
        assertEquals("PLAYER_PLAYBACK_FAILED", PlaybackDiagnostic.playerFailure(false).code)
    }
}
