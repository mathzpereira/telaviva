package app.telaviva.androidtv.data

import android.content.ContentValues
import android.content.Context
import app.telaviva.androidtv.domain.CameraConfig
import app.telaviva.androidtv.domain.CameraCredentials
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class CameraRepository(context: Context) {
    private val database = CameraDatabase(context)

    fun list(): List<CameraConfig> {
        val cameras = mutableListOf<CameraConfig>()
        database.readableDatabase.query(
            "cameras",
            arrayOf("id", "name", "host", "main_rtsp_uri", "sub_rtsp_uri"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cameras += CameraConfig(
                    id = cursor.getString(0),
                    name = cursor.getString(1),
                    host = cursor.getString(2),
                    mainRtspUri = cursor.getString(3),
                    subRtspUri = cursor.getString(4),
                )
            }
        }
        return cameras
    }

    fun credentials(id: String): CameraCredentials? {
        val encrypted = database.readableDatabase.query(
            "cameras",
            arrayOf("credentials_ciphertext"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        } ?: return null

        return try {
            val plaintext = CameraCredentialCipher.decrypt(encrypted)
            try {
                decodeCredentials(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    fun save(camera: CameraConfig, credentials: CameraCredentials) {
        val plaintext = encodeCredentials(credentials)
        val encrypted = try {
            CameraCredentialCipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }

        try {
            val values = ContentValues().apply {
                put("id", camera.id)
                put("name", camera.name)
                put("host", camera.host)
                put("main_rtsp_uri", camera.mainRtspUri)
                put("sub_rtsp_uri", camera.subRtspUri)
                put("credentials_ciphertext", encrypted)
            }
            val result = database.writableDatabase.insertWithOnConflict(
                "cameras",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            )
            check(result != -1L) { "Could not save camera profile" }
        } finally {
            encrypted.fill(0)
        }
    }

    fun delete(id: String) {
        database.writableDatabase.delete("cameras", "id = ?", arrayOf(id))
    }

    private fun encodeCredentials(credentials: CameraCredentials): ByteArray {
        val username = credentials.username.toByteArray(StandardCharsets.UTF_8)
        val password = credentials.password.toByteArray(StandardCharsets.UTF_8)
        return try {
            ByteBuffer.allocate(Int.SIZE_BYTES * 2 + username.size + password.size)
                .putInt(username.size)
                .put(username)
                .putInt(password.size)
                .put(password)
                .array()
        } finally {
            username.fill(0)
            password.fill(0)
        }
    }

    private fun decodeCredentials(value: ByteArray): CameraCredentials {
        require(value.size >= Int.SIZE_BYTES * 2) { "Camera credentials are malformed" }

        val buffer = ByteBuffer.wrap(value)
        val usernameSize = buffer.int
        require(usernameSize >= 0 && usernameSize <= buffer.remaining() - Int.SIZE_BYTES) {
            "Camera username is malformed"
        }
        val usernameBytes = ByteArray(usernameSize).also(buffer::get)
        val passwordSize = buffer.int
        require(passwordSize >= 0 && passwordSize == buffer.remaining()) {
            "Camera password is malformed"
        }
        val passwordBytes = ByteArray(passwordSize).also(buffer::get)

        return try {
            CameraCredentials(
                username = String(usernameBytes, StandardCharsets.UTF_8),
                password = String(passwordBytes, StandardCharsets.UTF_8),
            )
        } finally {
            usernameBytes.fill(0)
            passwordBytes.fill(0)
        }
    }
}
