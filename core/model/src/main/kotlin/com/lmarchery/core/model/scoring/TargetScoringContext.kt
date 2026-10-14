package com.lmarchery.core.model.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
import kotlin.math.sqrt

/** Same Float radius and Double intermediates as ADR 009. */
fun NormalizedPoint.normalizedDistanceFromCenter(): Float {
    val xDouble = x.toDouble()
    val yDouble = y.toDouble()
    val distance = sqrt(xDouble * xDouble + yDouble * yDouble)
    require(distance <= Float.MAX_VALUE.toDouble()) {
        "Distance from center cannot be represented as a finite Float."
    }
    return distance.toFloat()
}

/** Rebuilt from the immutable profile, never from a caller's hint or tolerance. */
fun TargetType.scoreBoundaries(): List<BoundaryDefinition> {
    val ordered = zones.sortedBy { it.outerRadiusNormalized }
    fun definition(type: BoundaryType, radius: Float): BoundaryDefinition {
        val innerZone = ordered.first { radius <= it.outerRadiusNormalized }
        val outerZone = ordered.firstOrNull { radius < it.outerRadiusNormalized }
        return BoundaryDefinition(
            BoundaryReference(type, radius),
            BoundaryValue(innerZone.score, xRingEnabled && radius <= innerTenRadiusNormalized),
            BoundaryValue(outerZone?.score ?: 0, xRingEnabled && radius < innerTenRadiusNormalized),
        )
    }
    return buildList {
        for (zone in ordered.dropLast(1)) add(definition(BoundaryType.SCORE_ZONES, zone.outerRadiusNormalized))
        if (xRingEnabled) add(definition(BoundaryType.X_RING, innerTenRadiusNormalized))
        add(definition(BoundaryType.TARGET_EDGE, ordered.last().outerRadiusNormalized))
    }
}

internal fun TargetType.requireLegalValue(value: BoundaryValue) {
    require(value.score == 0 || zones.any { it.score == value.score }) {
        "Final score must be M/0 or a score actually present in the target zones."
    }
    require(!value.isX || (xRingEnabled && value.score == maxScore)) {
        "X requires an enabled X ring and the target maximum score."
    }
}
