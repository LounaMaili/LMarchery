package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.normalizedDistanceFromCenter

/** Shared radial convention; intermediate calculations use Double, the public radius remains Float. */
internal fun NormalizedPoint.distanceFromCenter(): Float = normalizedDistanceFromCenter()
