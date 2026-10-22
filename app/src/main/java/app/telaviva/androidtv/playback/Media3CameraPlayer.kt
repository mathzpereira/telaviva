package app.telaviva.androidtv.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import app.telaviva.androidtv.domain.CameraCredentials
import java.net.URI
import java.nio.charset.StandardCharsets

interface CameraPlayer {
    fun attach(view: PlayerView?)
    fun prepare(uri: URI, credentials: CameraCredentials, audioEnabled: Boolean)
    fun setVisiblePlayerCount(count: Int) = Unit
    fun setAudioEnabled(enabled: Boolean)
    fun setFailureListener(listener: ((PlaybackFailure) -> Unit)?)
    fun release()
}

fun interface CameraPlayerFactory {
    fun create(): CameraPlayer
}

class Media3CameraPlayerFactory(context: Context) : CameraPlayerFactory {
    private val applicationContext = context.applicationContext
    private val debugBuild = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @OptIn(UnstableApi::class)
    override fun create(): CameraPlayer = Media3CameraPlayer(
        applicationContext,
        PlaybackMetricsListener(debugBuild = debugBuild),
    )
}

@UnstableApi
private class Media3CameraPlayer(
    context: Context,
    private val metrics: PlaybackMetricsListener,
) : CameraPlayer {
    private val player = ExoPlayer.Builder(context).build()
    private var attachedView: PlayerView? = null
    private var failureListener: ((PlaybackFailure) -> Unit)? = null
    private var udpFallbackUsed = false
    private var visiblePlayerCount = 0

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            metrics.onFirstFrameRendered(visiblePlayerCount)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!udpFallbackUsed && isTcpTransportFailure(error)) {
                val currentItem = player.currentMediaItem
                if (currentItem != null) {
                    udpFallbackUsed = true
                    try {
                        player.setMediaSource(createRtspMediaSource(currentItem, forceTcp = false))
                        player.prepare()
                        player.play()
                        return
                    } catch (_: RuntimeException) {
                        // Report the original failure through the same safe classification path.
                    }
                }
            }
            failureListener?.invoke(PlaybackFailureMapper.from(error))
        }
    }

    init {
        player.addListener(playerListener)
    }

    override fun attach(view: PlayerView?) {
        if (attachedView === view) return
        attachedView?.let { oldView ->
            if (oldView.player === player) oldView.player = null
        }
        attachedView = view
        view?.apply {
            useController = false
            player = this@Media3CameraPlayer.player
        }
    }

    override fun prepare(uri: URI, credentials: CameraCredentials, audioEnabled: Boolean) {
        udpFallbackUsed = false
        player.volume = if (audioEnabled) AUDIO_VOLUME else MUTED_VOLUME
        val authenticatedUri = RtspAuthenticatedUri.withCredentials(uri, credentials)
        val mediaItem = MediaItem.fromUri(authenticatedUri.toASCIIString())
        player.setMediaSource(createRtspMediaSource(mediaItem, forceTcp = true))
        metrics.onPlaybackStarted()
        player.prepare()
        player.play()
    }

    override fun setVisiblePlayerCount(count: Int) {
        visiblePlayerCount = count.coerceAtLeast(0)
        metrics.onVisiblePlayerCountChanged(visiblePlayerCount)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        player.volume = if (enabled) AUDIO_VOLUME else MUTED_VOLUME
    }

    override fun setFailureListener(listener: ((PlaybackFailure) -> Unit)?) {
        failureListener = listener
    }

    override fun release() {
        setFailureListener(null)
        attach(null)
        player.removeListener(playerListener)
        player.release()
    }

    private fun createRtspMediaSource(mediaItem: MediaItem, forceTcp: Boolean) =
        RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setDebugLoggingEnabled(false)
            .createMediaSource(mediaItem)

    private fun isTcpTransportFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is RtspMediaSource.RtspPlaybackException &&
                current.message == TCP_SETUP_UNSUPPORTED_TRANSPORT) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private companion object {
        const val AUDIO_VOLUME = 1f
        const val MUTED_VOLUME = 0f
        const val TCP_SETUP_UNSUPPORTED_TRANSPORT = "SETUP 461"
        const val MAX_CAUSE_DEPTH = 16
    }
}

internal object RtspAuthenticatedUri {
    fun withCredentials(uri: URI, credentials: CameraCredentials): URI {
        require(uri.scheme.equals(RTSP_SCHEME, ignoreCase = true) && uri.rawUserInfo == null) {
            "A credential-free RTSP URI is required"
        }
        val authority = uri.rawAuthority ?: throw IllegalArgumentException("RTSP host is required")
        val username = encodeUserInfo(credentials.username)
        val password = encodeUserInfo(credentials.password)
        val authenticated = buildString {
            append(uri.scheme)
            append("://")
            append(username)
            append(':')
            append(password)
            append('@')
            append(authority)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        return try {
            URI(authenticated)
        } catch (_: Exception) {
            throw IllegalArgumentException("Could not construct the RTSP playback address")
        }
    }

    private fun encodeUserInfo(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            buildString(bytes.size) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and BYTE_MASK
                    if (value in 'a'.code..'z'.code ||
                        value in 'A'.code..'Z'.code ||
                        value in '0'.code..'9'.code ||
                        value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code) {
                        append(value.toChar())
                    } else {
                        append('%')
                        append(HEX[value ushr 4])
                        append(HEX[value and 0x0f])
                    }
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private const val RTSP_SCHEME = "rtsp"
    private const val BYTE_MASK = 0xff
    private const val HEX = "0123456789ABCDEF"
}
