package com.lmarchery.core.scoring

import com.lmarchery.core.model.target.TargetZone

data class ScoreResult(
    val score: Int,
    val zone: TargetZone?,
    val distanceFromCenter: Float,
    val isX: Boolean,
    val isMiss: Boolean,
    val isNearCordon: Boolean,
    val cordonZoneAbove: Int?,
)
