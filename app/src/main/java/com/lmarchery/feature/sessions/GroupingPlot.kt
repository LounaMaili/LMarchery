package com.lmarchery.feature.sessions

import com.lmarchery.core.scoring.grouping.EndGrouping
import com.lmarchery.core.scoring.grouping.GroupingCenter
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy

internal data class PlotPoint(val x: Double, val y: Double)
internal data class GroupingPlotImpact(val point: PlotPoint, val retained: Boolean)

internal data class GroupingPlot(
    val impacts: List<GroupingPlotImpact>,
    val mean: PlotPoint?,
) {
    /** One scale for both axes and the rings. Include the full face and all active positions. */
    fun layout(width: Double, height: Double): GroupingPlotLayout {
        var left = -1.0
        var top = -1.0
        var right = 1.0
        var bottom = 1.0
        impacts.forEach { impact ->
            val (x, y) = impact.point
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
        val scale = minOf(width / (right - left), height / (bottom - top))
        return GroupingPlotLayout(
            origin = PlotPoint((width - (right + left) * scale) / 2.0, (height - (bottom + top) * scale) / 2.0),
            radius = scale,
        )
    }
}

internal data class GroupingPlotLayout(val origin: PlotPoint, val radius: Double) {
    fun position(point: PlotPoint) = PlotPoint(origin.x + point.x * radius, origin.y + point.y * radius)
}

internal fun groupingPlot(source: EndGroupingSummarySource, grouping: EndGrouping): GroupingPlot {
    require(grouping.endUuid == source.end.uuid && grouping.configuration == source.configuration)
    val includeMisses = grouping.missPolicy == GroupingMissPolicy.INCLUDE_MISSES
    return GroupingPlot(
        source.end.activeImpacts.map { impact ->
            val point = impact.point
            GroupingPlotImpact(PlotPoint(point.x.toDouble(), point.y.toDouble()), includeMisses || !impact.isMiss)
        },
        grouping.normalized.center?.let { PlotPoint(it.x, it.y) },
    )
}

/** Archived coordinates increase to the right and down. */
internal fun GroupingCenter.directionLabel(): String {
    val vertical = when {
        y < 0 -> "le haut"
        y > 0 -> "le bas"
        else -> null
    }
    val horizontal = when {
        x < 0 -> "la gauche"
        x > 0 -> "la droite"
        else -> null
    }
    return listOfNotNull(vertical, horizontal).joinToString(" et ")
}
