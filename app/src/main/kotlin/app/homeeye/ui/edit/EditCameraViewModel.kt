package app.homeeye.ui.edit

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.homeeye.cameraRepository
import app.homeeye.data.Camera
import app.homeeye.data.CameraProbe
import app.homeeye.data.ProbeResult
import app.homeeye.data.Secrets
import app.homeeye.onvif.DiscoveredCamera
import app.homeeye.onvif.Discovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val result: ProbeResult) : TestState
    data class Failed(val message: String) : TestState
}

class EditCameraViewModel(app: Application, private val cameraId: String?) : AndroidViewModel(app) {
    private val repo = app.cameraRepository
    private val existing = cameraId?.let(repo::get)
    val isNew = existing == null

    var name by mutableStateOf(existing?.name.orEmpty())
    var host by mutableStateOf(existing?.host.orEmpty())
    var username by mutableStateOf(existing?.username.orEmpty())
    var password by mutableStateOf(existing?.password().orEmpty())
    var onvifPort by mutableStateOf((existing?.onvifPort ?: 2020).toString())
    var forceTcp by mutableStateOf(existing?.forceTcp ?: true)
    var invertPan by mutableStateOf(existing?.invertPan ?: false)
    var invertTilt by mutableStateOf(existing?.invertTilt ?: false)

    var test by mutableStateOf<TestState>(TestState.Idle)
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanned by mutableStateOf(false)
        private set
    val found = mutableStateListOf<DiscoveredCamera>()

    /** Set when saving failed the connection test; the screen asks "save anyway?". */
    var saveAnywayReason by mutableStateOf<String?>(null)
        private set

    private var testJob: Job? = null

    val canSave get() = host.isNotBlank() && onvifPort.toIntOrNull() != null

    /** Any edit to connection fields makes an earlier test result stale. */
    fun connectionChanged() {
        if (test !is TestState.Running) test = TestState.Idle
    }

    fun scan() {
        if (scanning) return
        scanning = true
        found.clear()
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("homeeye-discovery").apply { setReferenceCounted(false); acquire() }
            try {
                withContext(Dispatchers.IO) {
                    val targets = phoneIpv4(ctx)?.let(Discovery::subnetHosts).orEmpty()
                    Discovery.probe(timeoutMs = 3000, unicastTargets = targets) { cam ->
                        viewModelScope.launch(Dispatchers.Main) { if (found.none { it.host == cam.host }) found += cam }
                    }
                }
            } catch (_: Exception) {
                // No network: just show "nothing found"
            } finally {
                lock.release()
                scanning = false
                scanned = true
            }
        }
    }

    fun pick(cam: DiscoveredCamera) {
        host = cam.host
        onvifPort = cam.port.toString()
        if (name.isBlank()) name = cam.hardware ?: cam.name ?: ""
        connectionChanged()
    }

    fun runTest() = startTest(onSuccess = {}, onFailure = {})

    /** Saves after a successful test. If the test fails, asks whether to save anyway. */
    fun save(onDone: () -> Unit) {
        (test as? TestState.Ok)?.let { store(it.result); onDone(); return }
        startTest(
            onSuccess = { store(it); onDone() },
            onFailure = { saveAnywayReason = it },
        )
    }

    private fun startTest(onSuccess: (ProbeResult) -> Unit, onFailure: (String) -> Unit) {
        val port = onvifPort.toIntOrNull() ?: return
        testJob?.cancel()
        test = TestState.Running
        testJob = viewModelScope.launch {
            try {
                val r = CameraProbe.probe(host.trim(), port, username.trim(), password)
                test = TestState.Ok(r)
                onSuccess(r)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = e.message ?: "Couldn't connect"
                test = TestState.Failed(msg)
                onFailure(msg)
            }
        }
    }

    fun saveAnyway(onDone: () -> Unit) {
        saveAnywayReason = null
        store(null)
        onDone()
    }

    fun dismissSaveAnyway() { saveAnywayReason = null }

    private fun store(probe: ProbeResult?) {
        val base = existing ?: Camera(name = "", host = "", username = "", passwordEnc = "")
        val cam = base.copy(
            name = name.trim().ifEmpty { probe?.info?.model ?: host.trim() },
            host = host.trim(),
            onvifPort = onvifPort.toIntOrNull() ?: 2020,
            username = username.trim(),
            passwordEnc = Secrets.encrypt(password),
            forceTcp = forceTcp,
            invertPan = invertPan,
            invertTilt = invertTilt,
        ).let { c ->
            if (probe == null) c else c.copy(
                rtspPort = probe.rtspPort,
                mainPath = probe.mainPath,
                subPath = probe.subPath,
                model = probe.info.model.ifEmpty { null },
                hasPtz = probe.hasPtz,
            )
        }
        repo.upsert(cam)
    }

    private fun phoneIpv4(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }
}
