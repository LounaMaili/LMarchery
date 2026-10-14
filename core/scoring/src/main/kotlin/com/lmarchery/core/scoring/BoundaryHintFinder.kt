package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.scoring.scoreBoundaries
import kotlin.math.abs

/**
 * Finds boundaries within the inclusive touch tolerance on either side of the point.
 * Results are ordered by distance, radius, then BoundaryType declaration order.
 * No score is selected or modified, and no legacy cordon fields are consulted.
 */
fun findBoundaryHints(
    targetType: TargetType,
    point: NormalizedPoint,
    touchToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
): List<BoundaryHint> {
    require(touchToleranceNormalized.isFinite() && touchToleranceNormalized >= 0f) {
        "touchToleranceNormalized must be finite and >= 0."
    }

    val distanceFromCenter = point.distanceFromCenter().toDouble()
    val tolerance = touchToleranceNormalized.toDouble()
    return targetType.scoreBoundaries().mapNotNull { boundary ->
        val distance = abs(distanceFromCenter - boundary.reference.radiusNormalized.toDouble())
        if (distance > tolerance) null else BoundaryHint(
            boundary.reference.type, boundary.reference.radiusNormalized,
            boundary.inner, boundary.outer, distance,
        )
    }.sortedWith(
        compareBy<BoundaryHint> { it.distanceToBoundaryNormalized }
            .thenBy { it.radiusNormalized }
            .thenBy { it.type },
    )
}
