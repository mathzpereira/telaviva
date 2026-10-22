package app.telaviva.androidtv.network

import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CameraAddressValidatorTest {
    @Test
    fun acceptsCredentialFreeLocalRtspUri() {
        val validated = CameraAddressValidator.validate("rtsp://192.168.1.50:554/live/main?channel=1")

        assertEquals(URI("rtsp://192.168.1.50:554/live/main?channel=1"), validated.uriWithoutUserInfo)
        assertEquals(InetAddress.getByName("192.168.1.50"), validated.resolvedAddress)
        assertNull(validated.uriWithoutUserInfo.userInfo)
    }

    @Test
    fun rejectsMalformedWrongSchemeAndEmbeddedCredentials() {
        val invalidUris = listOf(
            "not a uri",
            "http://192.168.1.50/live/main",
            "rtsp://viewer:secret@192.168.1.50/live/main",
        )

        invalidUris.forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) {
                CameraAddressValidator.validate(uri)
            }
        }
    }

    @Test
    fun rejectsPublicResolvedAddress() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraAddressValidator.validate("rtsp://8.8.8.8/live/main")
        }
    }
}
