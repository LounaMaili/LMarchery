package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.mixed.EndScoringContext
import java.util.Collections
import kotlin.math.floor

/** Uniform grid covering [-1, 1] per axis. Columns run right and rows run down. */
data class SpatialGrid(val columns: Int, val rows: Int) {
    init {
        require(columns in 1..MAX_DIMENSION && rows in 1..MAX_DIMENSION) {
            "Spatial grid dimensions must be between 1 and $MAX_DIMENSION."
        }
    }

    val cellAreaInSquaredTargetRadii: Double get() = 4.0 / (columns * rows)

    companion object {
        const val MAX_DIMENSION = 256
    }
}

/** Percentage denominator includes every retained impact, even when the category excludes some. */
@ConsistentCopyVisibility
data class SpatialFrequency internal constructor(val count: Int, val retainedImpactCount: Int) {
    val percentageOfRetained: Double?
        get() = if (retainedImpactCount == 0) null else 100.0 * count / retainedImpactCount
}

@ConsistentCopyVisibility
data class SpatialCell internal constructor(
    val frequency: SpatialFrequency,
    val areaInSquaredTargetRadii: Double,
) {
    /** Observed count per full rectangular cell area, including at the target boundary. */
    val impactsPerSquaredTargetRadius: Double?
        get() = if (frequency.retainedImpactCount == 0) null else frequency.count / areaInSquaredTargetRadii
}

/** x negatives are left, y negatives are above. Signed zeros belong to the axis. */
@ConsistentCopyVisibility
data class SpatialAxisDistribution internal constructor(
    val negative: SpatialFrequency,
    val onAxis: SpatialFrequency,
    val positive: SpatialFrequency,
)

/** Clockwise from +x. Each sector includes its first ray and excludes its last ray. */
enum class SpatialSector {
    DEGREES_0_TO_45,
    DEGREES_45_TO_90,
    DEGREES_90_TO_135,
    DEGREES_135_TO_180,
    DEGREES_180_TO_225,
    DEGREES_225_TO_270,
    DEGREES_270_TO_315,
    DEGREES_315_TO_360,
}

/** Current archived position of a retained impact outside the nominal circular face. */
@ConsistentCopyVisibility
data class SpatialOutsideImpact internal constructor(
    val endUuid: String,
    val impactUuid: String,
    val point: NormalizedPoint,
)

class SpatialDistribution internal constructor(
    val scope: GroupingSelectionScope?,
    val missPolicy: GroupingMissPolicy,
    val selectedEndCount: Int,
    val counts: GroupingImpactCounts,
    val grid: SpatialGrid,
    private val cells: IntArray,
    private val sectors: IntArray,
    val x: SpatialAxisDistribution,
    val y: SpatialAxisDistribution,
    val origin: SpatialFrequency,
    outsideImpacts: List<SpatialOutsideImpact>,
) {
    /** Outside impacts keep their source coordinates and traverse in input end/impact order. */
    val outsideImpacts: List<SpatialOutsideImpact> = Collections.unmodifiableList(outsideImpacts.toList())

    val outsideFace: SpatialFrequency get() = frequency(outsideImpacts.size)
    val insideFace: SpatialFrequency get() = frequency(counts.retainedImpactCount - outsideImpacts.size)

    /** Zero-based column and row. Only the nominal circular face contributes to the grid. */
    fun cell(column: Int, row: Int): SpatialCell {
        require(column in 0 until grid.columns && row in 0 until grid.rows) { "Cell is outside the spatial grid." }
        return SpatialCell(frequency(cells[row * grid.columns + column]), grid.cellAreaInSquaredTargetRadii)
    }

    /** Counts on- and off-face impacts. The origin has its own bucket. */
    fun sector(sector: SpatialSector): SpatialFrequency = frequency(sectors[sector.ordinal])

    private fun frequency(count: Int) = SpatialFrequency(count, counts.retainedImpactCount)
}

/**
 * Counts retained impacts from one explicit selection without calculating a hull or any scores.
 * Keep independent archive selections separate unless the caller has established their provenance and overlap.
 */
fun calculateSpatialDistribution(
    contexts: List<EndScoringContext>,
    grid: SpatialGrid = SpatialGrid(32, 32),
    missPolicy: GroupingMissPolicy = GroupingMissPolicy.INCLUDE_MISSES,
): SpatialDistribution {
    val scope = validateGroupingSelection(contexts)
    val cells = IntArray(grid.columns * grid.rows)
    val sectors = IntArray(SpatialSector.entries.size)
    val horizontal = IntArray(3)
    val vertical = IntArray(3)
    val outside = mutableListOf<SpatialOutsideImpact>()
    var origin = 0
    var counts = emptyGroupingImpactCounts
    for (context in contexts) {
        counts += context.visitGroupingImpacts(missPolicy) { impact ->
            val x = impact.point.x.toDouble()
            val y = impact.point.y.toDouble()
            horizontal[axisBucket(x)]++
            vertical[axisBucket(y)]++
            if (x == 0.0 && y == 0.0) origin++ else sectors[sectorAt(x, y).ordinal]++
            if (isWithinSquaredRadius(x * x, y * y, 1.0)) {
                val column = cellIndex(x, grid.columns)
                val row = cellIndex(y, grid.rows)
                cells[row * grid.columns + column]++
            } else {
                outside.add(SpatialOutsideImpact(context.end.uuid, impact.uuid, impact.point))
            }
        }
    }
    val retained = counts.retainedImpactCount
    return SpatialDistribution(scope, missPolicy, contexts.size, counts, grid, cells, sectors,
        horizontal.axisDistribution(retained), vertical.axisDistribution(retained), SpatialFrequency(origin, retained), outside)
}

private fun axisBucket(coordinate: Double): Int = when {
    coordinate < 0.0 -> 0
    coordinate > 0.0 -> 2
    else -> 1
}

private fun IntArray.axisDistribution(total: Int) = SpatialAxisDistribution(
    SpatialFrequency(this[0], total), SpatialFrequency(this[1], total), SpatialFrequency(this[2], total),
)

private fun cellIndex(coordinate: Double, dimension: Int): Int {
    // Floor before translating, so a tiny negative coordinate still falls left/above the origin line.
    val scaled = floor(coordinate * dimension).toInt()
    return minOf(dimension - 1, (scaled + dimension) / 2)
}

private fun sectorAt(x: Double, y: Double): SpatialSector = when {
    y >= 0.0 && x > 0.0 -> if (y < x) SpatialSector.DEGREES_0_TO_45 else SpatialSector.DEGREES_45_TO_90
    y > 0.0 -> if (y > -x) SpatialSector.DEGREES_90_TO_135 else SpatialSector.DEGREES_135_TO_180
    x < 0.0 -> if (-y < -x) SpatialSector.DEGREES_180_TO_225 else SpatialSector.DEGREES_225_TO_270
    x < -y -> SpatialSector.DEGREES_270_TO_315
    else -> SpatialSector.DEGREES_315_TO_360
}
