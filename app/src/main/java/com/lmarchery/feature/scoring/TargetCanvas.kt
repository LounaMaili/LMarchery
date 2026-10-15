package com.lmarchery.feature.scoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import java.util.Locale

@Composable
fun TargetCanvas(
    targetType: TargetType,
    impacts: List<ArrowImpact>,
    enabled: Boolean,
    endUuid: String?,
    onImpactAdded: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnImpactAdded by rememberUpdatedState(onImpactAdded)
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
                .semantics { contentDescription = if (enabled) "Blason interactif LMarchery" else "Blason enregistré en consultation" }
                .pointerInput(endUuid, targetType.uuid, impacts.size, enabled) {
                    detectTapGestures { offset ->
                        if (enabled) {
                            currentOnImpactAdded(
                                canvasPointToNormalized(
                                    point = CanvasPoint(offset.x, offset.y),
                                    canvasSize = CanvasSize(
                                        widthPx = size.width.toFloat(),
                                        heightPx = size.height.toFloat(),
                                    ),
                                ),
                            )
                        }
                    }
                },
        ) {
            val diameterPx = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radiusPx = diameterPx / 2f

            targetType.zones
                .sortedByDescending(TargetZone::outerRadiusNormalized)
                .forEach { zone ->
                    drawCircle(
                        color = zoneColor(zone),
                        radius = radiusPx * zone.outerRadiusNormalized,
                        center = center,
                    )
                    drawCircle(
                        color = Color(0x66000000),
                        radius = radiusPx * zone.outerRadiusNormalized,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }

            drawLine(
                color = Color(0x55000000),
                start = Offset(center.x - radiusPx, center.y),
                end = Offset(center.x + radiusPx, center.y),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0x55000000),
                start = Offset(center.x, center.y - radiusPx),
                end = Offset(center.x, center.y + radiusPx),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )

            impacts.forEach { impact ->
                val point = normalizedPointToCanvas(
                    point = impact.point,
                    canvasSize = CanvasSize(widthPx = size.width, heightPx = size.height),
                )
                val centerOffset = Offset(point.x, point.y)
                val impactColor = if (impact.isMiss) Color(0xFFE53935) else Color(0xFF00C853)

                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = centerOffset,
                )
                drawCircle(
                    color = impactColor,
                    radius = 7.dp.toPx(),
                    center = centerOffset,
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.75f),
                    radius = 9.dp.toPx(),
                    center = centerOffset,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

private fun zoneColor(zone: TargetZone): Color = when (zone.colorName.lowercase(Locale.US)) {
    "yellow" -> Color(0xFFFFD400)
    "red" -> Color(0xFFE53935)
    "blue" -> Color(0xFF1E88E5)
    "black" -> Color(0xFF212121)
    "white" -> Color(0xFFFFFFFF)
    else -> Color(0xFFBDBDBD)
}
