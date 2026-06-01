package com.lmarchery.core.scoring

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.target.TargetType

fun calculateEndScore(scoreFinals: Iterable<Int>): Int =
    scoreFinals.sumOf { score ->
        require(score >= 0) { "scoreFinal values must be >= 0." }
        score
    }

fun calculateSessionScore(endScores: Iterable<Int>): Int =
    endScores.sumOf { endScore ->
        require(endScore >= 0) { "end score values must be >= 0." }
        endScore
    }

fun calculateMaxPossibleScore(
    targetType: TargetType,
    arrowCountMode: ArrowCountMode,
    arrowCountPerEnd: Int?,
    plannedEndCount: Int?,
    recordedEndCount: Int,
    totalImpactCount: Int,
): Int {
    require(recordedEndCount >= 0) { "recordedEndCount must be >= 0." }
    require(totalImpactCount >= 0) { "totalImpactCount must be >= 0." }
    require(plannedEndCount == null || plannedEndCount >= 0) {
        "plannedEndCount must be null or >= 0."
    }
    require(arrowCountPerEnd == null || arrowCountPerEnd > 0) {
        "arrowCountPerEnd must be null or > 0."
    }

    val arrowCount = when (arrowCountMode) {
        ArrowCountMode.FIXED -> {
            requireNotNull(arrowCountPerEnd) {
                "arrowCountPerEnd is required when arrowCountMode is FIXED."
            }
            val endCount = plannedEndCount ?: recordedEndCount
            endCount * arrowCountPerEnd
        }
        ArrowCountMode.FREE -> totalImpactCount
    }

    return arrowCount * targetType.maxScore
}
