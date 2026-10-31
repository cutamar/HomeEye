package app.homeeye.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParsingTest {
    @Test
    fun parsesCameraTime() {
        val xml = """<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body><tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Time><tt:Hour>9</tt:Hour><tt:Minute>49</tt:Minute><tt:Second>49</tt:Second></tt:Time><tt:Date><tt:Year>2026</tt:Year><tt:Month>10</tt:Month><tt:Day>31</tt:Day></tt:Date></tt:UTCDateTime><tt:LocalDateTime><tt:Time><tt:Hour>10</tt:Hour><tt:Minute>0</tt:Minute><tt:Second>0</tt:Second></tt:Time><tt:Date><tt:Year>2026</tt:Year><tt:Month>10</tt:Month><tt:Day>31</tt:Day></tt:Date></tt:LocalDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse></s:Body></s:Envelope>"""
        assertEquals(java.time.Instant.parse("2026-10-31T09:49:49Z").toEpochMilli(), OnvifClient.parseCameraTime(xml))
    }

    @Test
    fun parsesProbeMatch() {
        val xml = """<?xml version="1.0"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><e:Body><d:ProbeMatches><d:ProbeMatch><d:Scopes>onvif://www.onvif.org/name/TP-IPC onvif://www.onvif.org/hardware/Tapo%20C200 onvif://www.onvif.org/Profile/Streaming</d:Scopes><d:XAddrs>http://192.0.2.10:2020/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches></e:Body></e:Envelope>"""
        val cam = Discovery.parseProbeMatch(xml, null)!!
        assertEquals("192.0.2.10", cam.host)
        assertEquals(2020, cam.port)
        assertEquals("TP-IPC", cam.name)
        assertEquals("Tapo C200", cam.hardware)
    }

    @Test
    fun parsesRealTapoProbeMatch() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsdd="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:tdn="http://www.onvif.org/ver10/network/wsdl"><SOAP-ENV:Header><wsa:Action SOAP-ENV:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action></SOAP-ENV:Header><SOAP-ENV:Body><wsdd:ProbeMatches><wsdd:ProbeMatch><wsa:EndpointReference><wsa:Address>uuid:00000000-0000-0000-0000-000000000000</wsa:Address><wsa:ReferenceProperties></wsa:ReferenceProperties><wsa:PortType>ttl</wsa:PortType></wsa:EndpointReference><wsdd:Types>tdn:NetworkVideoTransmitter</wsdd:Types><wsdd:Scopes>onvif://www.onvif.org/name/C200 onvif://www.onvif.org/hardware/C200 onvif://www.onvif.org/Profile/Streaming onvif://www.onvif.org/location/Hong%20Kong</wsdd:Scopes><wsdd:XAddrs>http://192.0.2.10:2020/onvif/device_service</wsdd:XAddrs><wsdd:MetadataVersion>1</wsdd:MetadataVersion></wsdd:ProbeMatch></wsdd:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val cam = Discovery.parseProbeMatch(xml, null)!!
        assertEquals("192.0.2.10", cam.host)
        assertEquals("C200", cam.hardware)
    }

    @Test
    fun escapesUserText() {
        assertEquals("a&amp;b&lt;c&gt;", Xml.escape("a&b<c>"))
    }
}

/**
 * Runs against a real camera. Skipped unless these are set:
 *   HOMEEYE_CAM_HOST, HOMEEYE_CAM_USER, HOMEEYE_CAM_PASS  (and HOMEEYE_CAM_PORT, default 2020)
 * HOMEEYE_CAM_MOVE=1 also nudges the camera right and back.
 */
class LiveCameraTest {
    private val host = System.getenv("HOMEEYE_CAM_HOST")

    private fun client(pass: String = System.getenv("HOMEEYE_CAM_PASS")) = OnvifClient(
        host, System.getenv("HOMEEYE_CAM_PORT")?.toInt() ?: 2020, System.getenv("HOMEEYE_CAM_USER"), pass,
    )

    @Test
    fun readsCameraDetailsAndStreams() {
        assumeTrue(host != null)
        val c = client()
        c.connect()
        val info = c.deviceInfo()
        println("Device: $info")
        val profiles = c.profiles()
        println("Profiles: $profiles")
        assertTrue(profiles.isNotEmpty())
        profiles.forEach { println("Stream ${it.name}: ${c.streamUri(it.token)}") }
        println("PTZ: ${c.supportsPtz}, presets: ${if (c.supportsPtz) c.presets(profiles[0].token) else "-"}")
    }

    @Test
    fun wrongPasswordGivesClearError() {
        assumeTrue(host != null)
        val c = client("definitely-wrong")
        val e = runCatching { c.connect(); c.profiles() }.exceptionOrNull()
        assertNotNull(e)
        println("Wrong password -> ${e!!.message}")
        assertEquals("Wrong username or password", e.message)
    }

    @Test
    fun movesAndStops() {
        assumeTrue(host != null && System.getenv("HOMEEYE_CAM_MOVE") == "1")
        val c = client()
        c.connect()
        val token = c.profiles().first().token
        c.continuousMove(token, 0.5f, 0f); Thread.sleep(500); c.stop(token)
        Thread.sleep(800)
        c.continuousMove(token, -0.5f, 0f); Thread.sleep(500); c.stop(token)
    }

    @Test
    fun discoversCameras() {
        assumeTrue(host != null)
        // ".0" stands in for "our own address" so no real host is skipped
        val found = Discovery.probe(3000, Discovery.subnetHosts(host.substringBeforeLast('.') + ".0"))
        println("Discovered: $found")
        assertTrue(found.any { it.host == host })
    }
}

class UnreachableTest {
    @Test
    fun unreachableCameraFailsFastWithClearMessage() {
        // 192.0.2.x is reserved for documentation; nothing ever answers there.
        val start = System.currentTimeMillis()
        val e = runCatching { OnvifClient("192.0.2.1", 2020, "u", "p", connectTimeoutMs = 1500).connect() }.exceptionOrNull()
        val took = System.currentTimeMillis() - start
        assertTrue(e is OnvifException && e.unreachable)
        assertTrue("took ${took}ms", took < 2500)
    }
}
