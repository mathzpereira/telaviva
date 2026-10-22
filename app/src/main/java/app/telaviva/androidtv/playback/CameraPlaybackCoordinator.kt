package app.telaviva.androidtv.playback

import androidx.media3.ui.PlayerView
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.network.CameraAddressValidator
import java.net.URI
import java.util.concurrent.Executor

class CameraPlaybackCoordinator(
    private val playerFactory: CameraPlayerFactory,
    private val credentialProvider: (String) -> CameraCredentials?,
    private val ioExecutor: Executor,
    private val mainExecutor: Executor,
    private val onTileError: (String, CameraTileError) -> Unit = { _, _ -> },
) {
    private data class GridSnapshot(
        val state: CameraGridState,
        val camerasById: Map<String, CameraConfig>,
    )

    private data class PlaybackSpec(
        val camera: CameraConfig,
        val uri: String,
        val audioEnabled: Boolean,
    )

    private data class PendingPlayback(val spec: PlaybackSpec, val requestId: Long)

    private class Session(
        var spec: PlaybackSpec,
        val player: CameraPlayer,
        var preparationId: Int = 0,
    )

    private sealed interface PreparedCamera {
        data class Ready(val uri: URI, val credentials: CameraCredentials) : PreparedCamera
        data class Failed(val failure: PlaybackFailure) : PreparedCamera
    }

    private val sessions = linkedMapOf<String, Session>()
    private val pending = mutableMapOf<String, PendingPlayback>()
    private val desired = linkedMapOf<String, PlaybackSpec>()
    private val attachedViews = mutableMapOf<String, PlayerView>()
    private var currentGrid: GridSnapshot? = null
    private var gridBeforeFullscreen: GridSnapshot? = null
    private var fullscreenCameraId: String? = null
    private var foreground = true
    private var nextRequestId = 0L

    fun showGrid(state: CameraGridState, camerasById: Map<String, CameraConfig>) {
        mainExecutor.execute {
            foreground = true
            fullscreenCameraId = null
            gridBeforeFullscreen = null
            val snapshot = GridSnapshot(state, camerasById.toMap())
            currentGrid = snapshot
            reconcile(gridPlayback(snapshot))
        }
    }

    fun attach(cameraId: String, view: PlayerView?) {
        mainExecutor.execute {
            if (view == null) attachedViews.remove(cameraId) else attachedViews[cameraId] = view
            sessions[cameraId]?.player?.attach(view)
        }
    }

    fun openFullscreen(camera: CameraConfig) {
        mainExecutor.execute {
            foreground = true
            if (fullscreenCameraId == null) gridBeforeFullscreen = currentGrid
            fullscreenCameraId = camera.id
            reconcile(
                linkedMapOf(
                    camera.id to PlaybackSpec(
                        camera = camera,
                        uri = camera.mainRtspUri,
                        audioEnabled = true,
                    ),
                ),
            )
        }
    }

    fun closeFullscreen() {
        mainExecutor.execute {
            if (fullscreenCameraId == null) return@execute
            fullscreenCameraId = null
            val previousGrid = gridBeforeFullscreen
            gridBeforeFullscreen = null
            if (previousGrid == null) {
                reconcile(emptyMap())
            } else {
                currentGrid = previousGrid
                foreground = true
                reconcile(gridPlayback(previousGrid))
            }
        }
    }

    fun onAppBackgrounded() {
        mainExecutor.execute {
            foreground = false
            desired.clear()
            pending.clear()
            releaseAllPlayers()
        }
    }

    fun retry(cameraId: String) {
        mainExecutor.execute {
            val spec = desired[cameraId] ?: return@execute
            pending.remove(cameraId)
            releasePlayer(cameraId)
            requestPlayback(spec)
        }
    }

    private fun gridPlayback(snapshot: GridSnapshot): LinkedHashMap<String, PlaybackSpec> {
        val result = linkedMapOf<String, PlaybackSpec>()
        snapshot.state.slots
            .take(snapshot.state.layout.slotCount)
            .filterNotNull()
            .distinct()
            .forEach { cameraId ->
                val camera = snapshot.camerasById[cameraId]
                if (camera == null) {
                    onTileError(cameraId, CameraTileError.UNAVAILABLE)
                } else {
                    result[cameraId] = PlaybackSpec(
                        camera = camera,
                        uri = camera.subRtspUri?.takeIf(String::isNotBlank) ?: camera.mainRtspUri,
                        audioEnabled = false,
                    )
                }
            }
        return result
    }

    private fun reconcile(nextDesired: Map<String, PlaybackSpec>) {
        desired.clear()
        desired.putAll(nextDesired)

        pending.keys.toList().forEach { cameraId ->
            if (desired[cameraId] != pending[cameraId]?.spec) pending.remove(cameraId)
        }
        sessions.keys.toList().forEach { cameraId ->
            if (cameraId !in desired) releasePlayer(cameraId)
        }

        desired.forEach { (cameraId, spec) ->
            val session = sessions[cameraId]
            if (session?.spec == spec) return@forEach
            if (session != null && session.spec.camera == spec.camera && session.spec.uri == spec.uri) {
                session.spec = spec
                session.player.setAudioEnabled(spec.audioEnabled)
                return@forEach
            }
            requestPlayback(spec)
        }
    }

    private fun requestPlayback(spec: PlaybackSpec) {
        val cameraId = spec.camera.id
        if (!foreground || pending[cameraId]?.spec == spec) return
        val requestId = ++nextRequestId
        pending[cameraId] = PendingPlayback(spec, requestId)

        ioExecutor.execute {
            val result = try {
                val validatedUri = CameraAddressValidator.validate(spec.uri).uriWithoutUserInfo
                val credentials = credentialProvider(cameraId)
                    ?: throw MissingCameraCredentialsException()
                PreparedCamera.Ready(validatedUri, credentials)
            } catch (_: MissingCameraCredentialsException) {
                PreparedCamera.Failed(PlaybackFailure(PlaybackFailureKind.AUTHENTICATION))
            } catch (_: Exception) {
                PreparedCamera.Failed(PlaybackFailure(PlaybackFailureKind.CONNECTION))
            }

            mainExecutor.execute completion@{
                if (!foreground || desired[cameraId] != spec || pending[cameraId]?.requestId != requestId) {
                    return@completion
                }
                pending.remove(cameraId)

                when (result) {
                    is PreparedCamera.Failed -> {
                        releasePlayer(cameraId)
                        onTileError(cameraId, PlaybackFailureMapper.map(result.failure))
                    }
                    is PreparedCamera.Ready -> preparePlayer(cameraId, spec, result)
                }
            }
        }
    }

    private fun preparePlayer(cameraId: String, spec: PlaybackSpec, prepared: PreparedCamera.Ready) {
        val session = sessions[cameraId] ?: try {
            val player = playerFactory.create()
            Session(spec, player).also { sessions[cameraId] = it }
        } catch (_: Exception) {
            onTileError(cameraId, CameraTileError.UNAVAILABLE)
            return
        }
        session.spec = spec
        session.preparationId++
        val preparationId = session.preparationId
        val player = session.player

        try {
            player.setFailureListener { failure ->
                mainExecutor.execute {
                    handlePlayerFailure(cameraId, player, preparationId, failure)
                }
            }
            player.attach(attachedViews[cameraId])
            player.prepare(prepared.uri, prepared.credentials, spec.audioEnabled)
        } catch (_: Exception) {
            handlePlayerFailure(
                cameraId,
                player,
                preparationId,
                PlaybackFailure(PlaybackFailureKind.CONNECTION),
            )
        }
    }

    private fun handlePlayerFailure(
        cameraId: String,
        player: CameraPlayer,
        preparationId: Int,
        failure: PlaybackFailure,
    ) {
        val session = sessions[cameraId] ?: return
        if (session.player !== player || session.preparationId != preparationId) return
        releasePlayer(cameraId)
        onTileError(cameraId, PlaybackFailureMapper.map(failure))
    }

    private fun releasePlayer(cameraId: String) {
        val session = sessions.remove(cameraId) ?: return
        session.player.setFailureListener(null)
        session.player.attach(null)
        session.player.release()
    }

    private fun releaseAllPlayers() {
        sessions.keys.toList().forEach(::releasePlayer)
    }

    private class MissingCameraCredentialsException : Exception()
}
