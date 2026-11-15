package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.hypot

/** Explicit drag of ONE already selected marker. No tap, selection or durable command. */
class ImpactDragGesture(
    pointerId: Long, private val size: CanvasSize, private val down: CanvasPoint,
    private val start: NormalizedPoint, hitRadiusPx: Float, private val touchSlopPx: Float,
) {
    private val pointer = PrecisionGesture(pointerId, size)
    private val center = normalizedPointToCanvas(start, size)
    val accepted: Boolean
    var dragged = false
        private set
    val finished get() = pointer.finished

    init {
        require(hitRadiusPx.isFinite() && hitRadiusPx >= 0f)
        require(touchSlopPx.isFinite() && touchSlopPx >= 0f)
        accepted = aimPointInsideCanvas(down, size) && aimPointInsideCanvas(center, size) &&
            hypot(down.x.toDouble() - center.x, down.y.toDouble() - center.y) <= hitRadiusPx.toDouble()
        if (!accepted) pointer.cancel()
    }

    fun move(id: Long, point: CanvasPoint, count: Int, consumed: Boolean = false): NormalizedPoint? {
        if (pointer.move(id, point, count, consumed) == null) return null
        if (hypot(point.x.toDouble() - down.x, point.y.toDouble() - down.y) > touchSlopPx.toDouble()) dragged = true
        // Keep the finger's grab offset: the marker never jumps to the finger on press.
        val moved = CanvasPoint(center.x + (point.x - down.x), center.y + (point.y - down.y))
        if (!aimPointInsideCanvas(moved, size)) { cancel(); return null }
        return if (dragged) canvasPointToNormalized(moved, size) else start
    }

    fun release(id: Long, point: CanvasPoint, count: Int, consumed: Boolean = false): NormalizedPoint? {
        val next = move(id, point, count, consumed)
        pointer.cancel()
        return next?.takeIf { dragged }
    }

    fun cancel() = pointer.cancel()
}
