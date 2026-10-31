package app.homeeye.data

import app.homeeye.onvif.DeviceInfo
import app.homeeye.onvif.OnvifClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/** What we learn about a camera when testing the connection. */
data class ProbeResult(
    val info: DeviceInfo,
    val rtspPort: Int,
    val mainPath: String,
    val subPath: String?,
    val mainResolution: String,
    val subResolution: String?,
    val hasPtz: Boolean,
)

object CameraProbe {
    /** Logs in over ONVIF and finds the camera's streams. Throws OnvifException with a readable message. */
    suspend fun probe(host: String, onvifPort: Int, username: String, password: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val client = OnvifClient(host, onvifPort, username, password)
            client.connect()
            val info = client.deviceInfo()
            // Biggest first; skip MJPEG-only profiles if there are better ones.
            val profiles = client.profiles().sortedByDescending { it.width * it.height }
            val video = profiles.filterNot { it.name.contains("jpeg", ignoreCase = true) }.ifEmpty { profiles }
            val main = video.first()
            val sub = video.getOrNull(1)
            val mainUri = URI(client.streamUri(main.token))
            val subUri = sub?.let { URI(client.streamUri(it.token)) }
            ProbeResult(
                info = info,
                rtspPort = mainUri.port.takeIf { it > 0 } ?: 554,
                mainPath = pathOf(mainUri),
                subPath = subUri?.let(::pathOf),
                mainResolution = "${main.width}×${main.height}",
                subResolution = sub?.let { "${it.width}×${it.height}" },
                hasPtz = client.supportsPtz && profiles.any { it.hasPtz },
            )
        }

    private fun pathOf(uri: URI): String =
        (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
}
