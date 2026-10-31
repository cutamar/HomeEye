package app.homeeye.ui.list

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.homeeye.cameraRepository
import app.homeeye.data.Camera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListScreen(onOpen: (String) -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val repo = LocalContext.current.cameraRepository
    val cameras by repo.cameras.collectAsState()
    val thumbVersion by repo.thumbVersion.collectAsState()
    var deleting by remember { mutableStateOf<Camera?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("HomeEye", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (cameras.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add camera") },
                )
            }
        },
    ) { padding ->
        if (cameras.isEmpty()) {
            EmptyState(Modifier.padding(padding), onAdd)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(cameras, key = { it.id }) { cam ->
                    CameraCard(
                        camera = cam,
                        thumbVersion = thumbVersion,
                        onClick = { onOpen(cam.id) },
                        onEdit = { onEdit(cam.id) },
                        onDelete = { deleting = cam },
                    )
                }
            }
        }
    }

    deleting?.let { cam ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${cam.name}?") },
            text = { Text("This removes the camera from HomeEye. Nothing changes on the camera itself.") },
            confirmButton = {
                TextButton(onClick = { repo.delete(cam.id); deleting = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CameraCard(
    camera: Camera,
    thumbVersion: Int,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val repo = LocalContext.current.cameraRepository
    val thumb by produceState<ImageBitmap?>(null, camera.id, thumbVersion) {
        value = withContext(Dispatchers.IO) {
            repo.thumbnailFile(camera.id).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        }
    }
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0B0D10))) {
            thumb?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } ?: Icon(
                Icons.Outlined.Videocam, contentDescription = null,
                tint = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp).align(Alignment.Center),
            )
            // Bottom fade so the title stays readable on any picture
            Box(
                Modifier.fillMaxWidth().height(96.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            )
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(camera.name, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(camera.model, camera.host).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f),
                        )
                        if (camera.hasPtz) {
                            Spacer(Modifier.size(8.dp))
                            Icon(Icons.Outlined.OpenWith, contentDescription = "Can pan and tilt",
                                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Videocam, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("No cameras yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add an ONVIF camera on your Wi-Fi. HomeEye talks to it directly. No cloud, no account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.size(8.dp))
            Text("Add camera")
        }
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
            Text("Logins stay encrypted on this phone", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
