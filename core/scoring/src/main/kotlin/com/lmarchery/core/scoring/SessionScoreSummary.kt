package com.lmarchery.core.scoring

data class SessionScoreSummary(
    val totalScore: Int,
    val maxPossibleScore: Int,
    val percentage: Double?,
    val averagePerEnd: Double?,
    val averagePerArrow: Double?,
    val validatedEndCount: Int,
    val recordedEndCount: Int,
    val validatedImpactCount: Int,
    val recordedImpactCount: Int,
    val maxScoreCount: Int,
    val xCount: Int,
    val missCount: Int,
    val nineCount: Int = 0,
)
