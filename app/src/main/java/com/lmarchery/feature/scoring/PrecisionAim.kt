package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint

const val AIM_MAGNIFICATION = 4f

/** Pixel bounds, not the scoring circle: the square's corners remain legal misses. */
fun aimPointInsideCanvas(point: CanvasPoint, size: CanvasSize): Boolean =
    point.x.isFinite() && point.y.isFinite() && point.x >= 0f && point.y >= 0f &&
        point.x <= size.widthPx && point.y <= size.heightPx

/** One pointer owns a gesture. Cancellation is terminal, including a second finger. */
class PrecisionGesture(private val pointerId: Long, private val size: CanvasSize) {
    var point: NormalizedPoint? = null
        private set
    var finished: Boolean = false
        private set

    fun move(id: Long, position: CanvasPoint, pointerCount: Int, consumed: Boolean = false): NormalizedPoint? {
        if (finished) return null
        if (id != pointerId || pointerCount != 1 || consumed || !aimPointInsideCanvas(position, size)) {
            cancel()
            return null
        }
        return canvasPointToNormalized(position, size).also { point = it }
    }

    fun release(id: Long, position: CanvasPoint, pointerCount: Int, consumed: Boolean = false): NormalizedPoint? =
        move(id, position, pointerCount, consumed)?.also { finished = true }

    fun cancel() { point = null; finished = true }
}

data class AimLens(val center: CanvasPoint, val radiusPx: Float, val magnification: Float = AIM_MAGNIFICATION) {
    /** Same forward mapping as the target, followed by a visual-only magnification. */
    fun project(point: NormalizedPoint, aimedPoint: NormalizedPoint, size: CanvasSize): CanvasPoint {
        val source = normalizedPointToCanvas(point, size)
        val aimed = normalizedPointToCanvas(aimedPoint, size)
        return CanvasPoint(center.x + (source.x - aimed.x) * magnification,
            center.y + (source.y - aimed.y) * magnification)
    }
}

/** Opposite quadrant, with enough room for the border. No coordinate is clamped to a score zone. */
fun aimLens(aimedPoint: NormalizedPoint, size: CanvasSize): AimLens {
    val radius = size.diameterPx * .20f
    val margin = size.diameterPx * .025f
    return AimLens(CanvasPoint(
        if (aimedPoint.x <= 0f) size.widthPx - radius - margin else radius + margin,
        if (aimedPoint.y <= 0f) size.heightPx - radius - margin else radius + margin,
    ), radius)
}

/** Root guard: includes fingers outside the target and blocks their buttons until every finger lifts. */
class AimMultiTouchGuard {
    private var blocked = false
    fun reject(pointerCount: Int, anyPressed: Boolean): Boolean {
        if (pointerCount > 1) blocked = true
        val reject = blocked
        if (!anyPressed) blocked = false
        return reject
    }
}

/** Fit the whole precise target (and its lens) after scrolling it into view in landscape. */
fun precisionTargetMaxWidth(screenHeightDp: Float): Float {
    require(screenHeightDp.isFinite() && screenHeightDp > 0f)
    return minOf(480f, screenHeightDp * .65f)
}
