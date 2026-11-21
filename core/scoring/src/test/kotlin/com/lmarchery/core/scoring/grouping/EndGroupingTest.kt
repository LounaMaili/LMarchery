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
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.TargetProfileSnapshot
import com.lmarchery.core.scoring.archive.v4.ArchiveV4Reconstruction
import com.lmarchery.core.scoring.archive.v4.reconstructArchiveV4
import com.lmarchery.core.scoring.archive.v4.snapshotArchiveV4
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.scoring.configured.ConfiguredSessionMetadata
import com.lmarchery.core.scoring.configured.configuredSessionScoringContexts
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.MixedScoringException
import com.lmarchery.core.scoring.mixed.MixedScoringIssue
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.scoreArrowImpact
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.*

class EndGroupingTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val classic = ClassicTargetFixtures.classic40
    private val compound = WorldArcheryIndoorTargetProfiles.compound60

    @Test fun `empty end keeps identity and absent geometry in every unit`() {
        val end = context(index = 4)
        val grouping = calculateEndGrouping(end)
        assertEquals(end.end.uuid, grouping.endUuid)
        assertEquals(4, grouping.endIndex)
        assertEquals(EndStatus.EDITING, grouping.status)
        assertEquals(0, grouping.impactCount)
        assertEquals(0, grouping.includedMissCount)
        assertEquals(0, grouping.excludedMissCount)
        for (unit in GroupingUnit.entries) {
            val measured = grouping.measurementsIn(unit)
            assertEquals(unit, measured.unit)
            assertNull(measured.center)
            assertNull(measured.dispersion)
        }
        assertTrue(summarizeEndGroupings(emptyList()).isEmpty())
    }

    @Test fun `one point describes signed centering without fabricating dispersion`() {
        val grouping = calculateEndGrouping(context(points = listOf(point(-.375f, .5f))))
        assertEquals(1, grouping.impactCount)
        assertEquals(GroupingCenter(-.375, .5, .625), grouping.normalized.center)
        assertNull(grouping.normalized.dispersion)
        val physical = grouping.measurementsIn(GroupingUnit.CENTIMETERS)
        assertEquals(GroupingCenter(-7.5, 10.0, 12.5), physical.center)
        assertNull(physical.dispersion)
    }

    @Test fun `two points give population standard deviations radius and diameter`() {
        val values = calculateEndGrouping(context(points = listOf(point(-.25f, .5f), point(.75f, -.5f)))).normalized
        assertEquals(GroupingCenter(.25, 0.0, .25), values.center)
        val spread = assertNotNull(values.dispersion)
        assertEquals(.5, spread.standardDeviationX)
        assertEquals(.5, spread.standardDeviationY)
        assertEquals(hypot(.5, .5), spread.meanRadius, 1e-15)
        assertEquals(hypot(1.0, 1.0), spread.diameter, 1e-15)
    }

    @Test fun `coincident positions give real zero spread and canonicalize signed zero`() {
        val grouping = calculateEndGrouping(context(points = listOf(point(-0.0f, .75f), point(0f, .75f), point(0f, .75f))))
        assertEquals(3, grouping.impactCount)
        assertEquals(0.0.toRawBits(), assertNotNull(grouping.normalized.center).x.toRawBits())
        assertEquals(GroupingDispersion(0.0, 0.0, 0.0, 0.0), grouping.normalized.dispersion)
    }

    @Test fun `duplicate impacts carry their full weight in the statistics`() {
        val grouping = calculateEndGrouping(context(points = listOf(point(0f, 0f), point(0f, 0f), point(.75f, 0f))))
        assertEquals(.25, grouping.normalized.center?.x)
        val spread = assertNotNull(grouping.normalized.dispersion)
        assertEquals(.75, spread.diameter)
        assertEquals(1.0 / 3.0, spread.meanRadius, 1e-15)
        assertEquals(kotlin.math.sqrt(.125), spread.standardDeviationX, 1e-15)
    }

    @Test fun `tight translated cluster preserves its dispersion and changes its centering`() {
        val tight = listOf(point(-.0625f, -.0625f), point(-.0625f, .0625f), point(.0625f, -.0625f), point(.0625f, .0625f))
        val centered = calculateEndGrouping(context(points = tight)).normalized
        val moved = calculateEndGrouping(context(points = tight.map { point(it.x + .5f, it.y - .375f) })).normalized
        val wide = calculateEndGrouping(context(points = tight.map { point(it.x * 8f, it.y * 8f) })).normalized
        assertEquals(GroupingCenter(0.0, 0.0, 0.0), centered.center)
        assertEquals(GroupingCenter(.5, -.375, .625), moved.center)
        assertEquals(centered.dispersion, moved.dispersion)
        assertEquals(.0625, centered.dispersion?.standardDeviationX)
        assertEquals(.5, wide.dispersion?.standardDeviationX)
        assertEquals(hypot(1.0, 1.0), wide.dispersion?.diameter)
    }

    @Test fun `physical conversion uses the effective profile and retains precision`() {
        val custom = classic.copy(uuid = id(900), name = "Synthetic 55 cm", isBuiltIn = false, diameterCm = 55)
        val points = listOf(point(-.5f, -.25f), point(.5f, -.25f))
        for (profile in listOf(classic, compound, custom)) {
            val configuration = configuration(profile)
            val grouping = calculateEndGrouping(context(configuration = configuration, points = points))
            val cm = grouping.measurementsIn(GroupingUnit.CENTIMETERS)
            val mm = grouping.measurementsIn(GroupingUnit.MILLIMETERS)
            val radius = profile.diameterCm / 2.0
            assertSame(configuration, grouping.configuration)
            assertSame(grouping.normalized, grouping.measurementsIn(GroupingUnit.NORMALIZED))
            assertEquals(GroupingCenter(0.0, -.25 * radius, .25 * radius), cm.center)
            assertEquals(GroupingDispersion(.5 * radius, 0.0, .5 * radius, radius), cm.dispersion)
            assertEquals(radius * 10, mm.dispersion?.diameter)
        }
    }

    @Test fun `final misses are included by default at their real off-face positions`() {
        val grouping = calculateEndGrouping(context(points = listOf(point(0f, 0f), point(-2f, 1.5f))))
        assertEquals(GroupingMissPolicy.INCLUDE_MISSES, grouping.missPolicy)
        assertEquals(2, grouping.impactCount)
        assertEquals(1, grouping.includedMissCount)
        assertEquals(0, grouping.excludedMissCount)
        assertEquals(GroupingCenter(-1.0, .75, 1.25), grouping.normalized.center)
        assertEquals(2.5, grouping.normalized.dispersion?.diameter)
    }

    @Test fun `V4 round trip excludes cancellations and filters by stored final decision`() {
        val c = configuration(classic)
        val endId = id(1)
        val moved = arrow(1, c, point(.25f, 0f)).reposition(point(.375f, 0f), now.plusSeconds(1), "synthetic move")
            .withFinalScore(classic, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "synthetic miss", now.plusSeconds(2)),
                now.plusSeconds(2))
        val outside = arrow(2, c, point(1.5f, 0f)).withFinalScore(classic,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "synthetic judgment", now), now)
        val cancelled = arrow(3, c, point(-100f, .5f))
        var s = ConfiguredSession.create(id(999), ConfiguredSessionMetadata(
            SessionDateTime.fromInstant(now, ZoneId.of("UTC")), "Synthetic geometry"), c, now).start(now).createNextEnd(endId, now)
        for (impact in listOf(moved, outside, cancelled)) s = s.withImpact(endId, impact, now.plusSeconds(2))
        s = s.cancelImpact(endId, cancelled.uuid, now.plusSeconds(3), "synthetic cancellation")
            .validateEnd(endId, now.plusSeconds(4))
        val snapshot = snapshotArchiveV4(listOf(TargetProfileSnapshot(classic, c.regulatoryEdition)), listOf(s), emptyList(), emptyList())
        val archive = assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(snapshot)).archive
        val context = configuredSessionScoringContexts(archive.sessions.single()).single()
        val originalImpacts = context.end.impacts.toList()
        val included = calculateEndGrouping(context)
        val excluded = calculateEndGrouping(context, GroupingMissPolicy.EXCLUDE_MISSES)
        assertEquals(2, included.impactCount)
        assertEquals(1, included.includedMissCount)
        assertEquals(.9375, included.normalized.center?.x)
        assertEquals(1.125, included.normalized.dispersion?.diameter)
        assertEquals(1, excluded.impactCount)
        assertEquals(0, excluded.includedMissCount)
        assertEquals(1, excluded.excludedMissCount)
        assertEquals(GroupingCenter(1.5, 0.0, 1.5), excluded.normalized.center)
        assertNull(excluded.normalized.dispersion)
        assertEquals(listOf(0, 9, 0), context.end.impacts.map { it.scoreFinal })
        assertTrue(context.end.impacts.first().scoreCalculated > 0)
        assertEquals(0, context.end.impacts[1].scoreCalculated)
        assertEquals(1, context.end.impacts.first().repositions.size)
        assertEquals(snapshot, archive.toSnapshot())
        originalImpacts.zip(context.end.impacts).forEach { (before, after) -> assertSame(before, after) }
    }

    @Test fun `a filter or cancellation can leave zero retained points`() {
        val c = configuration(classic)
        val first = arrow(1, c, point(1.5f, 0f))
        val base = context(configuration = c, impacts = listOf(first))
        val filtered = calculateEndGrouping(base, GroupingMissPolicy.EXCLUDE_MISSES)
        val cancelled = calculateEndGrouping(base.copy(end = base.end.cancelImpact(first.uuid, now)))
        assertEquals(0, filtered.impactCount)
        assertEquals(1, filtered.excludedMissCount)
        assertEquals(0, cancelled.impactCount)
        assertEquals(0, cancelled.includedMissCount)
        assertNull(filtered.normalized.center)
        assertNull(cancelled.normalized.dispersion)
    }

    @Test fun `series are canonical and separate exact configurations and archived statuses`() {
        val c = configuration(classic)
        val a = context(10, c, index = 2, validated = true)
        val b = context(11, c, index = 1, validated = true, points = listOf(point(.5f, 0f)))
        val c1 = context(12, c, index = 2)
        val d = context(13, c.copy(distanceM = 30), index = 3, validated = true)
        val e = context(14, configuration(compound), index = 4, points = listOf(point(0f, 0f)))
        val f = context(15, c, index = 2, validated = true)
        val input = mutableListOf(f, d, e, a, c1, b)
        val series = summarizeEndGroupings(input, GroupingMissPolicy.EXCLUDE_MISSES)
        val reversed = summarizeEndGroupings(input.reversed(), GroupingMissPolicy.EXCLUDE_MISSES)
        input.clear()
        assertEquals(series.map { it.ends }, reversed.map { it.ends })
        assertEquals(listOf(c to EndStatus.VALIDATED, c to EndStatus.EDITING,
            d.configuration to EndStatus.VALIDATED, e.configuration to EndStatus.EDITING),
            series.map { it.configuration to it.status })
        assertEquals(listOf(b.end.uuid, a.end.uuid, f.end.uuid), series.first().ends.map { it.endUuid })
        assertEquals(listOf(1, 2, 2), series.first().ends.map { it.endIndex })
        assertNull(series.first().ends[1].normalized.center)
        assertTrue(series.all { it.missPolicy == GroupingMissPolicy.EXCLUDE_MISSES })
        assertFailsWith<UnsupportedOperationException> { (series as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (series.first().ends as MutableList).clear() }
    }

    @Test fun `series inherits collection identity validation`() {
        val a = context(points = listOf(point(0f, 0f)))
        assertEquals(MixedScoringIssue.DUPLICATE_END, assertFailsWith<MixedScoringException> {
            summarizeEndGroupings(listOf(a, a))
        }.code)
        val b = context(2, configuration(classic), impacts = a.end.impacts)
        assertEquals(MixedScoringIssue.DUPLICATE_IMPACT, assertFailsWith<MixedScoringException> {
            summarizeEndGroupings(listOf(a, b))
        }.code)
    }

    @Test fun `opposite Float extremes preserve tiny centroid residual and finite lengths`() {
        val large = Float.MAX_VALUE.toDouble()
        val tiny = Float.MIN_VALUE.toDouble()
        val points = listOf(point(Float.MAX_VALUE, 0f), point(Float.MIN_VALUE, 0f), point(-Float.MAX_VALUE, 0f))
        val grouping = calculateEndGrouping(context(points = points))
        val reversed = calculateEndGrouping(context(points = points.reversed()))
        assertEquals(grouping, reversed)
        assertEquals(tiny / 3.0, grouping.normalized.center?.x)
        val spread = assertNotNull(grouping.normalized.dispersion)
        assertEquals(2 * large, spread.diameter)
        assertEquals(0.0, spread.standardDeviationY)
        assertTrue(spread.standardDeviationX.isFinite() && spread.meanRadius.isFinite())
        assertTrue(assertNotNull(grouping.measurementsIn(GroupingUnit.MILLIMETERS).dispersion).diameter.isFinite())
    }

    @Test fun `diameter covers the endpoints of a collinear cloud with interior duplicates`() {
        val points = listOf(point(-.75f, -.375f), point(.5f, .25f), point(.5f, .25f), point(1f, .5f), point(0f, 0f))
        val measured = calculateEndGrouping(context(points = points)).normalized
        assertEquals(hypot(1.75, .875), measured.dispersion?.diameter)
    }

    @Test fun `calipers match a pairwise oracle on seeded clouds and ill-conditioned coordinates`() {
        val random = Random(6502)
        val cloud = (1..100).map { _ -> List(random.nextInt(2, 68)) {
            point(random.nextInt(-400, 401) / 128f, random.nextInt(-400, 401) / 128f)
        } }
        val huge = Math.scalb(1f, 120)
        val tiny = Float.MIN_VALUE
        val cancellation = listOf(point(-huge, -huge), point(tiny, 0f), point(huge, huge), point(0f, tiny), point(huge, 0f))
        // The exact orientation of the inner triangle survives even when Double differences lose the tiny terms.
        val nearLine = listOf(point(-huge, -huge), point(0f, 0f), point(tiny, 0f), point(0f, tiny), point(huge, huge))
        for (points in cloud + listOf(cancellation, nearLine)) {
            val expected = points.indices.maxOf { i -> points.indices.maxOf { j ->
                hypot(points[i].x.toDouble() - points[j].x, points[i].y.toDouble() - points[j].y)
            } }
            val actual = assertNotNull(calculateEndGrouping(context(points = points)).normalized.dispersion).diameter
            assertEquals(expected, actual, Math.ulp(expected) * 4)
        }
    }

    @Test fun `exact orientation resolves a sign hidden by Double rounding`() {
        val huge = Math.scalb(1.0, 120)
        val tiny = Float.MIN_VALUE.toDouble()
        val a = GroupingPoint(-huge, -huge)
        val b = GroupingPoint(huge, huge)
        assertEquals(huge, tiny - a.x)
        assertEquals(-1, crossSign(a, b, a, GroupingPoint(tiny, 0.0)))
        assertEquals(1, crossSign(a, b, a, GroupingPoint(0.0, tiny)))
        assertEquals(0, crossSign(a, b, a, GroupingPoint(tiny, tiny)))
    }

    @Test fun `large circle produces the diameter with no quadratic scan`() {
        val size = 20_000
        val points = List(size) { index ->
            val angle = index * 2 * PI / size
            point(cos(angle).toFloat(), sin(angle).toFloat())
        }
        val grouping = calculateEndGrouping(context(points = points))
        assertEquals(size, grouping.impactCount)
        assertEquals(2.0, assertNotNull(grouping.normalized.dispersion).diameter, 1e-7)
        assertEquals(0.0, assertNotNull(grouping.normalized.center).distanceFromTargetCenter, 1e-12)
    }

    private fun point(x: Float, y: Float) = NormalizedPoint(x, y)
    private fun id(value: Int) = "ab340000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    private fun configuration(target: TargetType) = EndConfiguration(target,
        BuiltInTargetRegistry.find(target.uuid)?.regulatoryEdition, 18, ArrowCountMode.FREE, null, true, ScoringMode.TRAINING)
    private fun arrow(index: Int, c: EndConfiguration, point: NormalizedPoint, owner: Int = 1): ArrowImpact =
        scoreArrowImpact(id(owner * 100_000 + index), index, c.targetType, point, c.scoringMode, createdAt = now)

    private fun context(owner: Int = 1, configuration: EndConfiguration = configuration(classic), index: Int = owner,
        validated: Boolean = false, points: List<NormalizedPoint> = emptyList(), impacts: List<ArrowImpact>? = null): EndScoringContext {
        val arrows = impacts ?: points.mapIndexed { i, point -> arrow(i + 1, configuration, point, owner) }
        val updatedAt = arrows.maxOfOrNull { it.updatedAt } ?: now
        val draft = End(id(owner), index, EndStatus.EDITING, arrows, createdAt = now, updatedAt = updatedAt)
        val end = if (validated) draft.validate(updatedAt) else draft
        return EndScoringContext(end, EndConfigurationBinding(end.uuid, configuration))
    }
}
