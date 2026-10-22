package app.telaviva.androidtv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRepositoryInstrumentedTest {
    @Test
    fun saveListDeleteDoesNotStorePlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = CameraRepository(context)
        val camera = CameraConfig(
            id = "storage-security-check",
            name = "Sala",
            host = "192.168.1.20",
            mainRtspUri = "rtsp://192.168.1.20/live/main",
            subRtspUri = null,
        )
        val username = "camera-user-plaintext"
        val password = "camera-password-plaintext"
        val credentials = CameraCredentials(username, password)

        repository.delete(camera.id)
        repository.save(camera, credentials)

        val savedCameras: List<CameraConfig> = repository.list()
        val savedCredentials: CameraCredentials? = repository.credentials(camera.id)
        assertEquals(listOf(camera), savedCameras)
        assertEquals(credentials, savedCredentials)

        val database = context.openOrCreateDatabase(
            CameraDatabase.DATABASE_NAME,
            Context.MODE_PRIVATE,
            null,
        )
        val cursor = database.rawQuery(
            "SELECT credentials_ciphertext FROM cameras WHERE id = ?",
            arrayOf(camera.id),
        )
        try {
            require(cursor.moveToFirst())
            val encryptedBlob = cursor.getBlob(0)
            assertFalse(encryptedBlob.containsBytes(username.toByteArray(Charsets.UTF_8)))
            assertFalse(encryptedBlob.containsBytes(password.toByteArray(Charsets.UTF_8)))
        } finally {
            cursor.close()
            database.close()
        }

        repository.delete(camera.id)

        val remainingCameras: List<CameraConfig> = repository.list()
        val remainingCredentials: CameraCredentials? = repository.credentials(camera.id)
        assertFalse(remainingCameras.any { it.id == camera.id })
        assertNull(remainingCredentials)
    }
}

private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset ->
            this[start + offset] == needle[offset]
        }
    }
