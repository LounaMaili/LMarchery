package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.hypot

/** A selection tap must not become a drag, a scroll, a second-pointer gesture or an out-of-bounds tap. */
class ImpactPickGesture(pointerId: Long, size: CanvasSize, private val down: CanvasPoint, private val touchSlopPx: Float) {
    private val pointer = PrecisionGesture(pointerId, size)
    init {
        require(aimPointInsideCanvas(down, size))
        require(touchSlopPx.isFinite() && touchSlopPx >= 0f)
    }
    val finished get() = pointer.finished
    fun move(id: Long, point: CanvasPoint, pointerCount: Int, consumed: Boolean = false): Boolean {
        if (hypot(point.x.toDouble() - down.x.toDouble(), point.y.toDouble() - down.y.toDouble()) > touchSlopPx.toDouble()) {
            cancel(); return false
        }
        return pointer.move(id, point, pointerCount, consumed) != null
    }
    fun release(id: Long, point: CanvasPoint, pointerCount: Int, consumed: Boolean = false): NormalizedPoint? =
        if (move(id, point, pointerCount, consumed)) pointer.release(id, point, pointerCount, consumed) else null
    fun cancel() = pointer.cancel()
}
