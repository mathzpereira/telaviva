package app.telaviva.androidtv.playback

import android.os.SystemClock
import android.util.Log

sealed interface PlaybackMetricsEvent {
    data class FirstFrame(val durationMillis: Long, val visiblePlayerCount: Int) : PlaybackMetricsEvent

    data class VisiblePlayerCount(val count: Int) : PlaybackMetricsEvent
}

fun interface PlaybackMetricsLogger {
    fun record(event: PlaybackMetricsEvent)
}

class PlaybackMetricsListener(
    private val logger: PlaybackMetricsLogger = AndroidPlaybackMetricsLogger,
    private val debugBuild: Boolean,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var playbackStartedAt: Long? = null
    private var firstFrameReported = false
    private var lastVisiblePlayerCount: Int? = null

    fun onPlaybackStarted() {
        if (!debugBuild) return
        playbackStartedAt = nowMillis()
        firstFrameReported = false
    }

    fun onFirstFrameRendered(visiblePlayerCount: Int) {
        if (!debugBuild || firstFrameReported) return
        val startedAt = playbackStartedAt ?: return
        val duration = (nowMillis() - startedAt).coerceAtLeast(0L)
        logger.record(PlaybackMetricsEvent.FirstFrame(duration, visiblePlayerCount))
        firstFrameReported = true
    }

    fun onVisiblePlayerCountChanged(count: Int) {
        if (!debugBuild || count < 0 || lastVisiblePlayerCount == count) return
        lastVisiblePlayerCount = count
        logger.record(PlaybackMetricsEvent.VisiblePlayerCount(count))
    }
}

private object AndroidPlaybackMetricsLogger : PlaybackMetricsLogger {
    override fun record(event: PlaybackMetricsEvent) {
        val message = when (event) {
            is PlaybackMetricsEvent.FirstFrame ->
                "first_frame_ms=${event.durationMillis} visible_players=${event.visiblePlayerCount}"
            is PlaybackMetricsEvent.VisiblePlayerCount -> "visible_players=${event.count}"
        }
        Log.d(TAG, message)
    }

    private const val TAG = "TelaVivaPlayback"
}
