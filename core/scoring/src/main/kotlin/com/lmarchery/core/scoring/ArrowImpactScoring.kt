package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import java.time.LocalDateTime

fun scoreArrowImpact(
    uuid: String,
    index: Int,
    targetType: TargetType,
    point: NormalizedPoint,
    scoringMode: ScoringMode,
    userSelectedScore: Int? = null,
    inputMode: InputMode = InputMode.MANUAL,
    note: String? = null,
    createdAt: LocalDateTime,
    updatedAt: LocalDateTime = createdAt,
    cordonToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
): ArrowImpact {
    val result = calculateScore(
        targetType = targetType,
        point = point,
        cordonToleranceNormalized = cordonToleranceNormalized,
    )
    val decision = resolveFinalScore(
        result = result,
        scoringMode = scoringMode,
        userSelectedScore = userSelectedScore,
    )

    return ArrowImpact(
        uuid = uuid,
        index = index,
        point = point,
        distanceFromCenter = result.distanceFromCenter,
        scoreCalculated = decision.scoreCalculated,
        scoreFinal = decision.scoreFinal,
        isX = result.isX,
        isMiss = result.isMiss,
        inputMode = inputMode,
        isCordDecisionManual = decision.isCordDecisionManual,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
