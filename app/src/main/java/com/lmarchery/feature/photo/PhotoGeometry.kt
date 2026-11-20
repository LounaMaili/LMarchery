package com.lmarchery.feature.photo

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.hypot

/** Fractions of the upright image, with origin in its upper left corner. */
internal data class PhotoPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f)
    }
}

internal data class PhotoCalibration(
    val left: PhotoPoint? = null,
    val top: PhotoPoint? = null,
    val right: PhotoPoint? = null,
    val bottom: PhotoPoint? = null,
) {
    fun anchors() = listOf(left, top, right, bottom)

    fun withAnchor(index: Int, point: PhotoPoint) = when (index) {
        0 -> copy(left = point)
        1 -> copy(top = point)
        2 -> copy(right = point)
        3 -> copy(bottom = point)
        else -> error("Unknown photo anchor")
    }

    fun circle(width: Int, height: Int): PhotoCircle? {
        val l = left ?: return null
        val t = top ?: return null
        val r = right ?: return null
        val b = bottom ?: return null
        val horizontalRadius = (r.x - l.x) * width / 2
        val verticalRadius = (b.y - t.y) * height / 2
        if (minOf(horizontalRadius, verticalRadius) < 32f) return null
        val radius = (horizontalRadius + verticalRadius) / 2
        val cx = (r.x + l.x) * width / 2
        val cy = (b.y + t.y) * height / 2
        if (abs(horizontalRadius - verticalRadius) > radius * .08f ||
            abs(l.y * height - cy) > radius * .08f ||
            abs(r.y * height - cy) > radius * .08f ||
            abs(t.x * width - cx) > radius * .08f ||
            abs(b.x * width - cx) > radius * .08f ||
            cx - radius <= 0f || cy - radius <= 0f || cx + radius >= width || cy + radius >= height
        ) return null
        return PhotoCircle(cx, cy, radius)
    }
}

internal data class PhotoCircle(val centerX: Float, val centerY: Float, val radius: Float) {
    fun normalize(point: PhotoPoint, width: Int, height: Int): NormalizedPoint? {
        val x = (point.x * width - centerX) / radius
        val y = (point.y * height - centerY) / radius
        return if (hypot(x, y) <= 1f) NormalizedPoint(x, y) else null
    }
}
