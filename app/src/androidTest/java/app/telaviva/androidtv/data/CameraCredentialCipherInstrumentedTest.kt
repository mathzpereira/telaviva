package app.telaviva.androidtv.data

import java.security.GeneralSecurityException
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraCredentialCipherInstrumentedTest {
    @Test
    fun encryptsWithDistinctIvAndRejectsTampering() {
        val plaintext = "camera-user\u0000camera-pass".toByteArray(Charsets.UTF_8)

        val first: ByteArray = CameraCredentialCipher.encrypt(plaintext)
        val second: ByteArray = CameraCredentialCipher.encrypt(plaintext)

        assertFalse(Arrays.equals(first, second))
        assertArrayEquals(plaintext, CameraCredentialCipher.decrypt(first))

        val tampered = second.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            CameraCredentialCipher.decrypt(tampered)
        }
    }
}
