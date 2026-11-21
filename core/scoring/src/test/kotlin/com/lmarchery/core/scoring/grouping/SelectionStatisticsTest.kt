package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.MixedScoringException
import com.lmarchery.core.scoring.mixed.MixedScoringIssue
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.scoreArrowImpact
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

class SelectionStatisticsTest {
    private val now = Instant.parse("2026-11-21T08:00:00Z")
    private val classic = configuration(ClassicTargetFixtures.classic40)

    @Test fun `empty selection and known empty end distinguish scope and preserve absent measures`() {
        val empty = end(1)
        for (contexts in listOf(emptyList(), listOf(empty))) {
            val report = calculateSelectionGrouping(contexts)
            assertEquals(contexts.size, report.ends.size)
            assertEquals(contexts.firstOrNull()?.configuration, report.scope?.configuration)
            assertEquals(GroupingMissPolicy.INCLUDE_MISSES, report.missPolicy)
            assertEquals(GroupingImpactCounts(0, 0, 0, 0), report.counts)
            for (unit in GroupingUnit.entries) {
                assertEquals(GroupingMeasurements(unit, null, null), report.pooledMeasurementsIn(unit))
                assertEquals(EndGroupingMeans(unit, null, null), report.endMeansIn(unit))
            }
            val spatial = calculateSpatialDistribution(contexts, SpatialGrid(3, 2))
            assertEquals(report.scope, spatial.scope)
            assertEquals(contexts.size, spatial.selectedEndCount)
            assertEquals(0, spatial.cell(2, 1).frequency.count)
            assertNull(spatial.cell(2, 1).frequency.percentageOfRetained)
            assertNull(spatial.cell(2, 1).impactsPerSquaredTargetRadius)
            assertNull(spatial.outsideFace.percentageOfRetained)
        }
    }

    @Test fun `global center weighs impacts while the end averages retain separate contributing counts`() {
        val a = end(1, listOf(point(-.75f), point(.25f)))
        val b = end(2, List(4) { point(.5f) })
        val c = end(3, listOf(point(1f)))
        val empty = end(4)
        val selection = mutableListOf(b, empty, a, c)
        val result = calculateSelectionGrouping(selection)
        assertEquals(selection.map { calculateEndGrouping(it) }, result.ends)
        val reversed = calculateSelectionGrouping(selection.reversed())
        selection.clear()
        assertEquals(4, result.ends.size)
        assertFailsWith<UnsupportedOperationException> { (result.ends as MutableList).clear() }
        assertEquals(GroupingImpactCounts(7, 0, 0, 0), result.counts)
        assertEquals(result.pooledNormalized, reversed.pooledNormalized)
        assertEquals(result.endMeansNormalized, reversed.endMeansNormalized)
        assertEquals(2.5 / 7, result.pooledNormalized.center?.x)
        assertEquals(1.75, result.pooledNormalized.dispersion?.diameter)
        val centers = assertNotNull(result.endMeansNormalized.center)
        assertEquals(3, centers.contributingEndCount)
        assertEquals(1.25 / 3, centers.meanX)
        assertEquals(1.75 / 3, centers.meanDistanceFromTargetCenter)
        val dispersions = assertNotNull(result.endMeansNormalized.dispersion)
        assertEquals(2, dispersions.contributingEndCount)
        assertEquals(.5, dispersions.meanDiameter)
        assertEquals(.25, dispersions.meanStandardDeviationX)
        assertEquals(.25, dispersions.meanRadius)
    }

    @Test fun `one retained point has a position but spread requires two including across ends`() {
        val a = end(1, listOf(point(.25f, -.25f)))
        val empty = end(2)
        val single = calculateSelectionGrouping(listOf(a, empty))
        assertEquals(calculateEndGrouping(a).normalized.center, single.pooledNormalized.center)
        assertNull(single.pooledNormalized.dispersion)
        assertNull(single.endMeansNormalized.dispersion)
        val b = end(3, listOf(point(.25f, -.25f)))
        val two = calculateSelectionGrouping(listOf(a, b, empty))
        assertEquals(GroupingDispersion(0.0, 0.0, 0.0, 0.0), two.pooledNormalized.dispersion)
        assertNull(two.endMeansNormalized.dispersion)
        assertEquals(2, two.endMeansNormalized.center?.contributingEndCount)
    }

