package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.validateContextCollection

/** A selection's exact configuration and archived end status. */
@ConsistentCopyVisibility
data class GroupingSelectionScope internal constructor(val configuration: EndConfiguration, val status: EndStatus)

/** M counts refer to active impacts. Cancelled impacts have their own count. */
@ConsistentCopyVisibility
data class GroupingImpactCounts internal constructor(
    val retainedImpactCount: Int,
    val includedMissCount: Int,
    val excludedMissCount: Int,
    val cancelledImpactCount: Int,
) {
    internal operator fun plus(other: GroupingImpactCounts) = GroupingImpactCounts(
        Math.addExact(retainedImpactCount, other.retainedImpactCount),
        Math.addExact(includedMissCount, other.includedMissCount),
        Math.addExact(excludedMissCount, other.excludedMissCount),
        Math.addExact(cancelledImpactCount, other.cancelledImpactCount),
    )
}

internal val emptyGroupingImpactCounts = GroupingImpactCounts(0, 0, 0, 0)

internal fun validateGroupingSelection(contexts: List<EndScoringContext>): GroupingSelectionScope? {
    val first = contexts.firstOrNull() ?: return null
    val configuration = first.configuration
    val status = first.end.status
    require(contexts.all { it.configuration == configuration }) { "A grouping selection requires one exact end configuration." }
    require(contexts.all { it.end.status == status }) { "A grouping selection requires one archived end status." }
    validateContextCollection(contexts)
    return GroupingSelectionScope(configuration, status)
}

internal inline fun EndScoringContext.visitGroupingImpacts(
    missPolicy: GroupingMissPolicy,
    consume: (ArrowImpact) -> Unit,
): GroupingImpactCounts {
    var includedMisses = 0
    var excludedMisses = 0
    val active = end.activeImpacts
    for (impact in active) {
        if (impact.isMiss) {
            if (missPolicy == GroupingMissPolicy.EXCLUDE_MISSES) {
                excludedMisses++
                continue
            }
            includedMisses++
        }
        consume(impact)
    }
    return GroupingImpactCounts(active.size - excludedMisses, includedMisses, excludedMisses, end.cancellations.size)
}
