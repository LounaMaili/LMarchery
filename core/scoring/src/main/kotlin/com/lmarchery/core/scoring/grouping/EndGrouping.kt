package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.validateContextCollection
import java.util.Collections

/** Misses are classified by the stored final decision, independently of their position. */
enum class GroupingMissPolicy { INCLUDE_MISSES, EXCLUDE_MISSES }

/** NORMALIZED distances are in units of target radius. Physical units describe the nominal face. */
enum class GroupingUnit { NORMALIZED, CENTIMETERS, MILLIMETERS }

/** The mean coordinates are also the signed offsets from the origin. +x is right and +y is down. */
@ConsistentCopyVisibility
data class GroupingCenter internal constructor(val x: Double, val y: Double, val distanceFromTargetCenter: Double)

/** Population standard deviations, mean distance to the centroid and maximum pairwise distance. */
@ConsistentCopyVisibility
data class GroupingDispersion internal constructor(
    val standardDeviationX: Double,
    val standardDeviationY: Double,
    val meanRadius: Double,
    val diameter: Double,
)

@ConsistentCopyVisibility
data class GroupingMeasurements internal constructor(
    val unit: GroupingUnit,
    val center: GroupingCenter?,
    val dispersion: GroupingDispersion?,
)

@ConsistentCopyVisibility
data class EndGrouping internal constructor(
    val endUuid: String,
    val endIndex: Int,
    val status: EndStatus,
    val configuration: EndConfiguration,
    val missPolicy: GroupingMissPolicy,
    val impactCount: Int,
    val includedMissCount: Int,
    val excludedMissCount: Int,
    val normalized: GroupingMeasurements,
) {
    /** Converts the original normalized result using the nominal radius of this end's target. */
    fun measurementsIn(unit: GroupingUnit): GroupingMeasurements =
        normalized.convertTo(unit, unit.nominalLengthFactor(configuration))
}

internal fun GroupingUnit.nominalLengthFactor(configuration: EndConfiguration): Double {
    val radiusCm = configuration.targetType.diameterCm / 2.0
    return when (this) {
        GroupingUnit.NORMALIZED -> 1.0
        GroupingUnit.CENTIMETERS -> radiusCm
        GroupingUnit.MILLIMETERS -> radiusCm * 10.0
    }
}

internal fun GroupingMeasurements.convertTo(unit: GroupingUnit, factor: Double): GroupingMeasurements {
    if (this.unit == unit) return this
    val scaledCenter = center?.let {
        GroupingCenter(it.x * factor, it.y * factor, it.distanceFromTargetCenter * factor)
    }
    val scaledDispersion = dispersion?.let {
        GroupingDispersion(it.standardDeviationX * factor, it.standardDeviationY * factor,
            it.meanRadius * factor, it.diameter * factor)
    }
    return GroupingMeasurements(unit, scaledCenter, scaledDispersion)
}

/** An immutable series for one exact configuration, status and filter. No cross-end statistic is inferred. */
class EndGroupingSeries internal constructor(
    val configuration: EndConfiguration,
    val status: EndStatus,
    val missPolicy: GroupingMissPolicy,
    ends: List<EndGrouping>,
) {
    val ends: List<EndGrouping> = Collections.unmodifiableList(ends.toList())
}

/** Uses current archived coordinates of active impacts. No score, decision or trace is derived or changed. */
fun calculateEndGrouping(
    context: EndScoringContext,
    missPolicy: GroupingMissPolicy = GroupingMissPolicy.INCLUDE_MISSES,
): EndGrouping {
    val points = ArrayList<GroupingPoint>(context.end.activeImpacts.size)
    val counts = context.visitGroupingImpacts(missPolicy) { points.add(it.point.toGroupingPoint()) }
    return context.toEndGrouping(missPolicy, counts, points)
}

internal fun EndScoringContext.toEndGrouping(
    missPolicy: GroupingMissPolicy,
    counts: GroupingImpactCounts,
    points: MutableList<GroupingPoint>,
): EndGrouping = EndGrouping(end.uuid, end.index, end.status, configuration, missPolicy,
    counts.retainedImpactCount, counts.includedMissCount, counts.excludedMissCount, measureUnsortedGrouping(points))

/**
 * Groups in first-occurrence order after sorting by (archived index, UUID). Empty ends stay in their series.
 * Uses the mixed scoring collection identity rules. Pass independent archives in separate calls.
 */
fun summarizeEndGroupings(
    contexts: List<EndScoringContext>,
    missPolicy: GroupingMissPolicy = GroupingMissPolicy.INCLUDE_MISSES,
): List<EndGroupingSeries> {
    val ordered = contexts.sortedWith(compareBy<EndScoringContext> { it.end.index }.thenBy { it.end.uuid })
    validateContextCollection(ordered)
    val groups = ordered.groupBy { it.configuration to it.end.status }
    return Collections.unmodifiableList(groups.map { (key, ends) ->
        EndGroupingSeries(key.first, key.second, missPolicy, ends.map { calculateEndGrouping(it, missPolicy) })
    })
}
