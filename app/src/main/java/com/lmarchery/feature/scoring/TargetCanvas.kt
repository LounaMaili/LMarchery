package com.lmarchery.feature.scoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import java.util.Locale

enum class TargetInteraction { READ_ONLY, ADD_IMPACT, REPOSITION }

@Composable
fun TargetCanvas(
    targetType: TargetType,
    impacts: List<ArrowImpact>,
    interaction: TargetInteraction,
    endUuid: String?,
    onPointSelected: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
    selectedImpactUuid: String? = null,
    candidate: NormalizedPoint? = null,
    preciseAim: Boolean = false,
    onAimStart: (NormalizedPoint) -> Long? = { null },
    onAimMove: (Long, NormalizedPoint) -> Unit = { _, _ -> },
    onAimFinish: (Long, NormalizedPoint) -> Unit = { _, _ -> },
    onAimCancel: (Long) -> Unit = {},
) {
    val currentOnPointSelected by rememberUpdatedState(onPointSelected)
    val move by rememberUpdatedState(onAimMove)
    val finish by rememberUpdatedState(onAimFinish)
    val cancel by rememberUpdatedState(onAimCancel)
    var canvasPixels by remember { mutableStateOf(IntSize.Zero) }
    val isDark = isSystemInDarkTheme()
    val borderColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .background(
                color = if (isDark) Color(0xFF111318) else Color(0xFFF6F7FB),
                shape = RoundedCornerShape(16.dp),
            )
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = when (interaction) {
                    TargetInteraction.ADD_IMPACT -> if (preciseAim) "Blason de visée précise — nouvelle flèche non enregistrée" else "Blason interactif LMarchery"
                    TargetInteraction.REPOSITION -> "Blason de repositionnement — proposition non enregistrée"
                    TargetInteraction.READ_ONLY -> "Blason enregistré en consultation"
                } }
                .onSizeChanged { canvasPixels = it }
                .pointerInput(endUuid, targetType.uuid, impacts.size, interaction, selectedImpactUuid, preciseAim, canvasPixels) {
                    if (interaction == TargetInteraction.READ_ONLY) return@pointerInput
                    if (!preciseAim) {
                        detectTapGestures { offset ->
                            currentOnPointSelected(canvasPointToNormalized(CanvasPoint(offset.x, offset.y),
                                CanvasSize(size.width.toFloat(), size.height.toFloat())))
                        }
                    } else awaitEachGesture {
                        val down = awaitFirstDown()
                        val gesture = PrecisionGesture(down.id.value, CanvasSize(size.width.toFloat(), size.height.toFloat()))
                        val point = gesture.move(down.id.value, CanvasPoint(down.position.x, down.position.y), currentEvent.changes.size)
                        // Capture this handler's original context; an old canvas must not start on a new end.
                        val token = point?.let(onAimStart)
                        if (token != null) {
                            down.consume() // Precise mode owns the target; scroll outside it instead.
                            try {
                                while (!gesture.finished) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.singleOrNull() ?: break
                                    val position = CanvasPoint(change.position.x, change.position.y)
                                    val next = if (change.pressed) gesture.move(change.id.value, position,
                                        event.changes.size, change.isConsumed)
                                    else gesture.release(change.id.value, position, event.changes.size, change.isConsumed)
                                    change.consume()
                                    if (next == null) break
                                    if (change.pressed) move(token, next) else finish(token, next)
                                }
                            } finally {
                                // Includes system cancellation, disposal, mode/identity/size change and rotation.
                                cancel(token) // An already finished token is harmless; never discards a released candidate.
                            }
                        }
                    }
                },
        ) {
            val geometry = CanvasSize(size.width, size.height)
            drawTargetScene(targetType, impacts, geometry, selectedImpactUuid, candidate)
            if (preciseAim && candidate != null) {
                val lens = aimLens(candidate, geometry)
                val center = Offset(lens.center.x, lens.center.y)
                val radius = lens.radiusPx
                val mask = Path().apply { addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)) }
                clipPath(mask) {
                    drawCircle(if (isDark) Color(0xFF111318) else Color(0xFFF6F7FB), radius, center)
                    val source = normalizedPointToCanvas(candidate, geometry)
                    translate(center.x - source.x * lens.magnification, center.y - source.y * lens.magnification) {
                        scale(lens.magnification, pivot = Offset.Zero) {
                            drawTargetScene(targetType, impacts, geometry, selectedImpactUuid, candidate)
                        }
                    }
                }
                drawCircle(Color.White, radius, center, style = Stroke(4.dp.toPx()))
                drawCircle(Color.Black, radius, center, style = Stroke(1.5.dp.toPx()))
                drawAimReticle(center)
            }
        }
    }
}

/** One renderer for the target and the magnifier: same rings, colors, stored points and candidate. */
private fun DrawScope.drawTargetScene(target: TargetType, impacts: List<ArrowImpact>, geometry: CanvasSize,
    selectedImpactUuid: String?, candidate: NormalizedPoint?) {
    val center = Offset(geometry.centerX, geometry.centerY)
    val radius = geometry.radiusPx
    target.zones.sortedByDescending(TargetZone::outerRadiusNormalized).forEach { zone ->
        drawCircle(zoneColor(zone), radius * zone.outerRadiusNormalized, center)
        drawCircle(Color(0x66000000), radius * zone.outerRadiusNormalized, center, style = Stroke(1.dp.toPx()))
    }
    drawLine(Color(0x55000000), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y),
        1.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Color(0x55000000), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius),
        1.dp.toPx(), cap = StrokeCap.Round)
    impacts.forEach { impact ->
        val point = normalizedPointToCanvas(impact.point, geometry).let { Offset(it.x, it.y) }
        drawCircle(Color.White, 9.dp.toPx(), point)
        drawCircle(if (impact.isMiss) Color(0xFFE53935) else Color(0xFF00C853), 7.dp.toPx(), point)
        drawCircle(Color.Black.copy(alpha = .75f), 9.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
        if (impact.uuid == selectedImpactUuid) drawCircle(Color(0xFFFF9800), 13.dp.toPx(), point, style = Stroke(3.dp.toPx()))
    }
    candidate?.let { drawAimReticle(normalizedPointToCanvas(it, geometry).let { p -> Offset(p.x, p.y) }) }
}

private fun DrawScope.drawAimReticle(center: Offset) {
    drawCircle(Color.White, 12.dp.toPx(), center, style = Stroke(4.dp.toPx()))
    drawCircle(Color(0xFFAA00FF), 12.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    val d = 8.dp.toPx()
    drawLine(Color(0xFFAA00FF), center - Offset(d, d), center + Offset(d, d), 3.dp.toPx())
    drawLine(Color(0xFFAA00FF), center + Offset(-d, d), center + Offset(d, -d), 3.dp.toPx())
}

private fun zoneColor(zone: TargetZone): Color = when (zone.colorName.lowercase(Locale.US)) {
    "yellow" -> Color(0xFFFFD400)
    "red" -> Color(0xFFE53935)
    "blue" -> Color(0xFF1E88E5)
    "black" -> Color(0xFF212121)
    "white" -> Color(0xFFFFFFFF)
    else -> Color(0xFFBDBDBD)
}
