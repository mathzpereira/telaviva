package app.telaviva.androidtv.network.onvif

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

class WsDiscoveryClient(context: Context) {
    private val applicationContext = context.applicationContext

    fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<OnvifEndpoint> {
        if (timeoutMs <= 0) return emptyList()

        val timeout = timeoutMs.coerceAtMost(MAX_TIMEOUT_MS)
        val deadline = SystemClock.elapsedRealtime() + timeout
        val endpoints = linkedMapOf<String, OnvifEndpoint>()
        var multicastLock: WifiManager.MulticastLock? = null
        var lockHeld = false
        var socket: MulticastSocket? = null

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
                lockHeld = true
            }

            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
                chooseLocalInterface()?.let(::setNetworkInterface)
                timeToLive = 1
            }
            val destination = InetAddress.getByName(MULTICAST_GROUP)
            val probe = probeMessage().toByteArray(StandardCharsets.UTF_8)
            socket.send(DatagramPacket(probe, probe.size, destination, WS_DISCOVERY_PORT))

            val packetBuffer = ByteArray(MAX_DATAGRAM_BYTES)
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                socket.soTimeout = remaining.coerceAtMost(MAX_SOCKET_TIMEOUT_MS.toLong()).toInt()
                val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                socket.receive(packet)
                val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                WsDiscoveryParser.parse(payload, packet.address).forEach { endpoint ->
                    endpoints.putIfAbsent("${endpoint.deviceAddress.hostAddress}|${endpoint.xAddr}", endpoint)
                }
            }
        } catch (_: IOException) {
            // A network with no ONVIF responders simply produces an empty result.
        } catch (_: RuntimeException) {
            // Keep a failed local discovery attempt isolated from the setup screen.
        } finally {
            socket?.close()
            if (lockHeld) runCatching { multicastLock?.release() }
        }

        return endpoints.values.toList()
    }

    private fun chooseLocalInterface(): NetworkInterface? {
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            return null
        }
        val eligible = interfaces.filter { networkInterface ->
            runCatching {
                networkInterface.isUp && !networkInterface.isLoopback &&
                    Collections.list(networkInterface.inetAddresses).any { address ->
                        app.telaviva.androidtv.network.LocalAddressPolicy.isAllowed(address)
                    }
            }.getOrDefault(false)
        }
        return eligible.firstOrNull { it.name.contains("wlan", ignoreCase = true) } ?: eligible.firstOrNull()
    }

    private fun probeMessage(): String =
        """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
          <s:Header>
            <a:MessageID>uuid:${UUID.randomUUID()}</a:MessageID>
            <a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
            <a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
          </s:Header>
          <s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body>
        </s:Envelope>
        """.trimIndent()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        private const val MAX_TIMEOUT_MS = 30_000L
        private const val MAX_SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_DATAGRAM_BYTES = 65_507
        private const val MULTICAST_GROUP = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        private const val MULTICAST_LOCK_TAG = "TelaVivaWsDiscovery"
    }
}
