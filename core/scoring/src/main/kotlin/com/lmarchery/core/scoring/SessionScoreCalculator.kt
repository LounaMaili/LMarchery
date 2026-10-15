package com.lmarchery.core.scoring

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.target.TargetType

fun calculateEndScore(scoreFinals: Iterable<Int>): Int =
    scoreFinals.sumOf { score ->
        require(score >= 0) { "scoreFinal values must be >= 0." }
        score
    }

fun calculateEndScore(end: End): Int =
    calculateEndScore(end.impacts.map { it.scoreFinal })

fun calculateSessionScore(endScores: Iterable<Int>): Int =
    endScores.sumOf { endScore ->
        require(endScore >= 0) { "end score values must be >= 0." }
        endScore
    }

fun calculateSessionScore(session: Session): Int =
    calculateSessionScore(session.validatedEnds().map(::calculateEndScore))

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

fun calculateMaxPossibleScore(session: Session): Int =
    calculateMaxPossibleScore(
        targetType = session.targetType,
        arrowCountMode = session.arrowCountMode,
        arrowCountPerEnd = session.arrowCountPerEnd,
        plannedEndCount = session.plannedEndCount,
        recordedEndCount = session.ends.size,
        totalImpactCount = session.ends.sumOf { it.impacts.size },
    )

fun summarizeSessionScore(session: Session): SessionScoreSummary {
    val validatedEnds = session.validatedEnds()
    val validatedImpacts = validatedEnds.flatMap { it.impacts }
    val totalScore = calculateSessionScore(session)
    val maxPossibleScore = calculateMaxPossibleScore(session)
    val validatedEndCount = validatedEnds.size
    val validatedImpactCount = validatedImpacts.size

    return SessionScoreSummary(
        totalScore = totalScore,
        maxPossibleScore = maxPossibleScore,
        percentage = maxPossibleScore.takeIf { it > 0 }?.let { totalScore * 100.0 / it },
        averagePerEnd = validatedEndCount.takeIf { it > 0 }?.let { totalScore.toDouble() / it },
        averagePerArrow = validatedImpactCount.takeIf { it > 0 }?.let { totalScore.toDouble() / it },
        validatedEndCount = validatedEndCount,
        recordedEndCount = session.ends.size,
        validatedImpactCount = validatedImpactCount,
        recordedImpactCount = session.ends.sumOf { it.impacts.size },
        maxScoreCount = validatedImpacts.count { it.scoreFinal == session.targetType.maxScore },
        xCount = validatedImpacts.count { it.isX },
        missCount = validatedImpacts.count { it.isMiss },
        nineCount = validatedImpacts.count { it.scoreFinal == 9 },
    )
}

private fun Session.validatedEnds(): List<End> =
    ends.filter { it.status == EndStatus.VALIDATED }
