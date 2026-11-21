package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.NormalizedPoint
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Internal coordinates originate in finite archived Floats widened exactly to Double. */
internal data class GroupingPoint(val x: Double, val y: Double)

// Canonicalize signed zero for geometric equality while retaining the source Float bits.
internal fun NormalizedPoint.toGroupingPoint() = GroupingPoint(x.toDouble() + 0.0, y.toDouble() + 0.0)

/** Exact circle membership for squares of archived Floats widened to Double. */
internal fun isWithinSquaredRadius(xSquared: Double, ySquared: Double, radiusSquared: Double): Boolean {
    val larger = maxOf(xSquared, ySquared)
    // The squares are exact. When the larger term is within half the radius squared,
    // both terms fit. From half to the limit the subtraction is exact (Sterbenz's lemma).
    return larger < radiusSquared / 2 || minOf(xSquared, ySquared) <= radiusSquared - larger
}

private val pointOrder = compareBy<GroupingPoint> { it.x }.thenBy { it.y }

internal fun measureUnsortedGrouping(points: MutableList<GroupingPoint>): GroupingMeasurements {
    points.sortWith(pointOrder)
    return measureGrouping(points)
}

/** Input is canonically sorted. Duplicate positions retain their statistical weight. */
private fun measureGrouping(points: List<GroupingPoint>): GroupingMeasurements {
    val unit = GroupingUnit.NORMALIZED
    if (points.isEmpty()) return GroupingMeasurements(unit, null, null)

    val first = points.first()
    if (first == points.last()) {
        val center = GroupingCenter(first.x, first.y, hypot(first.x, first.y))
        val dispersion = if (points.size > 1) GroupingDispersion(0.0, 0.0, 0.0, 0.0) else null
        return GroupingMeasurements(unit, center, dispersion)
    }

    val x = CompensatedSum()
    val y = CompensatedSum()
    for (point in points) {
        x.add(point.x)
        y.add(point.y)
    }
    val count = points.size.toDouble()
    val centerX = x.value / count
    val centerY = y.value / count
    val center = GroupingCenter(centerX, centerY, hypot(centerX, centerY))

    val squaredX = CompensatedSum()
    val squaredY = CompensatedSum()
    val radii = CompensatedSum()
    for (point in points) {
        val dx = point.x - centerX
        val dy = point.y - centerY
        squaredX.add(dx * dx)
        squaredY.add(dy * dy)
        radii.add(hypot(dx, dy))
    }
    val dispersion = GroupingDispersion(sqrt(squaredX.value / count), sqrt(squaredY.value / count),
        radii.value / count, diameter(points))
    return GroupingMeasurements(unit, center, dispersion)
}

/** Neumaier summation also retains small terms when the incoming term is larger than the running sum. */
internal class CompensatedSum {
    private var sum = 0.0
    private var correction = 0.0
    val value get() = sum + correction

    fun add(term: Double) {
        val next = sum + term
        correction += if (abs(sum) >= abs(term)) (sum - next) + term else (term - next) + sum
        sum = next
    }
}

private fun diameter(sorted: List<GroupingPoint>): Double {
    val points = ArrayList<GroupingPoint>(sorted.size)
    for (point in sorted) {
        if (point != points.lastOrNull()) points.add(point)
    }
    val hull = convexHull(points)
    if (hull.size == 2) return sqrt(squaredDistance(hull[0], hull[1]))

    // On a strict CCW hull, the opposite vertex maximizing edge distance advances monotonically.
    // Both endpoints of a parallel opposite edge are candidates when the distances tie.
    var opposite = 1
    var maxSquared = 0.0
    for (i in hull.indices) {
        val start = hull[i]
        val finish = hull[(i + 1) % hull.size]
        var next = (opposite + 1) % hull.size
        var direction = crossSign(start, finish, hull[opposite], hull[next])
        while (direction > 0) {
            opposite = next
            next = (opposite + 1) % hull.size
            direction = crossSign(start, finish, hull[opposite], hull[next])
        }
        val far = hull[opposite]
        maxSquared = maxOf(maxSquared, squaredDistance(start, far), squaredDistance(finish, far))
        if (direction == 0) {
            val tied = hull[next]
            maxSquared = maxOf(maxSquared, squaredDistance(start, tied), squaredDistance(finish, tied))
        }
    }
    return sqrt(maxSquared)
}

/** Andrew's monotone chain. Collinear runs contribute only their endpoints to the hull. */
private fun convexHull(sorted: List<GroupingPoint>): List<GroupingPoint> {
    fun half(points: List<GroupingPoint>): List<GroupingPoint> {
        val chain = mutableListOf<GroupingPoint>()
        for (point in points) {
            while (chain.size >= 2) {
                val start = chain[chain.lastIndex - 1]
                if (crossSign(start, chain.last(), start, point) > 0) break
                chain.removeAt(chain.lastIndex)
            }
            chain.add(point)
        }
        return chain.dropLast(1)
    }
    return half(sorted) + half(sorted.asReversed())
}

private fun squaredDistance(a: GroupingPoint, b: GroupingPoint): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/** Sign of (b - a) × (d - c), also when the two Double products nearly cancel. */
internal fun crossSign(a: GroupingPoint, b: GroupingPoint, c: GroupingPoint, d: GroupingPoint): Int {
    val positive = (b.x - a.x) * (d.y - c.y)
    val negative = (b.y - a.y) * (d.x - c.x)
    val determinant = positive - negative
    // Every nonzero Float difference/product here is normal in Double. This bound covers the
    // two differences, multiplication and subtraction errors before deciding the sign.
    val magnitude = abs(positive) + abs(negative)
    if (abs(determinant) > 8.0 * Math.ulp(1.0) * magnitude) return if (determinant > 0.0) 1 else -1
    if (magnitude == 0.0) return 0

    // BigDecimal(Double) preserves the exact binary value. valueOf would round via decimal text.
    fun delta(high: Double, low: Double) = BigDecimal(high).subtract(BigDecimal(low))
    val exactPositive = delta(b.x, a.x).multiply(delta(d.y, c.y))
    val exactNegative = delta(b.y, a.y).multiply(delta(d.x, c.x))
    return exactPositive.compareTo(exactNegative)
}
