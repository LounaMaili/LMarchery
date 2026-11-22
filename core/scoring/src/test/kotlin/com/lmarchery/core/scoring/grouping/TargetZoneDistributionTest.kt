package com.lmarchery.core.scoring.grouping

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.scoring.scoreBoundaries
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.mixed.EndConfigurationBinding
import com.lmarchery.core.scoring.mixed.EndScoringContext
import com.lmarchery.core.scoring.mixed.MixedScoringException
import com.lmarchery.core.scoring.mixed.MixedScoringIssue
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class TargetZoneDistributionTest {
    private val now = Instant.parse("2026-11-21T08:00:00Z")
    private val classic = ClassicTargetFixtures.classic40

    @Test fun `official indoor classic and compound use the actual ten size and disable X`() {
        val points = listOf(point(0f), point(.05f), point(.075f), point(.10f), point(.20f), point(1f))
        val recurve = calculateTargetZoneDistribution(listOf(context(1, points, WA.recurve40)))
        val compound = calculateTargetZoneDistribution(listOf(context(1, points, WA.compound40)))
        assertEquals(.10f, recurve.zones.first().zone.outerRadiusNormalized)
        assertEquals(.05f, compound.zones.first().zone.outerRadiusNormalized)
        assertEquals(listOf(4, 1), recurve.zones.take(2).map { it.frequency.count })
        assertEquals(listOf(2, 3), compound.zones.take(2).map { it.frequency.count })
        for (report in listOf(recurve, compound)) {
            assertNull(report.xRing)
            assertEquals(1, report.zones.last().frequency.count)
            assertEquals(6, report.insideFace.count)
            assertEquals(0.0, report.outsideFace.percentageOfRetained)
        }
    }

    @Test fun `custom scores use exact zone definitions and X is a subset of its central zone`() {
        val target = customTarget()
        val selection = listOf(
            context(1, listOf(point(0f), point(-.125f), point(.25f), point(.5f)), target),
            context(2, listOf(point(.5f, .5f), point(-1f), point(1.3f)), target),
        )
        val report = calculateTargetZoneDistribution(selection)
        assertEquals(2, report.selectedEndCount)
        assertEquals(EndStatus.EDITING, report.scope?.status)
        assertEquals(listOf(12, 7, 3), report.zones.map { it.zone.score })
        assertEquals(listOf(3, 1, 2), report.zones.map { it.frequency.count })
        report.zones.zip(target.zones.reversed()).forEach { (bin, original) -> assertSame(original, bin.zone) }
        val ring = assertNotNull(report.xRing)
        assertEquals("X synthétique", ring.label)
        assertEquals(.125f, ring.radiusNormalized)
        assertEquals(SpatialFrequency(2, 7), ring.frequency)
        assertEquals(SpatialFrequency(1, 7), report.outsideFace)
        assertEquals(SpatialFrequency(6, 7), report.insideFace)
        assertEquals(100.0 * 3 / 7, report.zones.first().frequency.percentageOfRetained)
        assertEquals(report.counts.retainedImpactCount, report.zones.sumOf { it.frequency.count } + report.outsideFace.count)
        assertFailsWith<UnsupportedOperationException> { (report.zones as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.outsideImpacts as MutableList).clear() }
        assertEquals(listOf(3, 7, 12), target.zones.map { it.score })

        val disabled = target.copy(xRingEnabled = false)
        assertNull(calculateTargetZoneDistribution(listOf(context(1, listOf(point(0f)), disabled))).xRing)
    }

    @Test fun `every radial boundary including X and the edge belongs to the inner side exactly`() {
        val target = customTarget()
        for ((radius, inner, outer) in listOf(Triple(.25f, 12, 7), Triple(.5f, 7, 3), Triple(1f, 3, null))) {
            for ((p, expected) in listOf(
                point(Math.nextDown(radius)) to inner,
                point(-radius, -0f) to inner,
                point(Math.nextUp(radius)) to outer,
                point(radius, Float.MIN_VALUE) to outer,
            )) {
                val report = calculateTargetZoneDistribution(listOf(context(1, listOf(p), target)))
                assertEquals(expected, report.zones.singleOrNull { it.frequency.count == 1 }?.zone?.score, "point=$p")
                assertEquals(if (expected == null) 1 else 0, report.outsideFace.count, "point=$p")
            }
        }
        val r = target.innerTenRadiusNormalized
        val edge = calculateTargetZoneDistribution(listOf(context(1, listOf(
            point(Math.nextDown(r)), point(r), point(Math.nextUp(r)), point(r, Float.MIN_VALUE),
        ), target)))
        assertEquals(4, edge.zones.first().frequency.count)
        assertEquals(2, edge.xRing?.frequency?.count)
        val wholeCenter = target.copy(innerTenRadiusNormalized = .25f)
        val coincidentBoundary = calculateTargetZoneDistribution(listOf(context(1, listOf(
            point(.25f), point(Math.nextUp(.25f)),
        ), wholeCenter)))
        assertEquals(1, coincidentBoundary.zones.first().frequency.count)
        assertEquals(1, coincidentBoundary.xRing?.frequency?.count)
    }

    @Test fun `current positions remain independent of calculated scores final judgments and cancellation`() {
        val cordon = classic.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
        val edge = classic.scoreBoundaries().single { it.reference.type == BoundaryType.TARGET_EDGE }
        val ring = classic.scoreBoundaries().single { it.reference.type == BoundaryType.X_RING }
        val judged = arrow(1, 1, point(.105f)).withFinalScore(classic,
            ScoreDecisionRequest.JudgeBoundary(cordon.reference, cordon.inner, now), now)
        val credited = arrow(1, 2, point(1.25f)).withFinalScore(classic,
            ScoreDecisionRequest.JudgeBoundary(edge.reference, edge.inner, now), now)
        val moved = arrow(1, 3, point(.85f)).reposition(point(.25f), now.plusSeconds(1), "Déplacement synthétique")
            .withFinalScore(classic, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "M synthétique",
                now.plusSeconds(2)), now.plusSeconds(2))
        val nonX = arrow(1, 4, point(0f)).withFinalScore(classic,
            ScoreDecisionRequest.JudgeBoundary(ring.reference, ring.outer, now), now)
        val cancelled = arrow(1, 5, point(0f))
        val rounding = arrow(1, 6, point(1f, Float.MIN_VALUE))
        val arrows = listOf(judged, credited, moved, nonX, cancelled, rounding)
        val draft = context(1, impacts = arrows)
        val recorded = draft.copy(end = draft.end.cancelImpact(cancelled.uuid, now.plusSeconds(3), "Annulation synthétique"))
        val selection = listOf(recorded, context(2, listOf(point(2f))))
        val all = calculateTargetZoneDistribution(selection)
        val filtered = calculateTargetZoneDistribution(selection, GroupingMissPolicy.EXCLUDE_MISSES)
        assertEquals(listOf(9, 0, 8, 10, 10, 1), recorded.end.impacts.map { it.scoreCalculated })
        assertEquals(listOf(10, 1, 0, 10, 10, 1), recorded.end.impacts.map { it.scoreFinal })
        assertFalse(nonX.isX)
        assertEquals(1, all.xRing?.frequency?.count)
        assertEquals(1, filtered.xRing?.frequency?.count)
        assertEquals(GroupingImpactCounts(6, 2, 0, 1), all.counts)
        assertEquals(GroupingImpactCounts(4, 0, 2, 1), filtered.counts)
        assertEquals(listOf(10, 9, 8), all.zones.filter { it.frequency.count > 0 }.map { it.zone.score })
        assertEquals(listOf(10, 9), filtered.zones.filter { it.frequency.count > 0 }.map { it.zone.score })
        assertEquals(listOf(credited.uuid, rounding.uuid, selection.last().end.impacts.single().uuid),
            all.outsideImpacts.map { it.impactUuid })
        assertSame(credited.point, filtered.outsideImpacts.first().point)
        assertEquals(Float.MIN_VALUE.toRawBits(), filtered.outsideImpacts.last().point.y.toRawBits())
        assertEquals(point(.85f), moved.repositions.single().previousPoint)
        assertEquals(point(.25f), moved.point)
        arrows.zip(recorded.end.impacts).forEach { (before, after) -> assertSame(before, after) }
        assertEquals(cancelled.uuid, recorded.end.cancellations.single().impactUuid)
        for (report in listOf(all, filtered)) {
            val spatial = calculateSpatialDistribution(selection, missPolicy = report.missPolicy)
            assertEquals(spatial.counts, report.counts)
            assertEquals(spatial.outsideImpacts, report.outsideImpacts)
            assertEquals(spatial.insideFace, report.insideFace)
        }
    }

    @Test fun `empty inputs and known empty profiles have zero counts and no percentages`() {
        val policy = GroupingMissPolicy.EXCLUDE_MISSES
        val absent = calculateTargetZoneDistribution(emptyList(), policy)
        assertNull(absent.scope)
        assertNull(absent.xRing)
        assertEquals(policy, absent.missPolicy)
        assertTrue(absent.zones.isEmpty())
        assertEquals(0, absent.selectedEndCount)
        for (source in listOf(context(1), context(1, listOf(point(2f))))) {
            val report = calculateTargetZoneDistribution(listOf(source), policy)
            assertNotNull(report.scope)
            assertEquals(1, report.selectedEndCount)
            assertEquals(0, report.counts.retainedImpactCount)
            assertEquals(source.end.impacts.size, report.counts.excludedMissCount)
            assertEquals(classic.zones.size, report.zones.size)
            assertEquals(SpatialFrequency(0, 0), assertNotNull(report.xRing).frequency)
            for (frequency in report.zones.map { it.frequency } + report.insideFace + report.outsideFace) {
                assertEquals(0, frequency.count)
                assertNull(frequency.percentageOfRetained)
            }
        }
        assertNull(absent.outsideFace.percentageOfRetained)
    }

    @Test fun `mixed selections and identity collisions use the same checks as the other selection APIs`() {
        val first = context(1, listOf(point(.25f)))
        val repeat = context(1, listOf(point(1.25f)))
        assertEquals(1, calculateTargetZoneDistribution(listOf(first)).insideFace.count)
        assertEquals(1, calculateTargetZoneDistribution(listOf(repeat)).outsideFace.count)
        assertEquals(MixedScoringIssue.DUPLICATE_END, assertFailsWith<MixedScoringException> {
            calculateTargetZoneDistribution(listOf(first, repeat))
        }.code)
        val sameImpact = context(2, impacts = first.end.impacts)
        assertEquals(MixedScoringIssue.DUPLICATE_IMPACT, assertFailsWith<MixedScoringException> {
            calculateTargetZoneDistribution(listOf(first, sameImpact))
        }.code)
        val collision = End(first.end.impacts.single().uuid, 3, EndStatus.EDITING, emptyList(), createdAt = now, updatedAt = now)
        assertEquals(MixedScoringIssue.IDENTITY_COLLISION, assertFailsWith<MixedScoringException> {
            calculateTargetZoneDistribution(listOf(first, EndScoringContext(collision,
                EndConfigurationBinding(collision.uuid, first.configuration))))
        }.code)
        val empty = context(2)
        val otherDistance = empty.copy(binding = empty.binding.copy(configuration = empty.configuration.copy(distanceM = 25)))
        val validated = first.copy(end = first.end.validate(now))
        assertFailsWith<IllegalArgumentException> { calculateTargetZoneDistribution(listOf(first, otherDistance)) }
        assertFailsWith<IllegalArgumentException> { calculateTargetZoneDistribution(listOf(validated, empty)) }
    }

    @Test fun `exact decimal oracle checks off-axis points and adversarial boundary neighbours`() {
        val target = customTarget()
        val ring = target.innerTenRadiusNormalized
        val random = Random(57)
        val points = buildList {
            repeat(1024) { add(point(random.nextFloat() * 2.4f - 1.2f, random.nextFloat() * 2.4f - 1.2f)) }
            for (radius in target.zones.map { it.outerRadiusNormalized } + ring) {
                add(point(radius, Float.MIN_VALUE))
                add(point(-Float.MIN_VALUE, -radius))
                repeat(64) {
                    val x = random.nextFloat() * radius
                    val y = kotlin.math.sqrt(radius.toDouble() * radius - x.toDouble() * x).toFloat()
                    add(point(x, Math.nextDown(y)))
                    add(point(x, y))
                    add(point(x, Math.nextUp(y)))
                }
            }
            add(point(Float.MAX_VALUE, -Float.MIN_VALUE))
        }
        val selection = points.chunked(256).mapIndexed { i, chunk -> context(i + 1, chunk, target) }
        val report = calculateTargetZoneDistribution(selection)
        val radii = target.zones.sortedBy { it.outerRadiusNormalized }.map { square(it.outerRadiusNormalized) }
        val pointSquares = points.map { square(it.x) + square(it.y) }
        val cumulative = radii.map { bound -> pointSquares.count { it <= bound } }
        val expected = (listOf(0) + cumulative).zipWithNext { inside, outer -> outer - inside }
        assertEquals(expected, report.zones.map { it.frequency.count })
        assertEquals(points.size - cumulative.last(), report.outsideFace.count)
        assertEquals(pointSquares.count { it <= square(ring) }, report.xRing?.frequency?.count)
        assertEquals(calculateSpatialDistribution(selection).outsideImpacts, report.outsideImpacts)
    }

    @Test fun `large synthetic multi-end report partitions all retained impacts`() {
        val positions = List(4096) { i ->
            when (i % 4) {
                0 -> point(-0f)
                1 -> point(.10f)
                2 -> point(.3f, .4f)
                else -> point(1f, .5f)
            }
        }
        val selection = List(16) { i -> context(i + 1, positions) }
        val report = calculateTargetZoneDistribution(selection)
        val perPosition = 16_384
        assertEquals(65_536, report.counts.retainedImpactCount)
        assertEquals(perPosition, report.counts.includedMissCount)
        assertEquals(perPosition, report.outsideFace.count)
        assertEquals(perPosition * 2, report.zones.first().frequency.count)
        assertEquals(perPosition, report.zones.single { it.zone.score == 5 }.frequency.count)
        assertEquals(perPosition, report.xRing?.frequency?.count)
        assertEquals(report.counts.retainedImpactCount, report.zones.sumOf { it.frequency.count } + report.outsideFace.count)
    }

    private fun square(number: Float) = BigDecimal(number.toDouble()).pow(2)
    private fun point(x: Float, y: Float = 0f) = NormalizedPoint(x, y)
    private fun id(n: Long) = UUID(0x4000, Long.MIN_VALUE + n).toString()

    private fun customTarget() = classic.copy(
        uuid = id(100), name = "Profil synthétique 12/7/3", isBuiltIn = false, maxScore = 12, minScore = 3,
        xRingLabel = "X synthétique", innerTenRadiusNormalized = .125f,
        zones = listOf(TargetZone(3, .5f, 1f, "white"), TargetZone(7, .25f, .5f, "red"), TargetZone(12, 0f, .25f, "gold")),
    )

    private fun arrow(owner: Int, index: Int, point: NormalizedPoint, target: TargetType = classic) =
        scoreArrowImpact(id(1_000_000L + owner * 10_000L + index), index, target, point, ScoringMode.TRAINING, createdAt = now)

    private fun context(
        owner: Int, points: List<NormalizedPoint> = emptyList(), target: TargetType = classic,
        impacts: List<ArrowImpact> = points.mapIndexed { i, point -> arrow(owner, i + 1, point, target) },
    ): EndScoringContext {
        val configuration = EndConfiguration(target, BuiltInTargetRegistry.find(target.uuid)?.regulatoryEdition,
            18, ArrowCountMode.FREE, null, true, ScoringMode.TRAINING)
        val end = End(id(owner.toLong()), owner, EndStatus.EDITING, impacts, createdAt = now,
            updatedAt = impacts.maxOfOrNull { it.updatedAt } ?: now)
        return EndScoringContext(end, EndConfigurationBinding(end.uuid, configuration))
    }
}
