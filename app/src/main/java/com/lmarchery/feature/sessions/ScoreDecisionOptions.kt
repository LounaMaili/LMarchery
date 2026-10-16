package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.findBoundaryHints
import kotlin.math.abs

/** Presentation choices from the actual profile, not a regulatory contact test or a decision. */
data class ScoreDecisionOptions(
    val nearby: List<BoundaryHint>,
    val other: List<BoundaryHint>,
    val legalValues: List<BoundaryValue>,
)

fun scoreDecisionOptions(target: TargetType, point: NormalizedPoint): ScoreDecisionOptions {
    val nearby = findBoundaryHints(target, point)
    val radius = point.normalizedDistanceFromCenter().toDouble()
    val other = target.scoreBoundaries().filter { definition -> nearby.none { it.reference == definition.reference } }
        .map { BoundaryHint(it.reference.type, it.reference.radiusNormalized, it.inner, it.outer,
            abs(radius - it.reference.radiusNormalized.toDouble())) }
        .sortedWith(compareBy<BoundaryHint> { it.radiusNormalized }.thenBy { it.type })
    val values = buildList {
        if (target.xRingEnabled) add(BoundaryValue(target.maxScore, true))
        addAll(target.zones.map { it.score }.distinct().sortedDescending().map { BoundaryValue(it) })
        add(BoundaryValue(0))
    }
    return ScoreDecisionOptions(nearby, other, values)
}
