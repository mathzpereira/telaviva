package app.telaviva.androidtv.network.onvif

import app.telaviva.androidtv.network.LocalAddressPolicy
import java.net.InetAddress
import java.net.URI
import org.xmlpull.v1.XmlPullParser

data class OnvifEndpoint(
    val deviceAddress: InetAddress,
    val xAddr: URI,
)

object WsDiscoveryParser {
    fun parse(payload: String, senderAddress: InetAddress): List<OnvifEndpoint> {
        if (payload.length > MAX_DISCOVERY_CHARACTERS || !LocalAddressPolicy.isAllowed(senderAddress)) {
            return emptyList()
        }

        return try {
            val parser = createOnvifPullParser(payload)
            val unique = linkedMapOf<String, OnvifEndpoint>()
            var inProbeMatch = false
            var xAddrs = mutableListOf<String>()

            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "ProbeMatch" -> {
                            inProbeMatch = true
                            xAddrs = mutableListOf()
                        }
                        "XAddrs" -> if (inProbeMatch) {
                            xAddrs += parser.nextText().trim().split(WHITESPACE).filter(String::isNotBlank)
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "ProbeMatch" && inProbeMatch) {
                        xAddrs.forEach { addressText ->
                            val xAddr = try {
                                OnvifServiceAddressValidator.validate(URI(addressText))
                            } catch (_: Exception) {
                                null
                            }
                            if (xAddr != null) {
                                val endpoint = OnvifEndpoint(senderAddress, xAddr)
                                unique.putIfAbsent("${senderAddress.hostAddress}|$xAddr", endpoint)
                            }
                        }
                        inProbeMatch = false
                    }
                }
            }
            unique.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private const val MAX_DISCOVERY_CHARACTERS = 65_536
    private val WHITESPACE = Regex("\\s+")
}

internal object OnvifServiceAddressValidator {
    fun validate(uri: URI): URI {
        val normalized = uri.normalize()
        require(normalized.scheme.equals("http", ignoreCase = true) ||
            normalized.scheme.equals("https", ignoreCase = true))
        require(normalized.rawUserInfo == null)
        val host = normalized.host?.removePrefix("[")?.removeSuffix("]")
            ?: throw IllegalArgumentException("ONVIF service address has no host")
        require(normalized.port == -1 || normalized.port in 1..MAX_PORT)
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all(LocalAddressPolicy::isAllowed))
        return normalized
    }

    private const val MAX_PORT = 65535
}
