package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.FinalScoreDecision
import java.time.Instant

/** Evidence of a position change, issued only by ArrowImpact.reposition. Not a score-decision log. */
class ImpactReposition private constructor(
    val impactUuid: String,
    val impactCreatedAt: Instant,
    val inputMode: InputMode,
    val previousDecision: FinalScoreDecision,
    val previousUpdatedAt: Instant,
    val newPoint: NormalizedPoint,
    val changedAt: Instant,
    val reason: String?,
) {
    val previousPoint: NormalizedPoint get() = previousDecision.point
    val invalidatedManualDecision: FinalScoreDecision?
        get() = previousDecision.takeIf { it.provenance.isManual }

    private val snapshot = Snapshot(impactUuid, impactCreatedAt, inputMode, previousDecision,
        previousUpdatedAt, newPoint, changedAt, reason)

    override fun equals(other: Any?): Boolean = other is ImpactReposition && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("Snapshot(", "ImpactReposition(")

    internal companion object {
        fun from(impact: ArrowImpact, newPoint: NormalizedPoint, changedAt: Instant, reason: String?): ImpactReposition {
            require(newPoint != impact.point) { "Reposition requires a different point." }
            require(!changedAt.isBefore(impact.updatedAt)) { "Reposition must not predate the previous impact revision." }
            require(reason == null || reason.isNotBlank()) { "A supplied reposition reason must not be blank." }
            require(!impact.finalScoreDecision.provenance.isManual || !reason.isNullOrBlank()) {
                "Invalidating a manual score decision requires a non-blank reposition reason."
            }
            return ImpactReposition(impact.uuid, impact.createdAt, impact.inputMode, impact.finalScoreDecision,
                impact.updatedAt, newPoint, changedAt, reason)
        }
    }

    private data class Snapshot(
        val impactUuid: String, val impactCreatedAt: Instant, val inputMode: InputMode,
        val previousDecision: FinalScoreDecision, val previousUpdatedAt: Instant,
        val newPoint: NormalizedPoint, val changedAt: Instant, val reason: String?,
    )
}
