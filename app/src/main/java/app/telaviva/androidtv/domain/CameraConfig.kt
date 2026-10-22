package app.telaviva.androidtv.domain

data class CameraConfig(
    val id: String,
    val name: String,
    val host: String,
    val mainRtspUri: String,
    val subRtspUri: String?,
)
