package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.scoreArrowImpact
import java.time.Clock
import java.util.UUID

data class ScoringDemoState(
    val targetType: TargetType = ClassicTargetFixtures.classic40,
    val scoringMode: ScoringMode = ScoringMode.TRAINING,
    val impacts: List<ArrowImpact> = emptyList(),
    val maxImpacts: Int = targetType.defaultArrowCount,
) {
    init {
        require(maxImpacts > 0) { "maxImpacts must be positive." }
        require(impacts.size <= maxImpacts) { "impacts size must not exceed maxImpacts." }
    }

    val canAddImpact: Boolean = impacts.size < maxImpacts
    val totalScore: Int = impacts.sumOf { it.scoreFinal }
    val maxPossibleScore: Int = maxImpacts * targetType.maxScore

    fun addImpact(
        point: NormalizedPoint,
        clock: Clock = Clock.systemUTC(),
        uuid: String = UUID.randomUUID().toString(),
    ): ScoringDemoState {
        if (!canAddImpact) return this

        val now = clock.instant()
        val nextIndex = impacts.size + 1
        val impact = scoreArrowImpact(
            uuid = uuid,
            index = nextIndex,
            targetType = targetType,
            point = point,
            scoringMode = scoringMode,
            createdAt = now,
        )

        return copy(impacts = impacts + impact)
    }

    fun reset(): ScoringDemoState = copy(impacts = emptyList())
}
