package app.homeeye.ui.live

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.homeeye.cameraRepository
import app.homeeye.onvif.Preset
import app.homeeye.ui.theme.LiveGreen
import app.homeeye.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.movableContentOf
import androidx.activity.compose.BackHandler

private val Backdrop = Color(0xFF0B0D10)

@Composable
fun LiveScreen(cameraId: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: LiveViewModel = viewModel(key = cameraId) { LiveViewModel(app, cameraId) }
    val camera = vm.camera ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val handle = remember { PlayerHandle() }
    var status by remember { mutableStateOf<StreamStatus>(StreamStatus.Connecting) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var askPresetName by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var overlayVisible by remember { mutableStateOf(true) }

    val activity = context as? Activity
    FullscreenEffect(activity, landscape)

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    // Refresh the list thumbnail a few seconds after the picture comes up.
    LaunchedEffect(status == StreamStatus.Live) {
        if (status != StreamStatus.Live) return@LaunchedEffect
        delay(3_000)
        handle.snapshot()?.let { bmp ->
            withContext(Dispatchers.IO) {
                runCatching { Snapshots.saveThumbnail(bmp, app.cameraRepository.thumbnailFile(cameraId)) }
            }
            app.cameraRepository.thumbnailSaved()
        }
    }

    // In fullscreen, fade the controls out after a few seconds of no taps.
    LaunchedEffect(overlayVisible, landscape) {
        if (landscape && overlayVisible) { delay(4_000); overlayVisible = false }
    }

    fun snapshot() {
        val bmp = handle.snapshot()
        if (bmp == null) { vm.message = "No picture yet"; return }
        scope.launch {
            val uri = withContext(Dispatchers.IO) { Snapshots.saveToGallery(context, bmp, camera.name) }
            vm.message = if (uri != null) "Saved to Pictures/HomeEye" else "Couldn't save the photo"
        }
    }

    fun toggleFullscreen() {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // movableContentOf keeps the same player (and connection) when the layout
    // switches between portrait and fullscreen.
    val player = remember {
        movableContentOf { m: Modifier ->
            RtspPlayer(
            url = camera.rtspUrl(vm.hd),
            forceTcp = camera.forceTcp,
            muted = vm.muted,
            handle = handle,
            onStatus = { status = it },
            onTap = { overlayVisible = !overlayVisible },
            modifier = m,
        )
        }
    }

    BackHandler(enabled = landscape) { toggleFullscreen() }

    Surface(color = Backdrop, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        if (landscape) {
            Box(Modifier.fillMaxSize()) {
                player(Modifier.fillMaxSize())
                AnimatedVisibility(overlayVisible, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { toggleFullscreen() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Exit fullscreen") }
                            Text(camera.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
                            Spacer(Modifier.width(12.dp))
                            StatusPill(status)
                            Spacer(Modifier.weight(1f))
                            StreamControls(vm, hasSub = camera.subPath != null, onSnapshot = ::snapshot,
                                fullscreen = true, onFullscreen = ::toggleFullscreen)
                        }
                        if (vm.ptzReady) {
                            Joystick(
                                onMove = { x, y -> overlayVisible = true; vm.move(x, y) },
                                onRelease = vm::release,
                                size = 148.dp, knob = 56.dp, translucent = true,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                            )
                            PresetRow(
                                vm.presets, onGo = vm::gotoPreset, onAdd = { askPresetName = true },
                                onLongPress = { deleting = it },
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 24.dp, end = 200.dp),
                            )
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(camera.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            camera.model?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                        IconButton(onClick = { onEdit(cameraId) }) { Icon(Icons.Outlined.Edit, "Edit camera") }
                    }

                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                        player(Modifier.fillMaxWidth())
                        StatusPill(status, Modifier.align(Alignment.TopStart).padding(10.dp))
                    }

                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        StreamControls(vm, hasSub = camera.subPath != null, onSnapshot = ::snapshot,
                            fullscreen = false, onFullscreen = ::toggleFullscreen)
                    }

                    Spacer(Modifier.weight(1f))
                    if (vm.ptzReady) {
                        Joystick(onMove = vm::move, onRelease = vm::release, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Positions", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                        )
                        PresetRow(
                            vm.presets, onGo = vm::gotoPreset, onAdd = { askPresetName = true },
                            onLongPress = { deleting = it },
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                    } else if (camera.hasPtz) {
                        Text(
                            "Connecting pan/tilt…", color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(Modifier.weight(1f))
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
    }

    if (askPresetName) {
        var name by remember { mutableStateOf("Position ${vm.presets.size + 1}") }
        AlertDialog(
            onDismissRequest = { askPresetName = false },
            title = { Text("Save this position") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = { vm.savePreset(name.trim().ifEmpty { "Position" }); askPresetName = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { askPresetName = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove \"${p.name}\"?") },
            text = { Text("The saved position is deleted from the camera.") },
            confirmButton = { TextButton(onClick = { vm.deletePreset(p); deleting = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StreamControls(
    vm: LiveViewModel,
    hasSub: Boolean,
    onSnapshot: () -> Unit,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasSub) QualityToggle(hd = vm.hd, onChange = { vm.hd = it })
        IconButton(onClick = { vm.muted = !vm.muted }) {
            Icon(
                if (vm.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                if (vm.muted) "Unmute" else "Mute",
            )
        }
        IconButton(onClick = onSnapshot) { Icon(Icons.Outlined.PhotoCamera, "Take photo") }
        IconButton(onClick = onFullscreen) {
            Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                if (fullscreen) "Exit fullscreen" else "Fullscreen")
        }
    }
}

@Composable
private fun QualityToggle(hd: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(3.dp),
    ) {
        for ((label, value) in listOf("SD" to false, "HD" to true)) {
            val selected = hd == value
            Box(
                Modifier.clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .combinedClickable(onClick = { onChange(value) })
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(
    presets: List<Preset>,
    onGo: (Preset) -> Unit,
    onAdd: () -> Unit,
    onLongPress: (Preset) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets, key = { it.token }) { p ->
            Chip(
                icon = { Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(16.dp)) },
                text = p.name.ifBlank { "Position ${p.token}" },
                onClick = { onGo(p) },
                onLongClick = { onLongPress(p) },
            )
        }
        item {
            Chip(icon = { Icon(Icons.Outlined.Add, null, Modifier.size(16.dp)) }, text = "Save current", onClick = onAdd)
        }
    }
}

@Composable
private fun Chip(icon: @Composable () -> Unit, text: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            // Mostly opaque so labels stay readable over bright video in fullscreen
            .background(Color(0xD91C2028))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPill(status: StreamStatus, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        StreamStatus.Live -> LiveGreen to "LIVE"
        StreamStatus.Connecting -> Color.White.copy(alpha = 0.6f) to "Connecting…"
        is StreamStatus.Retrying -> WarnAmber to "${status.reason} · retrying"
    }
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** Hides the system bars in landscape and restores everything when leaving the screen. */
@Composable
private fun FullscreenEffect(activity: Activity?, landscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(activity, landscape) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (landscape) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
            // Video screen is always dark: light status bar icons.
            controller.isAppearanceLightStatusBars = false
        }
        onDispose {}
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let {
                val c = WindowCompat.getInsetsController(it, view)
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
