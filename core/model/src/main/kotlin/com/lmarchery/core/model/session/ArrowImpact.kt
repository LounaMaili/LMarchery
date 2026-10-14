package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import java.time.LocalDateTime

data class ArrowImpact(
    val uuid: String,
    val index: Int,
    val point: NormalizedPoint,
    val distanceFromCenter: Float,
    val scoreCalculated: Int,
    val scoreFinal: Int,
    val isX: Boolean,
    val isMiss: Boolean,
    val inputMode: InputMode,
    val isCordDecisionManual: Boolean,
    val note: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    init {
        require(uuid.isNotBlank()) { "ArrowImpact uuid must not be blank." }
        require(index > 0) { "ArrowImpact index must be positive." }
        require(distanceFromCenter >= 0f) { "ArrowImpact distanceFromCenter must be >= 0." }
        require(scoreCalculated >= 0) { "ArrowImpact scoreCalculated must be >= 0." }
        require(scoreFinal >= 0) { "ArrowImpact scoreFinal must be >= 0." }
        require(!updatedAt.isBefore(createdAt)) {
            "ArrowImpact updatedAt must not be before createdAt."
        }
    }

    fun withFinalScore(
        scoreFinal: Int,
        isCordDecisionManual: Boolean,
        updatedAt: LocalDateTime,
    ): ArrowImpact {
        require(scoreFinal >= 0) { "scoreFinal must be >= 0." }
        require(!updatedAt.isBefore(createdAt)) {
            "updatedAt must not be before createdAt."
        }

        return copy(
            scoreFinal = scoreFinal,
            isCordDecisionManual = isCordDecisionManual,
            updatedAt = updatedAt,
        )
    }
}
