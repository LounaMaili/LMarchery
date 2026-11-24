package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.mixed.EndScoringContext
import java.util.Collections

/** The exact profile zone, including its geometric score and both normalized radial limits. */
@ConsistentCopyVisibility
data class TargetZoneFrequency internal constructor(val zone: TargetZone, val frequency: SpatialFrequency)

/** A subset of the central score zone, with the same retained-impact denominator as other frequencies. */
@ConsistentCopyVisibility
data class GeometricXRing internal constructor(
    val label: String,
    val radiusNormalized: Float,
    val frequency: SpatialFrequency,
)

class TargetZoneDistribution internal constructor(
    val scope: GroupingSelectionScope?,
    val missPolicy: GroupingMissPolicy,
    val selectedEndCount: Int,
    val counts: GroupingImpactCounts,
    zones: List<TargetZoneFrequency>,
    val xRing: GeometricXRing?,
    outsideImpacts: List<SpatialOutsideImpact>,
) {
    /** All profile zones, ordered from the center to the edge, including empty zones. */
    val zones: List<TargetZoneFrequency> = Collections.unmodifiableList(zones.toList())

    /** Retained off-face positions and identities in input end/impact order. */
    val outsideImpacts: List<SpatialOutsideImpact> = Collections.unmodifiableList(outsideImpacts.toList())

    val outsideFace: SpatialFrequency get() = SpatialFrequency(outsideImpacts.size, counts.retainedImpactCount)
    val insideFace: SpatialFrequency
        get() = SpatialFrequency(counts.retainedImpactCount - outsideImpacts.size, counts.retainedImpactCount)
}

/**
 * Counts retained impacts by their current archived coordinates, independently of their scores.
 * A point on a circular boundary belongs to the inner zone. Off-face membership agrees with
 * [calculateSpatialDistribution], even where the rounded radius used for scoring differs.
 * Keep separate archives in separate calls and preserve their provenance at the call site.
 */
fun calculateTargetZoneDistribution(
    contexts: List<EndScoringContext>,
    missPolicy: GroupingMissPolicy = GroupingMissPolicy.INCLUDE_MISSES,
): TargetZoneDistribution {
    val scope = validateGroupingSelection(contexts)
    val target = scope?.configuration?.targetType
        ?: return TargetZoneDistribution(null, missPolicy, 0, emptyGroupingImpactCounts, emptyList(), null, emptyList())

    val zones = target.zones.sortedBy { it.outerRadiusNormalized }
    val radiiSquared = DoubleArray(zones.size) { index ->
        val radius = zones[index].outerRadiusNormalized.toDouble()
        radius * radius
    }
    val zoneCounts = IntArray(zones.size)
    val xRadius = target.innerTenRadiusNormalized.toDouble()
    val xRadiusSquared = xRadius * xRadius
    var xCount = 0
    val outside = mutableListOf<SpatialOutsideImpact>()
    var counts = emptyGroupingImpactCounts
    for (context in contexts) {
        counts += context.visitGroupingImpacts(missPolicy) { impact ->
            val x = impact.point.x.toDouble()
            val y = impact.point.y.toDouble()
            val xSquared = x * x
            val ySquared = y * y
            if (isWithinSquaredRadius(xSquared, ySquared, radiiSquared.last())) {
                val zone = firstContainingZone(radiiSquared, xSquared, ySquared)
                zoneCounts[zone]++
                if (target.xRingEnabled && zone == 0 && isWithinSquaredRadius(xSquared, ySquared, xRadiusSquared)) {
                    xCount++
                }
            } else {
                outside.add(SpatialOutsideImpact(context.end.uuid, impact.uuid, impact.point))
            }
        }
    }

    val retained = counts.retainedImpactCount
    val xRing = if (target.xRingEnabled) {
        GeometricXRing(target.xRingLabel, target.innerTenRadiusNormalized, SpatialFrequency(xCount, retained))
    } else null
    return TargetZoneDistribution(scope, missPolicy, contexts.size, counts,
        zones.mapIndexed { index, zone -> TargetZoneFrequency(zone, SpatialFrequency(zoneCounts[index], retained)) },
        xRing, outside)
}

/** The caller has established that the last zone contains the point. */
private fun firstContainingZone(radiiSquared: DoubleArray, xSquared: Double, ySquared: Double): Int {
    var lower = 0
    var upper = radiiSquared.lastIndex
    while (lower < upper) {
        val middle = lower + (upper - lower) / 2
        if (isWithinSquaredRadius(xSquared, ySquared, radiiSquared[middle])) upper = middle else lower = middle + 1
    }
    return lower
}
