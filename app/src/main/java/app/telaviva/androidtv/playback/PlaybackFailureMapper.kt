package app.telaviva.androidtv.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.rtsp.RtspMediaSource

enum class PlaybackFailureKind {
    AUTHENTICATION,
    CONNECTION,
    UNSUPPORTED_DECODER,
}

class PlaybackFailure(
    val kind: PlaybackFailureKind,
    @Suppress("unused") private val source: Throwable? = null,
) {
    override fun toString(): String = "PlaybackFailure(kind=$kind)"
}

enum class CameraTileError(val userMessage: String) {
    UNAVAILABLE("Câmera indisponível"),
    AUTHENTICATION_FAILED("Usuário ou senha incorretos"),
    UNSUPPORTED_STREAM("Formato de vídeo incompatível"),
}

object PlaybackFailureMapper {
    fun map(error: PlaybackFailure): CameraTileError = when (error.kind) {
        PlaybackFailureKind.AUTHENTICATION -> CameraTileError.AUTHENTICATION_FAILED
        PlaybackFailureKind.CONNECTION -> CameraTileError.UNAVAILABLE
        PlaybackFailureKind.UNSUPPORTED_DECODER -> CameraTileError.UNSUPPORTED_STREAM
    }

    @UnstableApi
    fun from(error: PlaybackException): PlaybackFailure {
        return from(error.errorCode, error)
    }

    @UnstableApi
    internal fun from(errorCode: Int, source: Throwable?): PlaybackFailure {
        val kind = when {
            source != null && hasRtspAuthenticationFailure(source) -> PlaybackFailureKind.AUTHENTICATION
            errorCode in DECODER_ERROR_CODES -> PlaybackFailureKind.UNSUPPORTED_DECODER
            else -> PlaybackFailureKind.CONNECTION
        }
        return PlaybackFailure(kind, source)
    }

    @UnstableApi
    private fun hasRtspAuthenticationFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is RtspMediaSource.RtspPlaybackException &&
                current.message in RTSP_AUTHENTICATION_MESSAGES) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private val DECODER_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )
    private val RTSP_AUTHENTICATION_MESSAGES = setOf(
        "OPTIONS 401",
        "DESCRIBE 401",
        "SETUP 401",
        "PLAY 401",
    )
    private const val MAX_CAUSE_DEPTH = 16
}
