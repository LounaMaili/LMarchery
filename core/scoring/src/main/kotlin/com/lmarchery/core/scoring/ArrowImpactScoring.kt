package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import java.time.Instant

fun scoreArrowImpact(
    uuid: String,
    index: Int,
    targetType: TargetType,
    point: NormalizedPoint,
    scoringMode: ScoringMode,
    userSelectedScore: Int? = null,
    inputMode: InputMode = InputMode.MANUAL,
    note: String? = null,
    createdAt: Instant,
    updatedAt: Instant = createdAt,
    cordonToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
    decision: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry,
): ArrowImpact {
    require(userSelectedScore == null) { "userSelectedScore is closed: use a typed score decision." }
    // This legacy UX parameter cannot affect the final value.
    require(cordonToleranceNormalized >= 0f) { "cordonToleranceNormalized must be >= 0." }
    val resolved = resolveFinalScore(targetType, point, scoringMode, decision)

    return ArrowImpact(
        uuid = uuid,
        index = index,
        point = point,
        inputMode = inputMode,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
        targetType = targetType,
        finalScoreDecision = resolved,
    )
}
