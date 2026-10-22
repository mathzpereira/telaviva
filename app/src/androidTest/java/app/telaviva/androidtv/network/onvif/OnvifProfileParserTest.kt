package app.telaviva.androidtv.network.onvif

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifProfileParserTest {
    @Test
    fun audioEncodingDoesNotReplaceVideoEncoding() {
        val result = OnvifProfileParser.parseProfiles(
            """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><trt:GetProfilesResponse><trt:Profiles token="profile-av">
                <tt:VideoEncoderConfiguration>
                  <tt:Encoding>H264</tt:Encoding>
                  <tt:Resolution><tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>
                </tt:VideoEncoderConfiguration>
                <tt:AudioEncoderConfiguration><tt:Encoding>AAC</tt:Encoding></tt:AudioEncoderConfiguration>
              </trt:Profiles></trt:GetProfilesResponse></s:Body>
            </s:Envelope>
            """.trimIndent(),
        )

        val profile = (result as OnvifParseResult.Success).value.single()
        assertEquals("H264", profile.encoding)
        assertEquals(1920, profile.width)
        assertEquals(1080, profile.height)
    }

    @Test
    fun parsesProfileTokensEncodingAndResolution() {
        val result = OnvifProfileParser.parseProfiles(
            """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><trt:GetProfilesResponse>
                <trt:Profiles token="profile-main">
                  <tt:Name>Main</tt:Name><tt:VideoEncoderConfiguration>
                    <tt:Encoding>H264</tt:Encoding><tt:Resolution>
                      <tt:Width>1920</tt:Width><tt:Height>1080</tt:Height>
                    </tt:Resolution>
                  </tt:VideoEncoderConfiguration>
                </trt:Profiles>
                <trt:Profiles token="profile-sub">
                  <tt:VideoEncoderConfiguration><tt:Encoding>H264</tt:Encoding>
                    <tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height>
                    </tt:Resolution></tt:VideoEncoderConfiguration>
                </trt:Profiles>
              </trt:GetProfilesResponse></s:Body>
            </s:Envelope>
            """.trimIndent(),
        )

        assertTrue(result is OnvifParseResult.Success)
        val profiles = (result as OnvifParseResult.Success).value
        assertEquals(2, profiles.size)
        assertEquals("profile-main", profiles[0].token)
        assertEquals("H264", profiles[0].encoding)
        assertEquals(1920, profiles[0].width)
        assertEquals(1080, profiles[0].height)
        assertEquals("profile-sub", profiles[1].token)
        assertEquals(640, profiles[1].width)
        assertEquals(360, profiles[1].height)
    }

    @Test
    fun parsesStreamUriResponse() {
        val result = OnvifProfileParser.parseStreamUri(
            """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                xmlns:tt="http://www.onvif.org/ver10/schema">
              <s:Body><trt:GetStreamUriResponse><trt:MediaUri>
                <tt:Uri>rtsp://192.168.1.20:554/live/main</tt:Uri>
              </trt:MediaUri></trt:GetStreamUriResponse></s:Body>
            </s:Envelope>
            """.trimIndent(),
        )

        assertEquals(URI("rtsp://192.168.1.20:554/live/main"),
            (result as OnvifParseResult.Success).value)
    }

    @Test
    fun malformedXmlReturnsTypedError() {
        val result = OnvifProfileParser.parseProfiles("<s:Envelope")

        assertEquals(OnvifParseResult.Error(OnvifParseError.MALFORMED_XML), result)
    }
}
