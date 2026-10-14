package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
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
    val zonesFromCenter = targetType.zones.sortedBy { it.outerRadiusNormalized }

    fun hint(type: BoundaryType, radius: Float): BoundaryHint? {
        val distanceToBoundary = abs(distanceFromCenter - radius.toDouble())
        if (distanceToBoundary > tolerance) return null

        // Read the actual sides from the definition; no score +/- 1 or epsilon sampling.
        val innerZone = zonesFromCenter.first { radius <= it.outerRadiusNormalized }
        val outerZone = zonesFromCenter.firstOrNull { radius < it.outerRadiusNormalized }
        return BoundaryHint(
            type = type,
            radiusNormalized = radius,
            inner = BoundaryValue(
                score = innerZone.score,
                isX = targetType.xRingEnabled && radius <= targetType.innerTenRadiusNormalized,
            ),
            outer = BoundaryValue(
                score = outerZone?.score ?: 0,
                isX = targetType.xRingEnabled && radius < targetType.innerTenRadiusNormalized,
            ),
            distanceToBoundaryNormalized = distanceToBoundary,
        )
    }

    return buildList {
        for (zone in zonesFromCenter.dropLast(1)) {
            hint(BoundaryType.SCORE_ZONES, zone.outerRadiusNormalized)?.let(::add)
        }
        if (targetType.xRingEnabled) {
            hint(BoundaryType.X_RING, targetType.innerTenRadiusNormalized)?.let(::add)
        }
        hint(BoundaryType.TARGET_EDGE, zonesFromCenter.last().outerRadiusNormalized)?.let(::add)
    }.sortedWith(
        compareBy<BoundaryHint> { it.distanceToBoundaryNormalized }
            .thenBy { it.radiusNormalized }
            .thenBy { it.type },
    )
}
