package app.homeeye.ui.edit

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.homeeye.ui.theme.LiveGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCameraScreen(cameraId: String?, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditCameraViewModel = viewModel { EditCameraViewModel(app, cameraId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Add camera" else "Edit camera") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.save(onDone) }, enabled = vm.canSave && vm.test !is TestState.Running) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.isNew) DiscoverySection(vm)

            SectionLabel("Camera")
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("Name") }, placeholder = { Text("Living room") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.host, onValueChange = { vm.host = it; vm.connectionChanged() },
                label = { Text("IP address") }, placeholder = { Text("192.168.0.100") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )

            SectionLabel("Login")
            Text(
                "The camera's own account (for Tapo: Camera settings → Advanced → Camera Account), not your cloud login.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = vm.username, onValueChange = { vm.username = it; vm.connectionChanged() },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            var showPassword by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = vm.password, onValueChange = { vm.password = it; vm.connectionChanged() },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
            )

            AdvancedSection(vm)

            FilledTonalButton(
                onClick = vm::runTest,
                enabled = vm.canSave && vm.test !is TestState.Running,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (vm.test is TestState.Running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting…")
                } else {
                    Text("Test connection")
                }
            }
            TestResult(vm.test)
            Spacer(Modifier.height(24.dp))
        }
    }

    vm.saveAnywayReason?.let { reason ->
        AlertDialog(
            onDismissRequest = vm::dismissSaveAnyway,
            title = { Text("Couldn't connect") },
            text = { Text("$reason\n\nSave anyway? HomeEye will use the usual stream addresses and try again when you open it.") },
            confirmButton = { TextButton(onClick = { vm.saveAnyway(onDone) }) { Text("Save anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissSaveAnyway) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun DiscoverySection(vm: EditCameraViewModel) {
    OutlinedButton(
        onClick = vm::scan,
        enabled = !vm.scanning,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp),
    ) {
        if (vm.scanning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Looking for cameras…")
        } else {
            Icon(Icons.Outlined.WifiFind, null)
            Spacer(Modifier.width(8.dp))
            Text("Find cameras on Wi-Fi")
        }
    }
    if (vm.found.isNotEmpty()) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            vm.found.forEachIndexed { i, cam ->
                if (i > 0) HorizontalDivider()
                val selected = vm.host == cam.host
                ListItem(
                    modifier = Modifier.clickable { vm.pick(cam) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Videocam, null) },
                    headlineContent = { Text(cam.hardware ?: cam.name ?: "Camera") },
                    supportingContent = { Text("${cam.host}:${cam.port}") },
                    trailingContent = {
                        if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
    } else if (vm.scanned && !vm.scanning) {
        Text(
            "No cameras answered. Check the camera is on the same Wi-Fi with ONVIF enabled, or enter its IP below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvancedSection(vm: EditCameraViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = vm.onvifPort, onValueChange = { vm.onvifPort = it.filter(Char::isDigit); vm.connectionChanged() },
                label = { Text("ONVIF port") }, supportingText = { Text("Tapo uses 2020, most others 80") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            SwitchRow("Stream over TCP", "Steadier picture on weak Wi-Fi", vm.forceTcp) { vm.forceTcp = it }
            SwitchRow("Reverse left/right", null, vm.invertPan) { vm.invertPan = it }
            SwitchRow("Reverse up/down", "For ceiling-mounted cameras", vm.invertTilt) { vm.invertTilt = it }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun TestResult(state: TestState) {
    when (state) {
        is TestState.Ok -> {
            val r = state.result
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = LiveGreen)
                        Spacer(Modifier.width(8.dp))
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                    }
                    Detail("Camera", listOf(r.info.manufacturer, r.info.model).filter { it.isNotBlank() }.joinToString(" "))
                    Detail("Firmware", r.info.firmware)
                    Detail("HD stream", r.mainResolution)
                    r.subResolution?.let { Detail("SD stream", it) }
                    Detail("Pan / tilt", if (r.hasPtz) "Yes" else "No")
                }
            }
        }
        is TestState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        else -> Unit
    }
}

@Composable
private fun Detail(label: String, value: String) {
    if (value.isBlank()) return
    Row {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}
