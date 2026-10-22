package app.telaviva.androidtv.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressPolicyTest {
    @Test
    fun acceptsRfc1918AndLinkLocalAddresses() {
        val allowedAddresses = listOf(
            "10.0.0.8",
            "172.16.1.8",
            "172.31.255.8",
            "192.168.1.8",
            "169.254.10.8",
            "fc00::1",
            "fd12:3456::1",
            "fe80::1",
        )

        allowedAddresses.forEach { address ->
            assertTrue(address, LocalAddressPolicy.isAllowed(InetAddress.getByName(address)))
        }
    }

    @Test
    fun rejectsPublicLoopbackAndMulticastAddresses() {
        val deniedAddresses = listOf(
            "8.8.8.8",
            "172.32.0.1",
            "169.255.0.1",
            "127.0.0.1",
            "::1",
            "224.0.0.1",
            "ff02::1",
            "192.0.2.1",
        )

        deniedAddresses.forEach { address ->
            assertFalse(address, LocalAddressPolicy.isAllowed(InetAddress.getByName(address)))
        }
    }
}
