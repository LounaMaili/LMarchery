package com.lmarchery.core.model.scoring

import java.time.LocalDateTime

/** A started request may be incomplete; only a validated FinalScoreDecision is storable. */
sealed interface ScoreDecisionRequest {
    data object AcceptGeometry : ScoreDecisionRequest

    data class JudgeBoundary(
        val boundary: BoundaryReference,
        val selected: BoundaryValue?,
        val decidedAt: LocalDateTime,
    ) : ScoreDecisionRequest

    data class ExceptionalCorrection(
        val selected: BoundaryValue?,
        val reason: String,
        val decidedAt: LocalDateTime,
    ) : ScoreDecisionRequest
}

enum class ScoreDecisionKind { GEOMETRY, BOUNDARY_JUDGMENT, EXCEPTIONAL_CORRECTION }

data class ScoreDecisionProvenance(
    val kind: ScoreDecisionKind,
    val decidedAt: LocalDateTime? = null,
    val boundary: BoundaryReference? = null,
    val reason: String? = null,
) {
    val isManual: Boolean get() = kind != ScoreDecisionKind.GEOMETRY

    init {
        when (kind) {
            ScoreDecisionKind.GEOMETRY -> require(decidedAt == null && boundary == null && reason == null)
            ScoreDecisionKind.BOUNDARY_JUDGMENT -> require(decidedAt != null && boundary != null && reason == null)
            ScoreDecisionKind.EXCEPTIONAL_CORRECTION -> require(decidedAt != null && boundary == null && !reason.isNullOrBlank())
        }
    }
}
