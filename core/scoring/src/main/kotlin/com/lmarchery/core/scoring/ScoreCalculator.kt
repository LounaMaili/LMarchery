package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import kotlin.math.abs

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
    targetType: TargetType,
    point: NormalizedPoint,
    scoringMode: ScoringMode,
    request: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry,
): FinalScoreDecision = when (scoringMode) {
    // Modes do not constitute a human judgment; no UX proximity promotes a score.
    ScoringMode.TRAINING, ScoringMode.COMPETITION, ScoringMode.MANUAL_CORDON ->
        FinalScoreDecision.resolve(targetType, point, request)
}

@Deprecated("Use targetType, point and a typed decision request.")
@Suppress("UNUSED_PARAMETER")
fun resolveFinalScore(
    result: ScoreResult,
    scoringMode: ScoringMode,
    userSelectedScore: Int? = null,
): FinalScoreDecision = throw IllegalArgumentException("Target context and a typed score decision are required.")
