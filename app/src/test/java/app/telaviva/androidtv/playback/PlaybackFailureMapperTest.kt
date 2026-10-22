package app.telaviva.androidtv.playback

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackFailureMapperTest {
    @Test
    fun authenticationAndUnsupportedCodecMapToDistinctTileErrors() {
        val authenticationError = PlaybackFailureMapper.map(
            PlaybackFailure(PlaybackFailureKind.AUTHENTICATION),
        )
        val unsupportedError = PlaybackFailureMapper.map(
            PlaybackFailure(PlaybackFailureKind.UNSUPPORTED_DECODER),
        )

        assertEquals(CameraTileError.AUTHENTICATION_FAILED, authenticationError)
        assertEquals(CameraTileError.UNSUPPORTED_STREAM, unsupportedError)
        assertFalse(authenticationError.userMessage == unsupportedError.userMessage)
    }

    @Test
    fun errorTextNeverContainsCredentials() {
        val failure = PlaybackFailure(
            PlaybackFailureKind.CONNECTION,
            IllegalStateException("Failed to read rtsp://camera-user:camera-password@192.168.1.20/live"),
        )

        val error = PlaybackFailureMapper.map(failure)

        assertFalse(error.userMessage.contains("camera-user"))
        assertFalse(error.userMessage.contains("camera-password"))
        assertFalse(error.userMessage.contains("rtsp://"))
        assertFalse(failure.toString().contains("camera-password"))
    }

    @Test
    fun media3AuthenticationDecoderAndConnectionErrorsAreClassified() {
        val authentication = PlaybackFailureMapper.from(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            RtspMediaSource.RtspPlaybackException("DESCRIBE 401"),
        )
        val unsupportedDecoder = PlaybackFailureMapper.from(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            null,
        )
        val connection = PlaybackFailureMapper.from(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            null,
        )

        assertEquals(PlaybackFailureKind.AUTHENTICATION, authentication.kind)
        assertEquals(PlaybackFailureKind.UNSUPPORTED_DECODER, unsupportedDecoder.kind)
        assertEquals(PlaybackFailureKind.CONNECTION, connection.kind)
    }
}
