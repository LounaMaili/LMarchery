package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import java.util.Collections
import kotlin.math.hypot

/** Presentation identity only: no business UUID, date or score decision is created. */
data class ImpactPickContext(val sessionUuid: String, val endUuid: String, val ticket: Long)
data class ImpactPicking(
    val context: ImpactPickContext,
    val candidates: List<String> = emptyList(),
    val hasTapped: Boolean = false,
)

/** Actual Canvas bounds in target coordinates; a miss in a visible corner remains selectable. */
data class ImpactPickViewport(val halfWidth: Float = 1f, val halfHeight: Float = 1f) {
    init { require(halfWidth.isFinite() && halfWidth > 0f && halfHeight.isFinite() && halfHeight > 0f) }
    fun contains(point: NormalizedPoint) = point.x >= -halfWidth && point.x <= halfWidth &&
        point.y >= -halfHeight && point.y <= halfHeight
}

/** UX hit area, independent of scoring/cordon tolerance. Never guesses among nearby arrows. */
fun findImpactCandidates(impacts: List<ArrowImpact>, point: NormalizedPoint, toleranceNormalized: Float,
    viewport: ImpactPickViewport = ImpactPickViewport()): List<String> {
    require(toleranceNormalized.isFinite() && toleranceNormalized >= 0f) { "Selection tolerance must be finite and non-negative." }
    require(impacts.map { it.uuid }.distinct().size == impacts.size) { "Impact identities must be unique." }
    if (!viewport.contains(point)) return emptyList()
    return Collections.unmodifiableList(impacts.filter {
        viewport.contains(it.point) && hypot(it.point.x.toDouble() - point.x.toDouble(), it.point.y.toDouble() - point.y.toDouble()) <= toleranceNormalized.toDouble()
    }.sortedWith(compareBy<ArrowImpact> { it.index }.thenBy { it.uuid }).map { it.uuid })
}
