package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.min

data class CanvasPoint(
    val x: Float,
    val y: Float,
)

data class CanvasSize(
    val widthPx: Float,
    val heightPx: Float,
) {
    init {
        require(widthPx.isFinite() && widthPx > 0f) { "widthPx must be positive." }
        require(heightPx.isFinite() && heightPx > 0f) { "heightPx must be positive." }
    }

    val diameterPx: Float = min(widthPx, heightPx)
    val radiusPx: Float = diameterPx / 2f
    val centerX: Float = widthPx / 2f
    val centerY: Float = heightPx / 2f
}

fun canvasPointToNormalized(
    point: CanvasPoint,
    canvasSize: CanvasSize,
): NormalizedPoint = NormalizedPoint(
    x = (point.x - canvasSize.centerX) / canvasSize.radiusPx,
    y = (point.y - canvasSize.centerY) / canvasSize.radiusPx,
)

fun normalizedPointToCanvas(
    point: NormalizedPoint,
    canvasSize: CanvasSize,
): CanvasPoint = CanvasPoint(
    x = canvasSize.centerX + point.x * canvasSize.radiusPx,
    y = canvasSize.centerY + point.y * canvasSize.radiusPx,
)
