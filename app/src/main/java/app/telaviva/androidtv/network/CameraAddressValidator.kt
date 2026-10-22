package app.telaviva.androidtv.network

import java.net.InetAddress
import java.net.URI

enum class CameraAddressValidationError {
    INVALID_URI,
    WRONG_SCHEME,
    EMBEDDED_CREDENTIALS,
    INVALID_HOST,
    ADDRESS_RESOLUTION_FAILED,
    NON_LOCAL_ADDRESS,
}

class CameraAddressValidationException(val reason: CameraAddressValidationError) :
    IllegalArgumentException(reason.safeMessage)

data class ValidatedCameraAddress(
    val uriWithoutUserInfo: URI,
    val resolvedAddress: InetAddress,
) {
    init {
        require(uriWithoutUserInfo.userInfo == null) { "Camera URI must not contain credentials" }
    }
}

object CameraAddressValidator {
    fun validate(uri: String): ValidatedCameraAddress {
        val parsed = try {
            URI(uri.trim()).normalize()
        } catch (_: Exception) {
            throw CameraAddressValidationException(CameraAddressValidationError.INVALID_URI)
        }

        if (!parsed.scheme.equals(RTSP_SCHEME, ignoreCase = true)) {
            throw CameraAddressValidationException(CameraAddressValidationError.WRONG_SCHEME)
        }
        if (parsed.rawUserInfo != null) {
            throw CameraAddressValidationException(CameraAddressValidationError.EMBEDDED_CREDENTIALS)
        }
        if (parsed.host.isNullOrBlank() || parsed.port != -1 && parsed.port !in 1..MAX_PORT) {
            throw CameraAddressValidationException(CameraAddressValidationError.INVALID_HOST)
        }

        val addresses = try {
            InetAddress.getAllByName(unbracket(parsed.host!!))
        } catch (_: Exception) {
            throw CameraAddressValidationException(CameraAddressValidationError.ADDRESS_RESOLUTION_FAILED)
        }
        if (addresses.isEmpty() || addresses.any { !LocalAddressPolicy.isAllowed(it) }) {
            throw CameraAddressValidationException(CameraAddressValidationError.NON_LOCAL_ADDRESS)
        }

        return ValidatedCameraAddress(parsed, addresses.first())
    }

    private fun unbracket(host: String): String = host.removePrefix("[").removeSuffix("]")

    private const val RTSP_SCHEME = "rtsp"
    private const val MAX_PORT = 65535
}

private val CameraAddressValidationError.safeMessage: String
    get() = when (this) {
        CameraAddressValidationError.INVALID_URI -> "Enter a valid RTSP address"
        CameraAddressValidationError.WRONG_SCHEME -> "Camera address must use RTSP"
        CameraAddressValidationError.EMBEDDED_CREDENTIALS -> "Enter camera credentials separately"
        CameraAddressValidationError.INVALID_HOST -> "Camera address must include a valid host"
        CameraAddressValidationError.ADDRESS_RESOLUTION_FAILED -> "Camera address could not be resolved"
        CameraAddressValidationError.NON_LOCAL_ADDRESS -> "Camera address must resolve only to the local network"
    }
