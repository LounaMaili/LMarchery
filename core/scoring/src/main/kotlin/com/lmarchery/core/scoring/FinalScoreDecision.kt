package com.lmarchery.core.scoring

data class FinalScoreDecision(
    val scoreCalculated: Int,
    val scoreFinal: Int,
    val isCordDecisionManual: Boolean,
)
