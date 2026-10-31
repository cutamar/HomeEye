package app.homeeye.ui.live

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.homeeye.cameraRepository
import app.homeeye.data.Camera
import app.homeeye.onvif.OnvifClient
import app.homeeye.onvif.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class LiveViewModel(app: Application, cameraId: String) : AndroidViewModel(app) {
    val camera: Camera? = app.cameraRepository.get(cameraId)

    var hd by mutableStateOf(false)
    var muted by mutableStateOf(true)

    var ptzReady by mutableStateOf(false)
        private set
    var presets by mutableStateOf<List<Preset>>(emptyList())
        private set
    /** One-off message for a snackbar. */
    var message by mutableStateOf<String?>(null)

    private var client: OnvifClient? = null
    private var profile: String? = null

    /**
     * Latest joystick position, or null for "stop". CONFLATED keeps only the newest
     * value, so a slow camera never builds up a queue of stale moves.
     */
    private val moves = Channel<Pair<Float, Float>?>(Channel.CONFLATED)
    @Volatile private var moving = false
    private var lastSent: Pair<Float, Float>? = null
    private var reportedMoveError = false

    init {
        if (camera?.hasPtz == true) viewModelScope.launch { connectPtz(camera) }
        viewModelScope.launch(Dispatchers.IO) { runMoves() }
    }

    private suspend fun connectPtz(cam: Camera) = withContext(Dispatchers.IO) {
        try {
            val c = OnvifClient(cam.host, cam.onvifPort, cam.username, cam.password())
            c.connect()
            if (!c.supportsPtz) return@withContext
            val token = c.profiles().first().token
            client = c
            profile = token
            ptzReady = true
            presets = runCatching { c.presets(token) }.getOrDefault(emptyList())
        } catch (e: Exception) {
            message = "Pan/tilt unavailable: ${e.message}"
        }
    }

    /** x/y in -1..1 from the joystick (right/up positive). */
    fun move(x: Float, y: Float) {
        val cam = camera ?: return
        val px = q(if (cam.invertPan) -x else x)
        val py = q(if (cam.invertTilt) -y else y)
        moves.trySend(if (px == 0f && py == 0f) null else px to py)
    }

    fun release() {
        moves.trySend(null)
    }

    // Snap to 0.25 steps with a dead zone, so tiny finger jitter doesn't spam the camera.
    private fun q(v: Float): Float = if (abs(v) < 0.2f) 0f else (v * 4).roundToInt() / 4f

    private suspend fun runMoves() {
        for (cmd in moves) {
            val c = client ?: continue
            val token = profile ?: continue
            try {
                if (cmd == null) {
                    if (moving) c.stop(token)
                    moving = false
                    lastSent = null
                    reportedMoveError = false
                } else if (cmd != lastSent) {
                    c.continuousMove(token, cmd.first, cmd.second)
                    moving = true
                    lastSent = cmd
                }
            } catch (e: Exception) {
                if (!reportedMoveError) message = e.message
                reportedMoveError = true
            }
        }
    }

    fun gotoPreset(p: Preset) = ptzCall("Couldn't move to ${p.name}") { c, t ->
        c.gotoPreset(t, p.token)
    }

    fun savePreset(name: String) = ptzCall("Couldn't save position") { c, t ->
        c.setPreset(t, name)
        presets = c.presets(t)
        message = "Saved \"$name\""
    }

    fun deletePreset(p: Preset) = ptzCall("Couldn't remove ${p.name}") { c, t ->
        c.removePreset(t, p.token)
        presets = c.presets(t)
    }

    private fun ptzCall(failure: String, block: (OnvifClient, String) -> Unit) {
        val c = client ?: return
        val t = profile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(c, t)
            } catch (e: Exception) {
                message = "$failure: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        // Never leave the camera spinning if the screen closes mid-move.
        val c = client
        val t = profile
        if (moving && c != null && t != null) Thread { runCatching { c.stop(t) } }.start()
        moves.close()
    }
}
