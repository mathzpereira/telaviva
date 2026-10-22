package app.telaviva.androidtv.network.onvif

import android.util.Base64
import app.telaviva.androidtv.domain.CameraCredentials
import app.telaviva.androidtv.network.CameraAddressValidationError
import app.telaviva.androidtv.network.CameraAddressValidationException
import app.telaviva.androidtv.network.CameraAddressValidator
import app.telaviva.androidtv.network.LocalAddressPolicy
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CameraStreams(
    val mainRtspUri: String,
    val subRtspUri: String?,
)

enum class OnvifFailure {
    NON_LOCAL_ADDRESS,
    INVALID_RESPONSE,
    DEVICE_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    UNSUPPORTED_AUTHENTICATION,
}

class OnvifException(val reason: OnvifFailure) : Exception(reason.safeMessage)

interface OnvifHttpTransport {
    fun post(
        uri: URI,
        action: String,
        envelope: String,
        credentials: CameraCredentials,
    ): String
}

class OnvifSoapClient(
    private val transport: OnvifHttpTransport = HttpOnvifTransport(),
) {
    fun getProfiles(
        endpoint: OnvifEndpoint,
        credentials: CameraCredentials,
    ): List<OnvifProfile> {
        val mediaService = getMediaServiceUri(endpoint, credentials)
        val response = call(
            mediaService,
            GET_PROFILES_ACTION,
            "<trt:GetProfiles xmlns:trt=\"$MEDIA_NAMESPACE\"/>",
            credentials,
        )
        return when (val result = OnvifProfileParser.parseProfiles(response)) {
            is OnvifParseResult.Success -> result.value
            is OnvifParseResult.Error -> throw parseFailure(result.reason)
        }
    }

    fun getStreamUri(
        endpoint: OnvifEndpoint,
        profileToken: String,
        credentials: CameraCredentials,
    ): URI {
        val mediaService = getMediaServiceUri(endpoint, credentials)
        val body = """
            <trt:GetStreamUri xmlns:trt="$MEDIA_NAMESPACE" xmlns:tt="$SCHEMA_NAMESPACE">
              <trt:StreamSetup>
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
              </trt:StreamSetup>
              <trt:ProfileToken>${escapeXml(profileToken)}</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent()
        val response = call(mediaService, GET_STREAM_URI_ACTION, body, credentials)
        val streamUri = when (val result = OnvifProfileParser.parseStreamUri(response)) {
            is OnvifParseResult.Success -> result.value
            is OnvifParseResult.Error -> throw parseFailure(result.reason)
        }

        return try {
            CameraAddressValidator.validate(streamUri.toASCIIString()).uriWithoutUserInfo
        } catch (error: CameraAddressValidationException) {
            val reason = if (error.reason == CameraAddressValidationError.NON_LOCAL_ADDRESS) {
                OnvifFailure.NON_LOCAL_ADDRESS
            } else {
                OnvifFailure.INVALID_RESPONSE
            }
            throw OnvifException(reason)
        } catch (_: Exception) {
            throw OnvifException(OnvifFailure.INVALID_RESPONSE)
        }
    }

    private fun getMediaServiceUri(
        endpoint: OnvifEndpoint,
        credentials: CameraCredentials,
    ): URI {
        if (!LocalAddressPolicy.isAllowed(endpoint.deviceAddress)) {
            throw OnvifException(OnvifFailure.NON_LOCAL_ADDRESS)
        }
        val deviceService = validateServiceAddress(endpoint.xAddr)
        val response = call(
            deviceService,
            GET_CAPABILITIES_ACTION,
            """
                <tds:GetCapabilities xmlns:tds="$DEVICE_NAMESPACE">
                  <tds:Category>Media</tds:Category>
                </tds:GetCapabilities>
            """.trimIndent(),
            credentials,
        )
        val mediaAddress = when (val result = OnvifProfileParser.parseMediaServiceUri(response)) {
            is OnvifParseResult.Success -> result.value
            is OnvifParseResult.Error -> throw parseFailure(result.reason)
        }
        return validateServiceAddress(mediaAddress)
    }

    private fun validateServiceAddress(uri: URI): URI = try {
        OnvifServiceAddressValidator.validate(uri)
    } catch (_: Exception) {
        throw OnvifException(OnvifFailure.NON_LOCAL_ADDRESS)
    }

    private fun call(
        uri: URI,
        action: String,
        body: String,
        credentials: CameraCredentials,
    ): String {
        val envelope = try {
            createEnvelope(uri, action, body, credentials)
        } catch (_: Exception) {
            throw OnvifException(OnvifFailure.INVALID_RESPONSE)
        }
        return try {
            transport.post(uri, action, envelope, credentials)
        } catch (error: OnvifException) {
            throw error
        } catch (_: Exception) {
            throw OnvifException(OnvifFailure.DEVICE_UNAVAILABLE)
        }
    }

    private fun parseFailure(error: OnvifParseError): OnvifException = when (error) {
        OnvifParseError.SOAP_FAULT -> OnvifException(OnvifFailure.AUTHENTICATION_FAILED)
        OnvifParseError.MALFORMED_XML,
        OnvifParseError.MISSING_VALUE,
        -> OnvifException(OnvifFailure.INVALID_RESPONSE)
    }

    private fun createEnvelope(
        endpoint: URI,
        action: String,
        body: String,
        credentials: CameraCredentials,
    ): String {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val created = utcTimestamp()
        val passwordBytes = credentials.password.toByteArray(StandardCharsets.UTF_8)
        val createdBytes = created.toByteArray(StandardCharsets.UTF_8)
        val digestInput = ByteArray(nonce.size + createdBytes.size + passwordBytes.size)
        try {
            nonce.copyInto(digestInput, 0)
            createdBytes.copyInto(digestInput, nonce.size)
            passwordBytes.copyInto(digestInput, nonce.size + createdBytes.size)
            val passwordDigest = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest(digestInput),
                Base64.NO_WRAP,
            )
            val nonceValue = Base64.encodeToString(nonce, Base64.NO_WRAP)
            return """
                <s:Envelope xmlns:s="$SOAP_NAMESPACE" xmlns:wsse="$WSSE_NAMESPACE"
                    xmlns:wsu="$WSU_NAMESPACE" xmlns:wsa="$WSA_NAMESPACE">
                  <s:Header>
                    <wsa:Action s:mustUnderstand="1">${escapeXml(action)}</wsa:Action>
                    <wsa:To s:mustUnderstand="1">${escapeXml(endpoint.toASCIIString())}</wsa:To>
                    <wsse:Security s:mustUnderstand="1">
                      <wsse:UsernameToken>
                        <wsse:Username>${escapeXml(credentials.username)}</wsse:Username>
                        <wsse:Password Type="$PASSWORD_DIGEST_TYPE">$passwordDigest</wsse:Password>
                        <wsse:Nonce EncodingType="$NONCE_ENCODING_TYPE">$nonceValue</wsse:Nonce>
                        <wsu:Created>$created</wsu:Created>
                      </wsse:UsernameToken>
                    </wsse:Security>
                  </s:Header>
                  <s:Body>$body</s:Body>
                </s:Envelope>
            """.trimIndent()
        } finally {
            nonce.fill(0)
            passwordBytes.fill(0)
            createdBytes.fill(0)
            digestInput.fill(0)
        }
    }

    private fun utcTimestamp(): String = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        private const val SOAP_NAMESPACE = "http://www.w3.org/2003/05/soap-envelope"
        private const val WSA_NAMESPACE = "http://www.w3.org/2005/08/addressing"
        private const val WSSE_NAMESPACE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val WSU_NAMESPACE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val PASSWORD_DIGEST_TYPE =
            "$WSSE_NAMESPACE#PasswordDigest"
        private const val NONCE_ENCODING_TYPE =
            "$WSSE_NAMESPACE#Base64Binary"
        private const val DEVICE_NAMESPACE = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA_NAMESPACE = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA_NAMESPACE = "http://www.onvif.org/ver10/schema"
        private const val GET_CAPABILITIES_ACTION = "$DEVICE_NAMESPACE/GetCapabilities"
        private const val GET_PROFILES_ACTION = "$MEDIA_NAMESPACE/GetProfiles"
        private const val GET_STREAM_URI_ACTION = "$MEDIA_NAMESPACE/GetStreamUri"
        private const val NONCE_BYTES = 16
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    }
}

private class HttpOnvifTransport : OnvifHttpTransport {
    override fun post(
        uri: URI,
        action: String,
        envelope: String,
        credentials: CameraCredentials,
    ): String {
        val payload = envelope.toByteArray(StandardCharsets.UTF_8)
        try {
            val first = execute(uri, action, payload, null)
            if (first.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val challenge = first.authenticationChallenge
                    ?: throw OnvifException(OnvifFailure.AUTHENTICATION_FAILED)
                val authorization = HttpDigestAuthorization.create(challenge, uri, payload, credentials)
                val retry = execute(uri, action, payload, authorization)
                if (retry.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw OnvifException(OnvifFailure.AUTHENTICATION_FAILED)
                }
                return responseBody(retry)
            }
            return responseBody(first)
        } finally {
            payload.fill(0)
        }
    }

    private fun execute(
        uri: URI,
        action: String,
        payload: ByteArray,
        authorization: String?,
    ): HttpOnvifResponse {
        val connection = try {
            uri.toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            throw OnvifException(OnvifFailure.NON_LOCAL_ADDRESS)
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty(
                "Content-Type",
                "application/soap+xml; charset=utf-8; action=\"$action\"",
            )
            connection.setRequestProperty("SOAPAction", "\"$action\"")
            if (authorization != null) connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { output -> output.write(payload) }

            val statusCode = connection.responseCode
            val body = when {
                statusCode == HttpURLConnection.HTTP_UNAUTHORIZED -> ""
                statusCode in 200..299 -> readBounded(connection.inputStream)
                else -> connection.errorStream?.let(::readBounded).orEmpty()
            }
            return HttpOnvifResponse(statusCode, connection.getHeaderField("WWW-Authenticate"), body)
        } catch (error: OnvifException) {
            throw error
        } catch (_: Exception) {
            throw OnvifException(OnvifFailure.DEVICE_UNAVAILABLE)
        } finally {
            connection.disconnect()
        }
    }

    private fun responseBody(response: HttpOnvifResponse): String {
        if (response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw OnvifException(OnvifFailure.AUTHENTICATION_FAILED)
        }
        if (response.statusCode !in 200..299 && response.body.isBlank()) {
            throw OnvifException(OnvifFailure.DEVICE_UNAVAILABLE)
        }
        return response.body
    }

    private fun readBounded(input: java.io.InputStream): String {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) {
                    throw OnvifException(OnvifFailure.INVALID_RESPONSE)
                }
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    private companion object {
        const val HTTP_TIMEOUT_MS = 5_000
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val READ_BUFFER_BYTES = 8_192
    }
}

private data class HttpOnvifResponse(
    val statusCode: Int,
    val authenticationChallenge: String?,
    val body: String,
)

internal object HttpDigestAuthorization {
    fun create(
        challenge: String,
        uri: URI,
        entity: ByteArray,
        credentials: CameraCredentials,
    ): String {
        val parameters = parseParameters(challenge)
        val realm = parameters["realm"] ?: throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        val nonce = parameters["nonce"] ?: throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        val algorithm = parameters["algorithm"] ?: DEFAULT_ALGORITHM
        if (!algorithm.equals(MD5, ignoreCase = true) &&
            !algorithm.equals(MD5_SESS, ignoreCase = true)) {
            throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        }
        val normalizedAlgorithm = if (algorithm.equals(MD5_SESS, ignoreCase = true)) MD5_SESS else MD5

        val challengeCharset = parameters["charset"]
        if (challengeCharset != null && !challengeCharset.equals(UTF8_NAME, ignoreCase = true)) {
            throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        }
        val charset = if (challengeCharset != null) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
        val qopOptions = parameters["qop"]?.split(',')?.map(String::trim).orEmpty()
        val qop = when {
            qopOptions.any { it.equals(AUTH, ignoreCase = true) } -> AUTH
            qopOptions.any { it.equals(AUTH_INT, ignoreCase = true) } -> AUTH_INT
            qopOptions.isEmpty() -> null
            else -> throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        }
        val cnonce = Base64.encodeToString(ByteArray(CNONCE_BYTES).also(SecureRandom()::nextBytes), Base64.NO_WRAP)
        val nonceCount = "00000001"
        val digestUri = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")

        val baseHa1 = md5("${credentials.username}:$realm:${credentials.password}", charset)
        val ha1 = if (normalizedAlgorithm == MD5_SESS) {
            md5("$baseHa1:$nonce:$cnonce", charset)
        } else {
            baseHa1
        }
        val ha2 = if (qop == AUTH_INT) {
            md5("POST:$digestUri:${md5(entity)}", charset)
        } else {
            md5("POST:$digestUri", charset)
        }
        val response = if (qop == null) {
            md5("$ha1:$nonce:$ha2", charset)
        } else {
            md5("$ha1:$nonce:$nonceCount:$cnonce:$qop:$ha2", charset)
        }

        return buildString {
            append("Digest username=").append(quoted(credentials.username))
            append(", realm=").append(quoted(realm))
            append(", nonce=").append(quoted(nonce))
            append(", uri=").append(quoted(digestUri))
            append(", response=").append(quoted(response))
            append(", algorithm=").append(normalizedAlgorithm)
            parameters["opaque"]?.let { append(", opaque=").append(quoted(it)) }
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nonceCount)
                append(", cnonce=").append(quoted(cnonce))
            } else if (algorithm.equals(MD5_SESS, ignoreCase = true)) {
                append(", cnonce=").append(quoted(cnonce))
            }
            if (challengeCharset != null) append(", charset=").append(UTF8_NAME)
        }
    }

    private fun parseParameters(challenge: String): Map<String, String> {
        val digestStart = challenge.indexOf(DIGEST_PREFIX, ignoreCase = true)
        if (digestStart < 0) throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        val input = challenge.substring(digestStart + DIGEST_PREFIX.length)
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < input.length) {
            while (index < input.length && (input[index].isWhitespace() || input[index] == ',')) index++
            val keyStart = index
            while (index < input.length && input[index] != '=' && input[index] != ',') index++
            if (index >= input.length || input[index] != '=') break
            val key = input.substring(keyStart, index).trim().lowercase(Locale.ROOT)
            index++
            while (index < input.length && input[index].isWhitespace()) index++
            val value = StringBuilder()
            if (index < input.length && input[index] == '"') {
                index++
                while (index < input.length && input[index] != '"') {
                    if (input[index] == '\\' && index + 1 < input.length) index++
                    value.append(input[index])
                    index++
                }
                if (index < input.length) index++
            } else {
                while (index < input.length && input[index] != ',') {
                    value.append(input[index])
                    index++
                }
            }
            if (key.isNotEmpty()) values[key] = value.toString().trim()
        }
        return values
    }

    private fun md5(value: String, charset: Charset): String =
        md5(value.toByteArray(charset))

    private fun md5(value: ByteArray): String =
        MessageDigest.getInstance(MD5).digest(value).joinToString("") { byte -> "%02x".format(byte) }

    private fun quoted(value: String): String {
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            throw OnvifException(OnvifFailure.UNSUPPORTED_AUTHENTICATION)
        }
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private const val DIGEST_PREFIX = "Digest "
    private const val DEFAULT_ALGORITHM = "MD5"
    private const val MD5 = "MD5"
    private const val MD5_SESS = "MD5-sess"
    private const val AUTH = "auth"
    private const val AUTH_INT = "auth-int"
    private const val UTF8_NAME = "UTF-8"
    private const val CNONCE_BYTES = 18
}

private val OnvifFailure.safeMessage: String
    get() = when (this) {
        OnvifFailure.NON_LOCAL_ADDRESS -> "Camera address is outside the local network"
        OnvifFailure.INVALID_RESPONSE -> "Camera returned an unsupported ONVIF response"
        OnvifFailure.DEVICE_UNAVAILABLE -> "Camera is unavailable on the local network"
        OnvifFailure.AUTHENTICATION_FAILED -> "Camera rejected the credentials"
        OnvifFailure.UNSUPPORTED_AUTHENTICATION -> "Camera uses an unsupported authentication method"
    }

private fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character.toString()
            },
        )
    }
}
