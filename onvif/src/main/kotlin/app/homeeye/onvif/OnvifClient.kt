package app.homeeye.onvif

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64

/** [unreachable] is true when nothing answered at all (wrong IP, camera off). */
class OnvifException(
    message: String,
    cause: Throwable? = null,
    val unreachable: Boolean = false,
) : Exception(message, cause)

data class DeviceInfo(val manufacturer: String, val model: String, val firmware: String)

data class MediaProfile(
    val token: String,
    val name: String,
    val width: Int,
    val height: Int,
    val hasPtz: Boolean,
)

data class Preset(val token: String, val name: String)

/**
 * Talks ONVIF (SOAP over HTTP) to one camera on the local network.
 * Calls block, so run them off the main thread.
 */
class OnvifClient(
    val host: String,
    val port: Int = 2020,
    private val username: String,
    private val password: String,
    private val timeoutMs: Int = 8000,
    private val connectTimeoutMs: Int = 4000,
) {
    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private var mediaUrl: String? = null
    private var ptzUrl: String? = null
    private var clockOffsetMs = 0L
    private val random = SecureRandom()

    @Volatile var isConnected = false
        private set

    val supportsPtz: Boolean get() = ptzUrl != null

    /** Syncs to the camera clock and looks up its service addresses. */
    fun connect() {
        // Login digests include a timestamp, so use the camera's clock. This keeps
        // working when the camera is cut off from the internet and its clock drifts.
        // Some cameras refuse this without a login; that's fine. But if nothing
        // answers at all, stop here instead of waiting through another timeout.
        try {
            parseCameraTime(post(deviceUrl, "<tds:GetSystemDateAndTime/>", auth = false))
                ?.let { clockOffsetMs = it - System.currentTimeMillis() }
        } catch (e: OnvifException) {
            if (e.unreachable) throw e
        }

        val caps = Xml.parse(call(deviceUrl, "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>"))
        mediaUrl = caps.first("Media")?.text("XAddr")?.let(::useOurHost)
        ptzUrl = caps.first("PTZ")?.text("XAddr")?.let(::useOurHost)
        isConnected = true
    }

    fun deviceInfo(): DeviceInfo {
        val r = Xml.parse(call(deviceUrl, "<tds:GetDeviceInformation/>"))
        return DeviceInfo(
            manufacturer = r.text("Manufacturer").orEmpty(),
            model = r.text("Model").orEmpty(),
            firmware = r.text("FirmwareVersion").orEmpty(),
        )
    }

    fun profiles(): List<MediaProfile> {
        val r = Xml.parse(call(media(), "<trt:GetProfiles/>"))
        return r.all("Profiles").map { p ->
            val res = p.first("VideoEncoderConfiguration")?.first("Resolution")
            MediaProfile(
                token = p.getAttribute("token"),
                name = p.children("Name").firstOrNull()?.textContent?.trim().orEmpty(),
                width = res?.text("Width")?.toIntOrNull() ?: 0,
                height = res?.text("Height")?.toIntOrNull() ?: 0,
                hasPtz = p.first("PTZConfiguration") != null,
            )
        }
    }

    fun streamUri(profileToken: String): String {
        val body = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
            "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
            "<trt:ProfileToken>${Xml.escape(profileToken)}</trt:ProfileToken></trt:GetStreamUri>"
        return Xml.parse(call(media(), body)).text("Uri")
            ?: throw OnvifException("Camera did not return a stream address")
    }

    /** Starts moving. pan/tilt are -1..1 (right and up are positive). Keeps going until [stop]. */
    fun continuousMove(profileToken: String, pan: Float, tilt: Float) {
        call(ptz(), "<tptz:ContinuousMove><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken>" +
            "<tptz:Velocity><tt:PanTilt x=\"${fmt(pan)}\" y=\"${fmt(tilt)}\"/></tptz:Velocity></tptz:ContinuousMove>")
    }

    fun stop(profileToken: String) {
        call(ptz(), "<tptz:Stop><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken>" +
            "<tptz:PanTilt>true</tptz:PanTilt></tptz:Stop>")
    }

    fun presets(profileToken: String): List<Preset> {
        val r = Xml.parse(call(ptz(), "<tptz:GetPresets><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken></tptz:GetPresets>"))
        return r.all("Preset").map { Preset(it.getAttribute("token"), it.text("Name").orEmpty()) }
    }

    fun gotoPreset(profileToken: String, presetToken: String) {
        call(ptz(), "<tptz:GotoPreset><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken>" +
            "<tptz:PresetToken>${Xml.escape(presetToken)}</tptz:PresetToken></tptz:GotoPreset>")
    }

    /** Saves the current position. Returns the new preset's token. */
    fun setPreset(profileToken: String, name: String): String {
        val r = Xml.parse(call(ptz(), "<tptz:SetPreset><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken>" +
            "<tptz:PresetName>${Xml.escape(name)}</tptz:PresetName></tptz:SetPreset>"))
        return r.text("PresetToken").orEmpty()
    }

    fun removePreset(profileToken: String, presetToken: String) {
        call(ptz(), "<tptz:RemovePreset><tptz:ProfileToken>${Xml.escape(profileToken)}</tptz:ProfileToken>" +
            "<tptz:PresetToken>${Xml.escape(presetToken)}</tptz:PresetToken></tptz:RemovePreset>")
    }

    // --- plumbing ---

    private fun media() = mediaUrl ?: deviceUrl
    private fun ptz() = ptzUrl ?: throw OnvifException("This camera doesn't support pan/tilt")

    private fun fmt(v: Float) = "%.2f".format(java.util.Locale.US, v.coerceIn(-1f, 1f))

    /** Cameras sometimes report addresses with the wrong IP; keep their path, use the IP we reached. */
    private fun useOurHost(xaddr: String): String = runCatching {
        val u = URI(xaddr.trim().split(" ").first())
        "http://$host:${if (u.port > 0) u.port else port}${u.rawPath ?: ""}"
    }.getOrDefault(xaddr)

    private fun call(url: String, body: String) = post(url, body, auth = true)

    private fun post(url: String, body: String, auth: Boolean): String {
        val envelope = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope $NS><s:Header>${if (auth) securityHeader() else ""}</s:Header><s:Body>$body</s:Body></s:Envelope>"""
        val conn = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw OnvifException("Can't reach camera at $host:$port", e, unreachable = true)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw faultToException(code, err)
        } catch (e: OnvifException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw OnvifException("Camera at $host:$port didn't answer in time", e, unreachable = true)
        } catch (e: IOException) {
            throw OnvifException("Can't reach camera at $host:$port", e, unreachable = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun faultToException(code: Int, body: String): OnvifException {
        val fault = runCatching { Xml.parse(body) }.getOrNull()
        val reason = fault?.first("Reason")?.text("Text")
        val subcode = fault?.first("Subcode")?.text("Value").orEmpty()
        val authFail = code == 401 || subcode.contains("NotAuthorized") ||
            reason?.contains("auth", ignoreCase = true) == true
        return when {
            authFail -> OnvifException("Wrong username or password")
            reason != null -> OnvifException("Camera error: $reason")
            else -> OnvifException("Camera error (HTTP $code)")
        }
    }

    private fun securityHeader(): String {
        val nonce = ByteArray(16).also(random::nextBytes)
        val created = Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs)
            .truncatedTo(ChronoUnit.SECONDS).toString()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(nonce + created.toByteArray() + password.toByteArray())
        val b64 = Base64.getEncoder()
        return """<wsse:Security xmlns:wsse="$WSSE" xmlns:wsu="$WSU"><wsse:UsernameToken>""" +
            "<wsse:Username>${Xml.escape(username)}</wsse:Username>" +
            """<wsse:Password Type="$PW_DIGEST">${b64.encodeToString(digest)}</wsse:Password>""" +
            """<wsse:Nonce EncodingType="$B64_TYPE">${b64.encodeToString(nonce)}</wsse:Nonce>""" +
            "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    companion object {
        private const val NS = """xmlns:s="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:tds="http://www.onvif.org/ver10/device/wsdl" """ +
            """xmlns:trt="http://www.onvif.org/ver10/media/wsdl" """ +
            """xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" """ +
            """xmlns:tt="http://www.onvif.org/ver10/schema""""
        private const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        private const val WSU = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        private const val PW_DIGEST = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest"
        private const val B64_TYPE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-soap-message-security-1.0#Base64Binary"

        /** Reads the UTC time out of a GetSystemDateAndTime response, in epoch millis. */
        internal fun parseCameraTime(xml: String): Long? = runCatching {
            val utc = Xml.parse(xml).first("UTCDateTime") ?: return null
            fun n(tag: String) = utc.text(tag)!!.toInt()
            LocalDateTime.of(n("Year"), n("Month"), n("Day"), n("Hour"), n("Minute"), n("Second"))
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
}
