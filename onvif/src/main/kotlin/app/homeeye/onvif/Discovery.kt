package app.homeeye.onvif

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

data class DiscoveredCamera(
    val host: String,
    val port: Int,
    val name: String?,
    val hardware: String?,
)

/**
 * Finds ONVIF cameras on the local network (WS-Discovery multicast probe).
 * On Android, hold a WifiManager.MulticastLock while calling this.
 */
object Discovery {
    private const val ADDRESS = "239.255.255.250"
    private const val PORT = 3702

    /**
     * [unicastTargets]: also ask these addresses directly (e.g. every host in the
     * local /24). Helps where routers or firewalls drop multicast.
     */
    fun probe(
        timeoutMs: Int = 3000,
        unicastTargets: List<String> = emptyList(),
        onFound: (DiscoveredCamera) -> Unit = {},
    ): List<DiscoveredCamera> {
        val message = probeMessage().toByteArray()
        val found = linkedMapOf<String, DiscoveredCamera>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val group = InetAddress.getByName(ADDRESS)
            repeat(2) { socket.send(DatagramPacket(message, message.size, group, PORT)) }
            // Sends to empty addresses can stall while the network looks them up,
            // so send in the background and listen at the same time.
            val sender = Thread {
                for (target in unicastTargets) {
                    if (socket.isClosed) break
                    runCatching { socket.send(DatagramPacket(message, message.size, InetAddress.getByName(target), PORT)) }
                }
            }.apply { isDaemon = true; start() }
            val buf = ByteArray(65535)
            val start = System.currentTimeMillis()
            val hardStop = start + timeoutMs + 10_000
            fun keepListening(): Boolean {
                val now = System.currentTimeMillis()
                return now < hardStop && (now < start + timeoutMs || sender.isAlive)
            }
            while (keepListening()) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val xml = String(packet.data, 0, packet.length)
                parseProbeMatch(xml, packet.address.hostAddress)?.let {
                    if (found.putIfAbsent(it.host, it) == null) onFound(it)
                }
            }
        }
        return found.values.toList()
    }

    /** All host addresses in the /24 of an IPv4 address, e.g. 10.0.0.42 -> 10.0.0.1..254. */
    fun subnetHosts(ipv4: String): List<String> {
        val parts = ipv4.split('.')
        if (parts.size != 4) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != ipv4 }
    }

    internal fun parseProbeMatch(xml: String, fallbackHost: String?): DiscoveredCamera? = runCatching {
        val root = Xml.parse(xml)
        val xaddrs = root.text("XAddrs").orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
        // Prefer an IPv4 http address
        val uri = xaddrs.map { URI(it) }.firstOrNull { it.host?.contains('.') == true }
        val host = uri?.host ?: fallbackHost ?: return null
        val port = uri?.port?.takeIf { it > 0 } ?: 80
        val scopes = root.text("Scopes").orEmpty().split(Regex("\\s+"))
        fun scope(key: String) = scopes.firstOrNull { it.contains("/$key/") }
            ?.substringAfter("/$key/")
            ?.let { URLDecoder.decode(it, "UTF-8") }
        DiscoveredCamera(host, port, scope("name"), scope("hardware"))
    }.getOrNull()

    private fun probeMessage() = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
}
