package com.lmarchery.feature.scoring

import androidx.compose.ui.graphics.Color
import com.lmarchery.core.model.target.TargetZone
import java.util.Locale

internal fun zoneColor(zone: TargetZone): Color = when (zone.colorName.lowercase(Locale.US)) {
    "yellow" -> Color(0xFFFFD400)
    "red" -> Color(0xFFE53935)
    "blue" -> Color(0xFF1E88E5)
    "black" -> Color(0xFF212121)
    "white" -> Color(0xFFFFFFFF)
    else -> Color(0xFFBDBDBD)
}
