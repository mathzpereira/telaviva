package app.telaviva.androidtv.network.onvif

import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsDiscoveryParserTest {
    @Test
    fun ignoresMalformedAndDuplicateProbeMatches() {
        val sender = InetAddress.getByName("192.168.1.10")
        assertTrue(WsDiscoveryParser.parse("<broken", sender).isEmpty())

        val endpoints = WsDiscoveryParser.parse(
            """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
              <s:Body><d:ProbeMatches>
                <d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:camera-1</a:Address>
                  </a:EndpointReference><d:XAddrs>http://192.168.1.20/onvif/device_service</d:XAddrs>
                </d:ProbeMatch>
                <d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:camera-1</a:Address>
                  </a:EndpointReference><d:XAddrs>http://192.168.1.20/onvif/device_service</d:XAddrs>
                </d:ProbeMatch>
                <d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:external</a:Address>
                  </a:EndpointReference><d:XAddrs>http://8.8.8.8/device_service</d:XAddrs>
                </d:ProbeMatch>
              </d:ProbeMatches></s:Body>
            </s:Envelope>
            """.trimIndent(),
            sender,
        )

        assertEquals(1, endpoints.size)
        assertEquals(sender, endpoints.single().deviceAddress)
        assertEquals(URI("http://192.168.1.20/onvif/device_service"), endpoints.single().xAddr)
    }
}
