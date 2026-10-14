package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.sqrt

/** Shared radial convention; intermediate calculations use Double, the public radius remains Float. */
internal fun NormalizedPoint.distanceFromCenter(): Float {
    val xDouble = x.toDouble()
    val yDouble = y.toDouble()
    val distance = sqrt(xDouble * xDouble + yDouble * yDouble)
    require(distance <= Float.MAX_VALUE.toDouble()) {
        "Distance from center cannot be represented as a finite Float."
    }
    return distance.toFloat()
}
