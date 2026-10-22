package app.telaviva.androidtv.network

import java.net.InetAddress

object LocalAddressPolicy {
    fun isAllowed(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == IPV4_LENGTH) return isAllowedIpv4(bytes, 0)
        if (bytes.size != IPV6_LENGTH) return false

        if (isIpv4Mapped(bytes)) return isAllowedIpv4(bytes, IPV6_LENGTH - IPV4_LENGTH)

        val first = bytes[0].toInt() and BYTE_MASK
        val second = bytes[1].toInt() and BYTE_MASK
        val isUniqueLocal = first and IPV6_ULA_MASK == IPV6_ULA_PREFIX
        val isLinkLocal = first == IPV6_LINK_LOCAL_PREFIX &&
            second and IPV6_LINK_LOCAL_MASK == IPV6_LINK_LOCAL_VALUE
        return isUniqueLocal || isLinkLocal
    }

    private fun isAllowedIpv4(bytes: ByteArray, offset: Int): Boolean {
        val first = bytes[offset].toInt() and BYTE_MASK
        val second = bytes[offset + 1].toInt() and BYTE_MASK

        return first == 10 ||
            (first == 172 && second in IPV4_PRIVATE_172_RANGE) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        (0 until 10).all { bytes[it].toInt() == 0 } &&
            bytes[10].toInt() and BYTE_MASK == IPV4_MAPPED_MARKER &&
            bytes[11].toInt() and BYTE_MASK == IPV4_MAPPED_MARKER

    private val IPV4_PRIVATE_172_RANGE = 16..31
    private const val IPV4_LENGTH = 4
    private const val IPV6_LENGTH = 16
    private const val BYTE_MASK = 0xff
    private const val IPV4_MAPPED_MARKER = 0xff
    private const val IPV6_ULA_MASK = 0xfe
    private const val IPV6_ULA_PREFIX = 0xfc
    private const val IPV6_LINK_LOCAL_PREFIX = 0xfe
    private const val IPV6_LINK_LOCAL_MASK = 0xc0
    private const val IPV6_LINK_LOCAL_VALUE = 0x80
}
