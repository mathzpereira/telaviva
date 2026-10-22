package app.telaviva.androidtv.domain

import org.junit.Assert.assertFalse
import org.junit.Test

class CameraCredentialsTest {
    @Test
    fun toStringRedactsUsernameAndPassword() {
        val username = "sensitive-camera-user"
        val password = "sensitive-camera-password"

        val description = CameraCredentials(username, password).toString()

        assertFalse(description.contains(username))
        assertFalse(description.contains(password))
    }
}