    @Test fun `physical conversions use the common exact custom profile`() {
        val custom = configuration(ClassicTargetFixtures.classic40.copy(
            uuid = id(999), isBuiltIn = false, name = "Synthetic 55 cm", diameterCm = 55,
        ))
        val a = end(1, listOf(point(-.5f), point(.5f)), custom)
        val b = end(2, listOf(point(.25f), point(.75f)), custom)
        val result = calculateSelectionGrouping(listOf(a, b))
        assertEquals(custom, result.scope?.configuration)
        assertEquals(34.375, result.pooledMeasurementsIn(GroupingUnit.CENTIMETERS).dispersion?.diameter)
        assertEquals(343.75, result.pooledMeasurementsIn(GroupingUnit.MILLIMETERS).dispersion?.diameter)
        assertEquals(20.625, result.endMeansIn(GroupingUnit.CENTIMETERS).dispersion?.meanDiameter)
        assertEquals(206.25, result.endMeansIn(GroupingUnit.MILLIMETERS).dispersion?.meanDiameter)
        assertEquals(2, result.endMeansIn(GroupingUnit.CENTIMETERS).dispersion?.contributingEndCount)
    }

    @Test fun `both calculations reject mixed configuration or status even if one end is empty`() {
        val a = end(1, listOf(point(.5f)))
        val otherDistance = end(2, config = classic.copy(distanceM = 25))
        val otherStatus = end(3, emptyList(), validated = true)
        for (contexts in listOf(listOf(a, otherDistance), listOf(a, otherStatus))) {
            assertFailsWith<IllegalArgumentException> { calculateSelectionGrouping(contexts) }
            assertFailsWith<IllegalArgumentException> { calculateSpatialDistribution(contexts) }
        }
        val validated = end(4, listOf(point(.5f)), validated = true)
        assertEquals(EndStatus.VALIDATED, calculateSelectionGrouping(listOf(validated)).scope?.status)
    }

    @Test fun `archive scoped calls keep conflicting UUIDs separate and repeated impacts are errors`() {
        val a = end(1, listOf(point(.5f)))
        val b = end(1, listOf(point(-.75f)))
        assertEquals(.5, calculateSelectionGrouping(listOf(a)).pooledNormalized.center?.x)
        assertEquals(-.75, calculateSelectionGrouping(listOf(b)).pooledNormalized.center?.x)
        assertEquals(1, calculateSpatialDistribution(listOf(a)).x.positive.count)
        assertEquals(1, calculateSpatialDistribution(listOf(b)).x.negative.count)
        assertEquals(MixedScoringIssue.DUPLICATE_END,
            assertFailsWith<MixedScoringException> { calculateSelectionGrouping(listOf(a, b)) }.code)
        assertEquals(MixedScoringIssue.DUPLICATE_END,
            assertFailsWith<MixedScoringException> { calculateSpatialDistribution(listOf(a, b)) }.code)
        val repeatedImpact = end(2, impacts = a.end.impacts)
        assertEquals(MixedScoringIssue.DUPLICATE_IMPACT,
            assertFailsWith<MixedScoringException> { calculateSpatialDistribution(listOf(a, repeatedImpact)) }.code)
    }

