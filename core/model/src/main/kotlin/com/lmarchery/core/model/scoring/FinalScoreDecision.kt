package com.lmarchery.core.model.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType

/** Immutable validated result. No public scalar constructor or unchecked copy. */
class FinalScoreDecision private constructor(
    val targetType: TargetType,
    val point: NormalizedPoint,
    val distanceFromCenter: Float,
    val geometricValue: BoundaryValue,
    val value: BoundaryValue,
    val provenance: ScoreDecisionProvenance,
) {
    val scoreCalculated: Int get() = geometricValue.score
    val scoreFinal: Int get() = value.score
    val isX: Boolean get() = value.isX
    val isMiss: Boolean get() = value.isMiss
    val isCordDecisionManual: Boolean get() = provenance.kind == ScoreDecisionKind.BOUNDARY_JUDGMENT

    private val snapshot = Snapshot(targetType, point, distanceFromCenter, geometricValue, value, provenance)

    override fun equals(other: Any?): Boolean = other is FinalScoreDecision && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "FinalScoreDecision(")

    companion object {
        fun resolve(
            targetType: TargetType,
            point: NormalizedPoint,
            request: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry,
        ): FinalScoreDecision {
            val distance = point.normalizedDistanceFromCenter()
            val geometric = if (distance > 1f) BoundaryValue(0) else BoundaryValue(
                targetType.zones.sortedBy { it.outerRadiusNormalized }
                    .first { distance <= it.outerRadiusNormalized }.score,
                targetType.xRingEnabled && distance <= targetType.innerTenRadiusNormalized,
            )
            val (value, provenance) = when (request) {
                ScoreDecisionRequest.AcceptGeometry -> geometric to ScoreDecisionProvenance(ScoreDecisionKind.GEOMETRY)
                is ScoreDecisionRequest.JudgeBoundary -> {
                    val selected = requireNotNull(request.selected) { "Boundary judgment is incomplete: select a side." }
                    val actual = requireNotNull(targetType.scoreBoundaries().singleOrNull { it.reference == request.boundary }) {
                        "Judged boundary does not exist in this target profile."
                    }
                    require(selected == actual.inner || selected == actual.outer) {
                        "Boundary judgment must select one of the actual boundary's two values."
                    }
                    selected to ScoreDecisionProvenance(ScoreDecisionKind.BOUNDARY_JUDGMENT,
                        request.decidedAt, actual.reference)
                }
                is ScoreDecisionRequest.ExceptionalCorrection -> {
                    val selected = requireNotNull(request.selected) { "Exceptional correction is incomplete: select a value." }
                    require(request.reason.isNotBlank()) { "Exceptional correction requires a non-blank reason." }
                    selected to ScoreDecisionProvenance(ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
                        request.decidedAt, reason = request.reason)
                }
            }
            targetType.requireLegalValue(value)
            return FinalScoreDecision(targetType, point, distance, geometric, value, provenance)
        }
    }

    private data class Snapshot(
        val targetType: TargetType,
        val point: NormalizedPoint,
        val distance: Float,
        val geometric: BoundaryValue,
        val value: BoundaryValue,
        val provenance: ScoreDecisionProvenance,
    )
}
