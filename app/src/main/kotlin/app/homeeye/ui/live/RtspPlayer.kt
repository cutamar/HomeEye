package app.homeeye.ui.live

import android.graphics.Bitmap
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.delay

sealed interface StreamStatus {
    data object Connecting : StreamStatus
    data object Live : StreamStatus
    data class Retrying(val reason: String) : StreamStatus
}

/** Lets the screen grab a frame from the video. */
class PlayerHandle {
    internal var textureView: TextureView? = null
    fun snapshot(): Bitmap? = textureView?.bitmap
}

@OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    url: String,
    forceTcp: Boolean,
    muted: Boolean,
    handle: PlayerHandle,
    onStatus: (StreamStatus) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onStatus by rememberUpdatedState(onStatus)
    val onTap by rememberUpdatedState(onTap)
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context)
            // Small buffers: a camera feed should be live, not smooth-but-late.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2000, 250, 500)
                    .build()
            )
            .build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    aspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onRenderedFirstFrame() {
                failed = false
                onStatus(StreamStatus.Live)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING && !failed) onStatus(StreamStatus.Connecting)
                if (state == Player.STATE_READY && player.videoSize.width > 0) onStatus(StreamStatus.Live)
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                onStatus(StreamStatus.Retrying(friendly(error)))
                retry++
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // (Re)load whenever the address or transport changes.
    LaunchedEffect(url, forceTcp) {
        onStatus(StreamStatus.Connecting)
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.playWhenReady = true
        player.prepare()
    }

    // Camera dropped (Wi-Fi blip, reboot): try again after a short pause.
    LaunchedEffect(retry) {
        if (retry == 0) return@LaunchedEffect
        delay(3_000)
        player.prepare()
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    // Stop streaming in the background; resume when visible again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.stop()
                Lifecycle.Event.ON_START -> if (player.playbackState == Player.STATE_IDLE) player.prepare()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Pinch to zoom, drag to look around while zoomed, double-tap to reset.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    val maxX = (scale - 1f) * size.width / 2f
                    val maxY = (scale - 1f) * size.height / 2f
                    offset = Offset(
                        (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                        (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { scale = 1f; offset = Offset.Zero },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also {
                    player.setVideoTextureView(it)
                    handle.textureView = it
                }
            },
            modifier = Modifier
                .aspectRatio(aspect)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

private fun friendly(e: PlaybackException): String {
    val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return when {
        "401" in text || "Unauthorized" in text -> "Camera rejected the login"
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the camera"
        else -> "Connection lost"
    }
}
