package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import kotlin.math.abs
import kotlin.math.sqrt

const val DEFAULT_CORDON_TOLERANCE_NORMALIZED: Float = 0.015f

fun calculateScore(
    targetType: TargetType,
    point: NormalizedPoint,
    cordonToleranceNormalized: Float = DEFAULT_CORDON_TOLERANCE_NORMALIZED,
): ScoreResult {
    require(cordonToleranceNormalized >= 0f) {
        "cordonToleranceNormalized must be >= 0."
    }

    val distanceFromCenter = point.distanceFromCenter()
    if (distanceFromCenter > 1.0f) {
        return ScoreResult(
            score = 0,
            zone = null,
            distanceFromCenter = distanceFromCenter,
            isX = false,
            isMiss = true,
            isNearCordon = false,
            cordonZoneAbove = null,
        )
    }

    val zonesFromCenter = targetType.zones.sortedBy { it.outerRadiusNormalized }
    val zoneIndex = zonesFromCenter.indexOfFirst { distanceFromCenter <= it.outerRadiusNormalized }
    require(zoneIndex >= 0) {
        "No target zone contains distance $distanceFromCenter."
    }

    val zone = zonesFromCenter[zoneIndex]
    val zoneAbove = zonesFromCenter.getOrNull(zoneIndex - 1)
    val isNearCordon = zoneAbove != null &&
        abs(distanceFromCenter - zone.innerRadiusNormalized) < cordonToleranceNormalized

    return ScoreResult(
        score = zone.score,
        zone = zone,
        distanceFromCenter = distanceFromCenter,
        isX = targetType.xRingEnabled && distanceFromCenter <= targetType.innerTenRadiusNormalized,
        isMiss = false,
        isNearCordon = isNearCordon,
        cordonZoneAbove = if (isNearCordon) zoneAbove?.score else null,
    )
}

fun resolveFinalScore(
    result: ScoreResult,
    scoringMode: ScoringMode,
    userSelectedScore: Int? = null,
): FinalScoreDecision {
    require(userSelectedScore == null || userSelectedScore >= 0) {
        "userSelectedScore must be >= 0."
    }

    val scoreFinal = when (scoringMode) {
        ScoringMode.TRAINING -> result.score
        ScoringMode.COMPETITION -> result.cordonZoneAbove ?: result.score
        ScoringMode.MANUAL_CORDON -> userSelectedScore ?: result.score
    }

    return FinalScoreDecision(
        scoreCalculated = result.score,
        scoreFinal = scoreFinal,
        isCordDecisionManual = scoringMode == ScoringMode.MANUAL_CORDON && userSelectedScore != null,
    )
}

private fun NormalizedPoint.distanceFromCenter(): Float =
    sqrt((x * x + y * y).toDouble()).toFloat()
