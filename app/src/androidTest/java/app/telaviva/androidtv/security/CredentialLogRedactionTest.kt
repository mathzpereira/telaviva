package app.telaviva.androidtv.security

import app.telaviva.androidtv.playback.PlaybackFailure
import app.telaviva.androidtv.playback.PlaybackFailureKind
import app.telaviva.androidtv.playback.PlaybackMetricsEvent
import app.telaviva.androidtv.playback.PlaybackMetricsListener
import app.telaviva.androidtv.playback.PlaybackMetricsLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialLogRedactionTest {
    @Test
    fun noLogOutputContainsCameraUsernamePasswordOrRtspUserInfo() {
        val username = "private-camera-user"
        val password = "private-camera-password"
        val credentialUri = "rtsp://$username:$password@192.168.1.51:554/live"
        val events = mutableListOf<PlaybackMetricsEvent>()
        var nowMillis = 2_000L
        val listener = PlaybackMetricsListener(
            logger = PlaybackMetricsLogger(events::add),
            debugBuild = true,
            nowMillis = { nowMillis },
        )
        listener.onVisiblePlayerCountChanged(4)
        val failure = PlaybackFailure(
            PlaybackFailureKind.CONNECTION,
            IllegalStateException("$username $password $credentialUri"),
        )

        listener.onPlaybackStarted()
        nowMillis += 735L
        listener.onFirstFrameRendered(visiblePlayerCount = 4)
        listener.onVisiblePlayerCountChanged(3)

        val output = "${events.joinToString()} $failure"
        assertTrue(events.contains(PlaybackMetricsEvent.FirstFrame(735L, 4)))
        assertTrue(events.contains(PlaybackMetricsEvent.VisiblePlayerCount(3)))
        assertFalse(output.contains(username))
        assertFalse(output.contains(password))
        assertFalse(output.contains(credentialUri))
    }

    @Test
    fun releaseBuildDoesNotRecordPlaybackMetrics() {
        val events = mutableListOf<PlaybackMetricsEvent>()
        val listener = PlaybackMetricsListener(
            logger = PlaybackMetricsLogger(events::add),
            debugBuild = false,
            nowMillis = { 1_000L },
        )

        listener.onVisiblePlayerCountChanged(4)
        listener.onPlaybackStarted()
        listener.onFirstFrameRendered(visiblePlayerCount = 4)

        assertTrue(events.isEmpty())
    }
}
