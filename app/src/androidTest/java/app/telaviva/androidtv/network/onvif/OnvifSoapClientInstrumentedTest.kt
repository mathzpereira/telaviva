package app.telaviva.androidtv.network.onvif

import app.telaviva.androidtv.domain.CameraCredentials
import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OnvifSoapClientInstrumentedTest {
    @Test
    fun getProfilesUsesCheckedMediaServiceAndKeepsPasswordOutOfSoapBody() {
        val transport = FakeOnvifHttpTransport(
            capabilitiesResponse("http://192.168.1.20/onvif/media_service"),
            profilesResponse,
        )
        val credentials = CameraCredentials("camera-user", "camera-password")

        val profiles = OnvifSoapClient(transport).getProfiles(endpoint, credentials)

        assertEquals("main", profiles.single().token)
        assertEquals(
            listOf(
                URI("http://192.168.1.20/onvif/device_service"),
                URI("http://192.168.1.20/onvif/media_service"),
            ),
            transport.requests.map(Request::uri),
        )
        assertTrue(transport.requests.all { it.envelope.contains("camera-user") })
        assertFalse(transport.requests.any { it.envelope.contains("camera-password") })
    }

    @Test
    fun getStreamUriReturnsOnlyLocalRtspAddresses() {
        val transport = FakeOnvifHttpTransport(
            capabilitiesResponse("http://192.168.1.20/onvif/media_service"),
            streamUriResponse("rtsp://192.168.1.20:554/live/main"),
        )

        val streamUri = OnvifSoapClient(transport).getStreamUri(endpoint, "main", credentials)

        assertEquals(URI("rtsp://192.168.1.20:554/live/main"), streamUri)
    }

    @Test
    fun rejectsPublicStreamUriWithoutReturningTheUriInTheError() {
        val transport = FakeOnvifHttpTransport(
            capabilitiesResponse("http://192.168.1.20/onvif/media_service"),
            streamUriResponse("rtsp://8.8.8.8/live/main"),
        )

        val error = assertThrows(OnvifException::class.java) {
            OnvifSoapClient(transport).getStreamUri(endpoint, "main", credentials)
        }

        assertEquals(OnvifFailure.NON_LOCAL_ADDRESS, error.reason)
        assertFalse(error.message.orEmpty().contains("8.8.8.8"))
    }

    @Test
    fun digestAuthorizationRejectsControlCharactersInChallengeAndUsername() {
        val entity = "<soap/>".toByteArray(Charsets.UTF_8)
        val injectedChallenge = "Digest realm=\"camera\r\nX-Injected: yes\", nonce=\"n\", qop=\"auth\""

        val challengeError = assertThrows(OnvifException::class.java) {
            HttpDigestAuthorization.create(injectedChallenge, digestUri, entity, credentials)
        }
        val usernameError = assertThrows(OnvifException::class.java) {
            HttpDigestAuthorization.create(
                "Digest realm=\"camera\", nonce=\"n\", qop=\"auth\"",
                digestUri,
                entity,
                CameraCredentials("user\r\nX-Injected: yes", "password"),
            )
        }

        assertEquals(OnvifFailure.UNSUPPORTED_AUTHENTICATION, challengeError.reason)
        assertEquals(OnvifFailure.UNSUPPORTED_AUTHENTICATION, usernameError.reason)
    }

    @Test
    fun digestAuthorizationKeepsSupportedChallengesUsable() {
        val authorization = HttpDigestAuthorization.create(
            "Digest realm=\"camera\", nonce=\"nonce-value\", qop=\"auth,auth-int\", algorithm=MD5",
            digestUri,
            "<soap/>".toByteArray(Charsets.UTF_8),
            credentials,
        )

        assertTrue(authorization.startsWith("Digest username=\"camera-user\""))
        assertTrue(authorization.contains(", qop=auth,"))
        assertFalse(authorization.contains("camera-password"))
    }

    private data class Request(val uri: URI, val action: String, val envelope: String)

    private class FakeOnvifHttpTransport(vararg responses: String) : OnvifHttpTransport {
        private val pendingResponses = java.util.ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()

        override fun post(
            uri: URI,
            action: String,
            envelope: String,
            credentials: CameraCredentials,
        ): String {
            requests += Request(uri, action, envelope)
            return pendingResponses.removeFirst()
        }
    }

    companion object {
        private val endpoint = OnvifEndpoint(
            deviceAddress = InetAddress.getByName("192.168.1.20"),
            xAddr = URI("http://192.168.1.20/onvif/device_service"),
        )
        private val digestUri = URI("http://192.168.1.20/onvif/device_service")
        private val credentials = CameraCredentials("camera-user", "camera-password")
        private val profilesResponse = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><trt:GetProfilesResponse><trt:Profiles token="main">
                <tt:VideoEncoderConfiguration><tt:Encoding>H264</tt:Encoding>
                  <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>
                </tt:VideoEncoderConfiguration>
              </trt:Profiles></trt:GetProfilesResponse></s:Body>
            </s:Envelope>
        """.trimIndent()

        private fun capabilitiesResponse(mediaXAddr: String) = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media>
                <tt:XAddr>$mediaXAddr</tt:XAddr>
              </tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse></s:Body>
            </s:Envelope>
        """.trimIndent()

        private fun streamUriResponse(streamUri: String) = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><trt:GetStreamUriResponse><trt:MediaUri>
                <tt:Uri>$streamUri</tt:Uri>
              </trt:MediaUri></trt:GetStreamUriResponse></s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}
