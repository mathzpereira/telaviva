package app.telaviva.androidtv.playback

import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.domain.CameraGridState
import app.telaviva.androidtv.domain.CameraLayout
import java.net.URI
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorTest {
    @Test
    fun gridNeverCreatesMoreThanFourPlayers() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)

        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        assertEquals(CameraGridState.SLOT_COUNT, factory.players.size)
        assertTrue(factory.players.count { !it.released } <= CameraGridState.SLOT_COUNT)
    }

    @Test
    fun gridMutesAudioAndReleasesHiddenPlayers() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        val originalPlayers = factory.players.toList()
        coordinator.showGrid(grid(CameraLayout.ONE), cameras)

        assertFalse(originalPlayers.any { it.preparations.first().audioEnabled })
        assertEquals(3, originalPlayers.count { it.released })
        assertEquals(1, factory.players.count { !it.released })
    }

    @Test
    fun fullscreenKeepsOnlyMainStreamWithAudio() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)
        coordinator.showGrid(grid(CameraLayout.TWO), cameras)

        coordinator.openFullscreen(cameras.getValue("camera-1"))

        val livePlayers = factory.players.filterNot(FakeCameraPlayer::released)
        assertEquals(1, livePlayers.size)
        assertEquals(URI("rtsp://192.168.1.11:554/main-1"), livePlayers.single().preparations.last().uri)
        assertTrue(livePlayers.single().preparations.last().audioEnabled)
    }

    @Test
    fun fullscreenFallsBackToSubstreamWhenMainDecoderIsUnsupported() {
        val factory = FakeCameraPlayerFactory()
        val reportedErrors = mutableListOf<Pair<String, CameraTileError>>()
        val coordinator = coordinator(factory) { cameraId, error ->
            reportedErrors.add(cameraId to error)
        }
        coordinator.showGrid(grid(CameraLayout.TWO), cameras)
        coordinator.openFullscreen(cameras.getValue("camera-1"))
        val mainPlayer = factory.players.single { !it.released }
        assertEquals(URI("rtsp://192.168.1.11:554/main-1"), mainPlayer.preparations.last().uri)

        mainPlayer.reportFailure(PlaybackFailure(PlaybackFailureKind.UNSUPPORTED_DECODER))

        val livePlayers = factory.players.filterNot(FakeCameraPlayer::released)
        assertEquals(1, livePlayers.size)
        assertEquals(
            URI("rtsp://192.168.1.11:554/sub-1"),
            livePlayers.single().preparations.last().uri,
        )
        assertTrue(livePlayers.single().preparations.last().audioEnabled)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun fullscreenReportsUnsupportedWhenFallbackStreamAlsoFails() {
        val factory = FakeCameraPlayerFactory()
        val reportedErrors = mutableListOf<Pair<String, CameraTileError>>()
        val coordinator = coordinator(factory) { cameraId, error ->
            reportedErrors.add(cameraId to error)
        }
        coordinator.showGrid(grid(CameraLayout.TWO), cameras)
        coordinator.openFullscreen(cameras.getValue("camera-1"))

        factory.players.single { !it.released }
            .reportFailure(PlaybackFailure(PlaybackFailureKind.UNSUPPORTED_DECODER))
        factory.players.single { !it.released }
            .reportFailure(PlaybackFailure(PlaybackFailureKind.UNSUPPORTED_DECODER))

        assertEquals(listOf("camera-1" to CameraTileError.UNSUPPORTED_STREAM), reportedErrors)
        assertEquals(0, factory.players.count { !it.released })
    }

    @Test
    fun backRestoresPreviousGrid() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)
        coordinator.showGrid(grid(CameraLayout.TWO), cameras)
        coordinator.openFullscreen(cameras.getValue("camera-1"))

        coordinator.closeFullscreen()

        val livePlayers = factory.players.filterNot(FakeCameraPlayer::released)
        assertEquals(2, livePlayers.size)
        assertEquals(
            setOf(
                URI("rtsp://192.168.1.11:554/sub-1"),
                URI("rtsp://192.168.1.12:554/sub-2"),
            ),
            livePlayers.map { it.preparations.last().uri }.toSet(),
        )
        assertTrue(livePlayers.all { it.preparations.last().audioEnabled.not() })
    }

    @Test
    fun cameraCredentialsArePercentEncodedWhenBuildingPlaybackUri() {
        val source = URI("rtsp://192.168.1.20:554/live%20main?channel=1")

        val authenticated = RtspAuthenticatedUri.withCredentials(
            source,
            CameraCredentials("camera@user", "p:a%ss"),
        )

        assertEquals(
            "rtsp://camera%40user:p%3Aa%25ss@192.168.1.20:554/live%20main?channel=1",
            authenticated.toASCIIString(),
        )
    }

    @Test
    fun onePlayerFailureLeavesOtherTilesLive() {
        val factory = FakeCameraPlayerFactory()
        val reportedErrors = mutableListOf<Pair<String, CameraTileError>>()
        val coordinator = coordinator(factory) { cameraId, error ->
            reportedErrors.add(cameraId to error)
        }
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        val failedPlayer = factory.players.single {
            it.preparations.last().uri == URI("rtsp://192.168.1.12:554/sub-2")
        }
        failedPlayer.reportFailure(PlaybackFailure(PlaybackFailureKind.CONNECTION))

        assertEquals(3, factory.players.count { !it.released })
        assertEquals(listOf("camera-2" to CameraTileError.UNAVAILABLE), reportedErrors)
    }

    @Test
    fun backgroundingReleasesEveryPlayer() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        coordinator.onAppBackgrounded()

        assertEquals(0, factory.players.count { !it.released })
        assertEquals(CameraGridState.SLOT_COUNT, factory.players.count(FakeCameraPlayer::released))
    }

    @Test
    fun pendingLoadsDoNotStartPlayersAfterBackgrounding() {
        val factory = FakeCameraPlayerFactory()
        val ioExecutor = QueuedExecutor()
        val coordinator = coordinator(factory, ioExecutor = ioExecutor)
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        coordinator.onAppBackgrounded()
        ioExecutor.runAll()

        assertTrue(factory.players.isEmpty())
    }

    @Test
    fun retryRestartsOnlyTheRequestedCamera() {
        val factory = FakeCameraPlayerFactory()
        val coordinator = coordinator(factory)
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)
        val originalCamera2Player = factory.players.single {
            it.preparations.last().uri == URI("rtsp://192.168.1.12:554/sub-2")
        }
        val otherPlayers = factory.players.filterNot { it === originalCamera2Player }

        coordinator.retry("camera-2")

        assertTrue(originalCamera2Player.released)
        assertTrue(otherPlayers.all { !it.released })
        assertEquals(
            1,
            factory.players.count {
                !it.released && it.preparations.last().uri == URI("rtsp://192.168.1.12:554/sub-2")
            },
        )
    }

    @Test
    fun retryInvalidatesAnOlderPendingLoadForThatCamera() {
        val factory = FakeCameraPlayerFactory()
        val ioExecutor = QueuedExecutor()
        val coordinator = coordinator(factory, ioExecutor = ioExecutor)
        coordinator.showGrid(grid(CameraLayout.FOUR), cameras)

        coordinator.retry("camera-2")
        ioExecutor.runAll()

        assertEquals(CameraGridState.SLOT_COUNT, factory.players.count { !it.released })
        assertEquals(
            1,
            factory.players.count {
                !it.released && it.preparations.last().uri == URI("rtsp://192.168.1.12:554/sub-2")
            },
        )
    }

    private fun coordinator(
        factory: FakeCameraPlayerFactory,
        ioExecutor: Executor = DirectExecutor,
        onTileError: (String, CameraTileError) -> Unit = { _, _ -> },
    ) = CameraPlaybackCoordinator(
        playerFactory = factory,
        credentialProvider = { CameraCredentials("camera-user", "camera-password") },
        ioExecutor = ioExecutor,
        mainExecutor = DirectExecutor,
        onTileError = onTileError,
    )

    private fun grid(layout: CameraLayout) = CameraGridState(
        layout,
        listOf("camera-1", "camera-2", "camera-3", "camera-4"),
    )

    private class Preparation(val uri: URI, val audioEnabled: Boolean)

    private class FakeCameraPlayerFactory : CameraPlayerFactory {
        val players = mutableListOf<FakeCameraPlayer>()

        override fun create(): CameraPlayer = FakeCameraPlayer().also(players::add)
    }

    private class FakeCameraPlayer : CameraPlayer {
        val preparations = mutableListOf<Preparation>()
        var released = false
            private set
        private var failureListener: ((PlaybackFailure) -> Unit)? = null

        override fun attach(view: androidx.media3.ui.PlayerView?) = Unit

        override fun prepare(uri: URI, credentials: CameraCredentials, audioEnabled: Boolean) {
            preparations += Preparation(uri, audioEnabled)
        }

        override fun setAudioEnabled(enabled: Boolean) = Unit

        override fun setFailureListener(listener: ((PlaybackFailure) -> Unit)?) {
            failureListener = listener
        }

        override fun release() {
            released = true
        }

        fun reportFailure(failure: PlaybackFailure) {
            failureListener?.invoke(failure)
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private class QueuedExecutor : Executor {
        private val commands = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            commands.addLast(command)
        }

        fun runAll() {
            while (commands.isNotEmpty()) commands.removeFirst().run()
        }
    }

    companion object {
        private val cameras = (1..4).associate { index ->
            "camera-$index" to CameraConfig(
                id = "camera-$index",
                name = "Camera $index",
                host = "192.168.1.${10 + index}",
                mainRtspUri = "rtsp://192.168.1.${10 + index}:554/main-$index",
                subRtspUri = "rtsp://192.168.1.${10 + index}:554/sub-$index",
            )
        }
    }
}
