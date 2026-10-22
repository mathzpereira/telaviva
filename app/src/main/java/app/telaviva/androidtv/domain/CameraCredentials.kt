package app.telaviva.androidtv.domain

data class CameraCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "CameraCredentials(username=[REDACTED], password=[REDACTED])"
}
