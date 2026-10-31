package app.homeeye.ui.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Drag the knob to pan/tilt; let go to stop. Reports x/y in -1..1, right and up positive.
 */
@Composable
fun Joystick(
    onMove: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    knob: Dp = 64.dp,
    translucent: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val knobPos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val onMove by rememberUpdatedState(onMove)
    val onRelease by rememberUpdatedState(onRelease)
    val accent = MaterialTheme.colorScheme.primary
    val ring = Color.White.copy(alpha = if (translucent) 0.10f else 0.06f)

    Box(
        modifier
            .size(size)
            .semantics { contentDescription = "Pan and tilt control. Drag to move the camera." }
            .background(ring, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .pointerInput(Unit) {
                val radius = (size.toPx() - knob.toPx()) / 2f
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    fun update(p: Offset) {
                        var v = p - center
                        val len = sqrt(v.x * v.x + v.y * v.y)
                        if (len > radius) v = v * (radius / len)
                        scope.launch { knobPos.snapTo(v) }
                        onMove(v.x / radius, -v.y / radius)
                    }
                    update(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) change.consume()
                        update(change.position)
                    }
                    onRelease()
                    scope.launch { knobPos.animateTo(Offset.Zero, spring(dampingRatio = 0.6f)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Direction chevrons
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            for (angle in listOf(0f, 90f, 180f, 270f)) {
                rotate(angle, c) {
                    val tip = Offset(c.x, c.y - r + 14.dp.toPx())
                    val w = 7.dp.toPx()
                    val path = Path().apply {
                        moveTo(tip.x - w, tip.y + w)
                        lineTo(tip.x, tip.y)
                        lineTo(tip.x + w, tip.y + w)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.45f),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(knobPos.value.x.roundToInt(), knobPos.value.y.roundToInt()) }
                .size(knob)
                .background(accent.copy(alpha = if (translucent) 0.85f else 1f), CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.25f), CircleShape)
        )
    }
}
