package app.homeeye.data

import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.UUID

@Serializable
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val onvifPort: Int = 2020,
    val rtspPort: Int = 554,
    val username: String,
    /** Encrypted with [Secrets]; never stored in plain text. */
    val passwordEnc: String,
    /** Path (plus query) of the high quality stream, e.g. "/stream1". */
    val mainPath: String = "/stream1",
    /** Path of the low quality stream, if the camera has one. */
    val subPath: String? = "/stream2",
    val model: String? = null,
    val hasPtz: Boolean = true,
    val invertPan: Boolean = false,
    val invertTilt: Boolean = false,
    /** RTSP over TCP: more reliable on weak Wi-Fi than UDP. */
    val forceTcp: Boolean = true,
) {
    fun password(): String = runCatching { Secrets.decrypt(passwordEnc) }.getOrDefault("")

    fun rtspUrl(hd: Boolean): String {
        val path = if (hd || subPath == null) mainPath else subPath
        val user = enc(username)
        val pass = enc(password())
        val auth = if (username.isEmpty()) "" else "$user:$pass@"
        return "rtsp://$auth$host:$rtspPort$path"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