    @Test fun `final decisions filter M and archived cancellations never enter active distributions`() {
        val moved = arrow(1, 1, point(.25f)).reposition(point(.375f), now.plusSeconds(1), "Synthetic move")
            .withFinalScore(classic.targetType, ScoreDecisionRequest.ExceptionalCorrection(
                BoundaryValue(0), "Synthetic M", now.plusSeconds(2)), now.plusSeconds(2))
        val credited = arrow(1, 2, point(1.25f)).withFinalScore(classic.targetType,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Synthetic score", now), now)
        val cancelled = arrow(1, 3, point(-200f))
        val original = end(1, impacts = listOf(moved, credited, cancelled))
        val archivedEnd = original.end.cancelImpact(cancelled.uuid, now.plusSeconds(3), "Synthetic cancellation")
        val context = original.copy(end = archivedEnd)
        val filtered = GroupingMissPolicy.EXCLUDE_MISSES
        val all = calculateSelectionGrouping(listOf(context))
        val only = calculateSelectionGrouping(listOf(context), filtered)
        val allSpatial = calculateSpatialDistribution(listOf(context))
        val onlySpatial = calculateSpatialDistribution(listOf(context), missPolicy = filtered)
        assertEquals(GroupingImpactCounts(2, 1, 0, 1), all.counts)
        assertEquals(GroupingImpactCounts(1, 0, 1, 1), only.counts)
        assertEquals(all.counts, allSpatial.counts)
        assertEquals(only.counts, onlySpatial.counts)
        assertEquals(1, onlySpatial.outsideFace.count)
        assertEquals(100.0, onlySpatial.outsideFace.percentageOfRetained)
        assertEquals(0.0, onlySpatial.cell(0, 0).impactsPerSquaredTargetRadius)
        val outside = onlySpatial.outsideImpacts.single()
        assertEquals(context.end.uuid, outside.endUuid)
        assertEquals(credited.uuid, outside.impactUuid)
        assertSame(credited.point, outside.point)
        assertFailsWith<UnsupportedOperationException> { (onlySpatial.outsideImpacts as MutableList).clear() }
        assertEquals(1.25, only.pooledNormalized.center?.x)
        assertNull(only.pooledNormalized.dispersion)
        assertEquals(listOf(0, 9, 0), archivedEnd.impacts.map { it.scoreFinal })
        archivedEnd.impacts.zip(listOf(moved, credited, cancelled)).forEach { (after, before) -> assertSame(before, after) }
        assertEquals(point(.25f), moved.repositions.single().previousPoint)
        assertEquals(1, archivedEnd.cancellations.size)
    }

    @Test fun `fully filtered selection keeps the excluded M and missing measurements`() {
        val contexts = listOf(end(1, listOf(point(2f))), end(2, listOf(point(-3f))))
        val policy = GroupingMissPolicy.EXCLUDE_MISSES
        val report = calculateSelectionGrouping(contexts, policy)
        val spatial = calculateSpatialDistribution(contexts, missPolicy = policy)
        assertEquals(GroupingImpactCounts(0, 0, 2, 0), report.counts)
        assertEquals(report.counts, spatial.counts)
        assertNull(report.pooledNormalized.center)
        assertNull(report.endMeansNormalized.center)
        assertTrue(spatial.outsideImpacts.isEmpty())
        assertNull(spatial.origin.percentageOfRetained)
    }

    @Test fun `cardinal and diagonal rays use half-open sectors and axes keep their own bins`() {
        val points = listOf(point(1f), point(.5f, .5f), point(0f, 1f), point(-.5f, .5f),
            point(-1f), point(-.5f, -.5f), point(0f, -1f), point(.5f, -.5f), point(-0f, 0f), point(1.25f))
        val result = calculateSpatialDistribution(listOf(end(1, points)), SpatialGrid(2, 2))
        assertEquals(listOf(2, 1, 1, 1, 1, 1, 1, 1), SpatialSector.entries.map { result.sector(it).count })
        assertEquals(1, result.origin.count)
        assertEquals(10.0, result.origin.percentageOfRetained)
        assertEquals(listOf(3, 3, 4), with(result.x) { listOf(negative.count, onAxis.count, positive.count) })
        assertEquals(listOf(3, 4, 3), with(result.y) { listOf(negative.count, onAxis.count, positive.count) })
        assertEquals(9, result.insideFace.count)
        assertEquals(1, result.outsideFace.count)
        assertEquals(4, result.cell(1, 1).frequency.count)
        assertEquals(40.0, result.cell(1, 1).frequency.percentageOfRetained)
        assertEquals(4.0, result.cell(1, 1).impactsPerSquaredTargetRadius)
        assertEquals(1, result.cell(0, 0).frequency.count)
        assertEquals(2, result.cell(1, 0).frequency.count)
        assertEquals(2, result.cell(0, 1).frequency.count)
    }

    @Test fun `tiny signed coordinates and extreme misses keep their exact boundaries and original bits`() {
        val tiny = Float.MIN_VALUE
        val points = listOf(point(-tiny), point(tiny), point(1f, tiny), point(1f),
            point(.75f, .75f), point(-.5f, Float.MAX_VALUE))
        val result = calculateSpatialDistribution(listOf(end(1, points)), SpatialGrid(2, 2))
        assertEquals(1, result.cell(0, 1).frequency.count)
        assertEquals(2, result.cell(1, 1).frequency.count)
        assertEquals(3, result.outsideFace.count)
        assertEquals(tiny.toRawBits(), result.outsideImpacts.first().point.y.toRawBits())
        assertEquals(point(.75f, .75f), result.outsideImpacts[1].point)
        assertEquals(Float.MAX_VALUE.toRawBits(), result.outsideImpacts.last().point.y.toRawBits())
        val geometry = calculateSelectionGrouping(listOf(end(2, listOf(point(-Float.MAX_VALUE), point(tiny))),
            end(3, listOf(point(Float.MAX_VALUE)))))
        assertEquals(tiny.toDouble() / 3, geometry.pooledNormalized.center?.x)
        assertEquals(2.0 * Float.MAX_VALUE, geometry.pooledNormalized.dispersion?.diameter)
        assertTrue(assertNotNull(geometry.pooledMeasurementsIn(GroupingUnit.MILLIMETERS).dispersion).diameter.isFinite())
    }

    @Test fun `grid cells and geometry agree with independent exact and trigonometric references`() {
        val random = Random(88123)
        val points = List(600) {
            point(random.nextDouble(-1.4, 1.4).toFloat(), random.nextDouble(-1.4, 1.4).toFloat())
        }
        val contexts = points.chunked(150).mapIndexed { i, bucket -> end(i + 1, bucket) }
        val grid = SpatialGrid(11, 7)
        val expectedGrid = Array(grid.rows) { IntArray(grid.columns) }
        val expectedSectors = IntArray(8)
        val outside = mutableListOf<NormalizedPoint>()
        for (p in points) {
            val x = BigDecimal(p.x.toDouble())
            val y = BigDecimal(p.y.toDouble())
            if (x.pow(2).add(y.pow(2)) <= BigDecimal.ONE) {
                expectedGrid[referenceCell(y, grid.rows)][referenceCell(x, grid.columns)]++
            } else outside.add(p)
            val clockwise = (Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble())) + 360.0) % 360.0
            expectedSectors[(clockwise / 45.0).toInt()]++
        }
        val spatial = calculateSpatialDistribution(contexts, grid)
        assertEquals(outside, spatial.outsideImpacts.map { it.point })
        SpatialSector.entries.forEach { assertEquals(expectedSectors[it.ordinal], spatial.sector(it).count) }
        for (r in 0 until grid.rows) for (c in 0 until grid.columns) {
            val expected = expectedGrid[r][c]
            val cell = spatial.cell(c, r)
            assertEquals(expected, cell.frequency.count)
            assertEquals(expected * 100.0 / points.size, cell.frequency.percentageOfRetained)
            assertEquals(expected / (4.0 / (11 * 7)), cell.impactsPerSquaredTargetRadius)
        }
        val expectedDiameter = points.maxOf { a -> points.maxOf { b ->
            hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y)
        } }
        val pooled = assertNotNull(calculateSelectionGrouping(contexts).pooledNormalized.dispersion)
        assertEquals(expectedDiameter, pooled.diameter, 1e-12)
    }

    @Test fun `grid resolution and query indices are bounded before access`() {
        for (dimension in listOf(Int.MIN_VALUE, -1, 0, 257, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { SpatialGrid(dimension, 1) }
            assertFailsWith<IllegalArgumentException> { SpatialGrid(1, dimension) }
        }
        val distribution = calculateSpatialDistribution(emptyList(), SpatialGrid(1, 3))
        assertEquals(4.0 / 3.0, distribution.grid.cellAreaInSquaredTargetRadii)
        assertFailsWith<IllegalArgumentException> { distribution.cell(-1, 0) }
        assertFailsWith<IllegalArgumentException> { distribution.cell(1, 0) }
        assertFailsWith<IllegalArgumentException> { distribution.cell(0, 3) }
    }

    @Test fun `large multi end cloud and maximal grid retain every count with headroom`() {
        val ends = 32
        val pointsPerEnd = 2_048
        val size = ends * pointsPerEnd
        val contexts = List(ends) { i ->
            end(i + 1, List(pointsPerEnd) { j ->
                val angle = (i * pointsPerEnd + j) * 2 * PI / size
                point((.75 * cos(angle)).toFloat(), (.75 * sin(angle)).toFloat())
            })
        }
        val grouping = calculateSelectionGrouping(contexts)
        assertEquals(size, grouping.counts.retainedImpactCount)
        assertEquals(ends, grouping.endMeansNormalized.dispersion?.contributingEndCount)
        assertEquals(1.5, assertNotNull(grouping.pooledNormalized.dispersion).diameter, 1e-7)
        val dimension = SpatialGrid.MAX_DIMENSION
        val spatial = calculateSpatialDistribution(contexts, SpatialGrid(dimension, dimension))
        assertEquals(size, spatial.insideFace.count)
        assertEquals(0, spatial.outsideFace.count)
        assertEquals(size, (0 until dimension).sumOf { row ->
            (0 until dimension).sumOf { column -> spatial.cell(column, row).frequency.count }
        })
        assertEquals(size, SpatialSector.entries.sumOf { spatial.sector(it).count })
    }

    private fun referenceCell(value: BigDecimal, size: Int): Int =
        ((value + BigDecimal.ONE) * BigDecimal(size) / BigDecimal(2))
            .setScale(0, RoundingMode.FLOOR).intValueExact().coerceAtMost(size - 1)

    private fun point(x: Float, y: Float = 0f) = NormalizedPoint(x, y)
    private fun id(value: Int) = "ab350000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    private fun configuration(profile: TargetType) = EndConfiguration(profile,
        BuiltInTargetRegistry.find(profile.uuid)?.regulatoryEdition, 18, ArrowCountMode.FREE, null, true, ScoringMode.TRAINING)

    private fun arrow(owner: Int, index: Int, point: NormalizedPoint, config: EndConfiguration = classic) =
        scoreArrowImpact(id(owner * 100_000 + index), index, config.targetType, point, config.scoringMode, createdAt = now)

    private fun end(owner: Int, points: List<NormalizedPoint> = emptyList(), config: EndConfiguration = classic,
        validated: Boolean = false, impacts: List<ArrowImpact>? = null): EndScoringContext {
        val arrows = impacts ?: points.mapIndexed { i, point -> arrow(owner, i + 1, point, config) }
        val updatedAt = arrows.maxOfOrNull { it.updatedAt } ?: now
        val draft = End(id(owner), owner, EndStatus.EDITING, arrows, createdAt = now, updatedAt = updatedAt)
        val recorded = if (validated) draft.validate(updatedAt) else draft
        return EndScoringContext(recorded, EndConfigurationBinding(recorded.uuid, config))
    }
}
