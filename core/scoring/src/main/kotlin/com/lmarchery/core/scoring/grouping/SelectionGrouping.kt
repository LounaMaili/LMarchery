package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.scoring.mixed.EndScoringContext
import java.util.Collections

/** Each end with a center contributes equally, including ends with just one impact. */
@ConsistentCopyVisibility
data class EndCenterMeans internal constructor(
    val contributingEndCount: Int,
    val meanX: Double,
    val meanY: Double,
    val meanDistanceFromTargetCenter: Double,
)

/** Each end with at least two retained impacts contributes equally. */
@ConsistentCopyVisibility
data class EndDispersionMeans internal constructor(
    val contributingEndCount: Int,
    val meanStandardDeviationX: Double,
    val meanStandardDeviationY: Double,
    val meanRadius: Double,
    val meanDiameter: Double,
)

@ConsistentCopyVisibility
data class EndGroupingMeans internal constructor(
    val unit: GroupingUnit,
    val center: EndCenterMeans?,
    val dispersion: EndDispersionMeans?,
)

class SelectionGrouping internal constructor(
    val scope: GroupingSelectionScope?,
    val missPolicy: GroupingMissPolicy,
    val counts: GroupingImpactCounts,
    ends: List<EndGrouping>,
    /** The center and dispersion of all retained impacts, each with equal weight. */
    val pooledNormalized: GroupingMeasurements,
    /** Equal weight per end with the corresponding available measure. */
    val endMeansNormalized: EndGroupingMeans,
) {
    /** The same order as the explicit selection. */
    val ends: List<EndGrouping> = Collections.unmodifiableList(ends.toList())

    fun pooledMeasurementsIn(unit: GroupingUnit): GroupingMeasurements =
        pooledNormalized.convertTo(unit, lengthFactor(unit))

    fun endMeansIn(unit: GroupingUnit): EndGroupingMeans {
        if (unit == GroupingUnit.NORMALIZED) return endMeansNormalized
        val factor = lengthFactor(unit)
        val center = endMeansNormalized.center?.let {
            it.copy(meanX = it.meanX * factor, meanY = it.meanY * factor,
                meanDistanceFromTargetCenter = it.meanDistanceFromTargetCenter * factor)
        }
        val dispersion = endMeansNormalized.dispersion?.let {
            it.copy(meanStandardDeviationX = it.meanStandardDeviationX * factor,
                meanStandardDeviationY = it.meanStandardDeviationY * factor,
                meanRadius = it.meanRadius * factor, meanDiameter = it.meanDiameter * factor)
        }
        return EndGroupingMeans(unit, center, dispersion)
    }

    // An empty selection has no nominal profile and no length to convert.
    private fun lengthFactor(unit: GroupingUnit): Double =
        scope?.let { unit.nominalLengthFactor(it.configuration) } ?: 1.0
}

/**
 * Calculates statistics for the supplied ends in one exact scope, including selected empty ends.
 * The caller establishes shared provenance and resolves any cross-archive overlap before calling.
 * Repeated domain identifiers, mixed configurations and mixed statuses are rejected.
 */
fun calculateSelectionGrouping(
    contexts: List<EndScoringContext>,
    missPolicy: GroupingMissPolicy = GroupingMissPolicy.INCLUDE_MISSES,
): SelectionGrouping {
    val scope = validateGroupingSelection(contexts)
    val pooledPoints = ArrayList<GroupingPoint>()
    var counts = emptyGroupingImpactCounts
    val ends = contexts.map { context ->
        val points = ArrayList<GroupingPoint>(context.end.activeImpacts.size)
        val endCounts = context.visitGroupingImpacts(missPolicy) { impact ->
            val point = impact.point.toGroupingPoint()
            points.add(point)
            pooledPoints.add(point)
        }
        counts += endCounts
        context.toEndGrouping(missPolicy, endCounts, points)
    }
    val pooled = ends.singleOrNull()?.normalized ?: measureUnsortedGrouping(pooledPoints)
    return SelectionGrouping(scope, missPolicy, counts, ends, pooled, meanEndMeasures(ends))
}

private fun meanEndMeasures(ends: List<EndGrouping>): EndGroupingMeans {
    // Keep presentation order independent of floating-point summation order.
    val ordered = ends.sortedBy { it.endUuid }
    val centers = ordered.mapNotNull { it.normalized.center }
    val dispersions = ordered.mapNotNull { it.normalized.dispersion }
    val center = centers.takeIf { it.isNotEmpty() }?.let {
        EndCenterMeans(it.size, it.meanOf(GroupingCenter::x), it.meanOf(GroupingCenter::y),
            it.meanOf(GroupingCenter::distanceFromTargetCenter))
    }
    val dispersion = dispersions.takeIf { it.isNotEmpty() }?.let {
        EndDispersionMeans(it.size, it.meanOf(GroupingDispersion::standardDeviationX),
            it.meanOf(GroupingDispersion::standardDeviationY), it.meanOf(GroupingDispersion::meanRadius),
            it.meanOf(GroupingDispersion::diameter))
    }
    return EndGroupingMeans(GroupingUnit.NORMALIZED, center, dispersion)
}

private inline fun <T> List<T>.meanOf(read: (T) -> Double): Double {
    val sum = CompensatedSum()
    for (item in this) sum.add(read(item))
    return sum.value / size
}
