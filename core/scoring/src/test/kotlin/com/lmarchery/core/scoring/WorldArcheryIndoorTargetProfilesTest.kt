package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.scoring.scoreBoundaries
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.model.time.SessionDateTime
import java.time.ZoneId
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldArcheryIndoorTargetProfilesTest {
    private val profiles = WorldArcheryIndoorTargetProfiles
    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val cases = listOf(
        ProfileCase(profiles.recurve40, 40, 18, 0.10f, 4.0, "5d98a517-4b7b-4308-8f4f-af3bd992a285"),
        ProfileCase(profiles.compound40, 40, 18, 0.05f, 2.0, "142a7cf4-1bee-427d-b351-c002c7003b98"),
        ProfileCase(profiles.recurve60, 60, 25, 0.10f, 6.0, "bb401e90-3818-4ed0-a926-06055b01ad25"),
        ProfileCase(profiles.compound60, 60, 25, 0.05f, 3.0, "39da4393-d4f9-45e4-86b7-36115ecd06ec"),
    )

    @Test
    fun `four explicit complete profiles have the specified dimensions and distances`() {
        assertEquals("2026-01-27", profiles.RULEBOOK_VERSION)
        assertEquals(cases.map { it.target }, profiles.all)
        for ((target, diameter, distance, radius, tenDiameter) in cases) {
            assertEquals(diameter, target.diameterCm, target.name)
            assertEquals(distance, target.defaultDistanceM, target.name)
            assertEquals("indoor", target.discipline)
            assertEquals(3, target.defaultArrowCount)
            assertTrue(target.isBuiltIn)
            assertEquals(10, target.maxScore)
            assertEquals(1, target.minScore)
            assertEquals((10 downTo 1).toList(), target.zones.map { it.score })
            val ten = target.zones.single { it.score == 10 }
            assertEquals(0f, ten.innerRadiusNormalized)
            assertEquals(radius, ten.outerRadiusNormalized)
            // Normalize with the whole face radius, not the radius of the scoring 10.
            assertEquals(tenDiameter, diameter * ten.outerRadiusNormalized.toDouble(), 0.000001)
            assertEquals(tenDiameter / 2, diameter / 2.0 * ten.outerRadiusNormalized.toDouble(), 0.000001)
        }
    }

    @Test
    fun `required sample radii distinguish recurve and compound without a central X`() {
        for (case in cases) {
            val expected = listOf(0.025f to 10, 0.075f to if (case.tenRadius == 0.10f) 10 else 9, 0.15f to 9)
            for ((radius, score) in expected) {
                val geometry = calculateScore(case.target, point(radius))
                assertEquals(score, geometry.score, "${case.target.name} r=$radius")
                assertFalse(geometry.isX)
                for (mode in ScoringMode.entries) {
                    val final = resolveFinalScore(case.target, point(radius), mode)
                    assertEquals(score, final.scoreCalculated)
                    assertEquals(score, final.scoreFinal)
                    assertFalse(final.isX)
                }
            }
        }
    }

    @Test
    fun `exact ten and nine boundaries belong to the inner zone and adjacent Floats switch predictably`() {
        for (case in cases) {
            for ((boundary, inner, outer) in listOf(Triple(case.tenRadius, 10, 9), Triple(0.2f, 9, 8))) {
                for ((radius, expected) in listOf(Math.nextDown(boundary) to inner, boundary to inner, Math.nextUp(boundary) to outer)) {
                    assertEquals(expected, calculateScore(case.target, point(radius)).score, "${case.target.name} r=$radius")
                    assertEquals(expected, impact(case.target, radius).scoreFinal)
                }
            }
        }
    }

    @Test
    fun `all remaining full face zones retain their exact boundaries through one and miss`() {
        val outerRadii = listOf(0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)
        for (target in profiles.all) {
            for ((offset, radius) in outerRadii.withIndex()) {
                val score = 8 - offset
                assertEquals(score, calculateScore(target, point(Math.nextDown(radius))).score)
                assertEquals(score, calculateScore(target, point(radius)).score)
                assertEquals(score - 1, calculateScore(target, point(Math.nextUp(radius))).score)
                val zone = target.zones.single { it.score == score }
                assertEquals(radius, zone.outerRadiusNormalized)
                assertEquals(if (offset == 0) 0.2f else outerRadii[offset - 1], zone.innerRadiusNormalized)
            }
            assertEquals(0, impact(target, 1.1f).scoreFinal)
            assertTrue(impact(target, 1.1f).isMiss)
        }
    }

    @Test
    fun `boundary suggestions use each real ten radius symmetrically with non X values`() {
        for (case in cases) {
            for (radius in listOf(Math.nextDown(case.tenRadius), case.tenRadius, Math.nextUp(case.tenRadius))) {
                val hint = findBoundaryHints(case.target, point(radius)).single()
                assertEquals(BoundaryType.SCORE_ZONES, hint.type)
                assertEquals(case.tenRadius, hint.radiusNormalized)
                assertEquals(BoundaryValue(10), hint.inner)
                assertEquals(BoundaryValue(9), hint.outer)
                assertEquals(kotlin.math.abs(radius.toDouble() - case.tenRadius.toDouble()), hint.distanceToBoundaryNormalized)
            }
            val exact = findBoundaryHints(case.target, point(case.tenRadius), 0f).single()
            assertEquals(0.0, exact.distanceToBoundaryNormalized)
            assertTrue(findBoundaryHints(case.target, point(Math.nextUp(case.tenRadius)), 0f).isEmpty())
        }
    }

    @Test
    fun `compound has no artificial score boundary at the printed recurve ten ring`() {
        for (target in listOf(profiles.compound40, profiles.compound60)) {
            for (radius in listOf(Math.nextDown(0.1f), 0.1f, Math.nextUp(0.1f))) {
                assertEquals(9, impact(target, radius).scoreFinal)
                assertTrue(findBoundaryHints(target, point(radius)).isEmpty())
            }
            assertFalse(target.scoreBoundaries().any { it.reference.radiusNormalized == 0.1f })
            assertEquals(0.05f, target.zones.single { it.score == 9 }.innerRadiusNormalized)
            assertEquals(0.2f, target.zones.single { it.score == 9 }.outerRadiusNormalized)
        }
    }

    @Test
    fun `nine eight and target edge suggestions remain available with X disabled`() {
        for (target in profiles.all) {
            for (radius in listOf(Math.nextDown(0.2f), 0.2f, Math.nextUp(0.2f))) {
                val hint = findBoundaryHints(target, point(radius)).single()
                assertEquals(0.2f, hint.radiusNormalized)
                assertEquals(BoundaryValue(9), hint.inner)
                assertEquals(BoundaryValue(8), hint.outer)
            }
            for (radius in listOf(Math.nextDown(1f), 1f, Math.nextUp(1f))) {
                val hint = findBoundaryHints(target, point(radius)).single()
                assertEquals(BoundaryType.TARGET_EDGE, hint.type)
                assertEquals(BoundaryValue(1), hint.inner)
                assertEquals(BoundaryValue(0), hint.outer)
            }
        }
    }

    @Test
    fun `manual judgments use the contextual ten boundary and never manufacture X`() {
        for (case in cases) {
            val original = impact(case.target, 0.075f)
            for (selected in listOf(10, 9)) {
                val decision = ScoreDecisionRequest.JudgeBoundary(
                    BoundaryReference(BoundaryType.SCORE_ZONES, case.tenRadius), BoundaryValue(selected), now)
                val judged = original.withFinalScore(case.target, decision, now)
                assertEquals(original.scoreCalculated, judged.scoreCalculated)
                assertEquals(selected, judged.scoreFinal)
                assertFalse(judged.isX)
                assertTrue(judged.isCordDecisionManual)
                assertEquals(decision.boundary, judged.finalScoreDecision.provenance.boundary)
            }
        }
    }

    @Test
    fun `a recurve ten boundary cannot be judged on compound and conversely`() {
        for (case in cases) {
            val otherRadius = if (case.tenRadius == 0.10f) 0.05f else 0.10f
            assertFailsWith<IllegalArgumentException> {
                impact(case.target, 0.075f).withFinalScore(case.target, ScoreDecisionRequest.JudgeBoundary(
                    BoundaryReference(BoundaryType.SCORE_ZONES, otherRadius), BoundaryValue(10), now), now)
            }
        }
    }

    @Test
    fun `X is disabled geometrically manually and in boundary definitions for every indoor profile`() {
        for (case in cases) {
            val target = case.target
            assertFalse(target.xRingEnabled)
            assertEquals("", target.xRingLabel)
            assertEquals(0f, target.innerTenRadiusNormalized)
            assertFalse(target.scoreBoundaries().any { it.reference.type == BoundaryType.X_RING })
            assertFalse(findBoundaryHints(target, point(0f), 1f).any { it.inner.isX || it.outer.isX })
            assertFalse(impact(target, 0f).isX)
            for (request in listOf(
                ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10, true), "Not an indoor X", now),
                ScoreDecisionRequest.JudgeBoundary(BoundaryReference(BoundaryType.SCORE_ZONES, case.tenRadius), BoundaryValue(10, true), now),
                ScoreDecisionRequest.JudgeBoundary(BoundaryReference(BoundaryType.X_RING, 0.05f), BoundaryValue(10, true), now),
            )) {
                assertFailsWith<IllegalArgumentException> { impact(target, 0f).withFinalScore(target, request, now) }
            }
        }
    }

    @Test
    fun `score decision and reposition retain the selected official profile and invalidate prior judgment`() {
        for (case in cases) {
            val target = case.target
            val original = impact(target, 0.15f).withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(
                BoundaryReference(BoundaryType.SCORE_ZONES, case.tenRadius), BoundaryValue(10), now), now)
            val moved = original.reposition(point(0.075f), now.plusSeconds(60), "Corrected point")
            assertEquals(target, moved.targetType)
            assertEquals(if (case.tenRadius == 0.1f) 10 else 9, moved.scoreCalculated)
            assertEquals(moved.scoreCalculated, moved.scoreFinal)
            assertFalse(moved.isX)
            assertFalse(moved.isMiss)
            assertEquals(ScoreDecisionKind.GEOMETRY, moved.finalScoreDecision.provenance.kind)
            assertEquals(original.finalScoreDecision, moved.repositions.single().invalidatedManualDecision)
        }
    }

    @Test
    fun `ten nine X and miss statistics follow the profile after reposition and validation`() {
        for (case in cases) {
            val target = case.target
            val original = impact(target, 0.025f)
            val editing = end(listOf(original, impact(target, 0.15f, "nine", 2), impact(target, 1.1f, "miss", 3)))
            val before = summarizeSessionScore(session(target, listOf(editing.validate(now))))
            assertEquals(19, before.totalScore)
            assertEquals(1, before.maxScoreCount)
            assertEquals(1, before.nineCount)
            assertEquals(0, before.xCount)
            assertEquals(1, before.missCount)
            val changed = editing.withImpact(original.reposition(point(0.075f), now.plusSeconds(60)))
            val updated = session(target, listOf(editing)).withEnd(changed).withEnd(changed.validate(now.plusSeconds(60)))
            val after = summarizeSessionScore(updated)
            assertEquals(if (case.tenRadius == 0.1f) 19 else 18, after.totalScore)
            assertEquals(if (case.tenRadius == 0.1f) 1 else 0, after.maxScoreCount)
            assertEquals(if (case.tenRadius == 0.1f) 1 else 2, after.nineCount)
            assertEquals(0, after.xCount)
            assertEquals(1, after.missCount)
        }
    }

    @Test
    fun `nine counter uses final values in validated ends only`() {
        for (target in profiles.all) {
            val corrected = impact(target, 0.025f).withFinalScore(target,
                ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed score", now), now)
            val validated = end(listOf(corrected)).validate(now)
            val editing = end(listOf(impact(target, 0.15f, "editing-nine")), "editing", 2)
            val summary = summarizeSessionScore(session(target, listOf(validated, editing)))
            assertEquals(9, summary.totalScore)
            assertEquals(1, summary.nineCount)
            assertEquals(0, summary.maxScoreCount)
            assertEquals(0, summary.xCount)
            assertEquals(0, summarizeSessionScore(session(target, emptyList())).nineCount)
        }
    }

    @Test
    fun `recurve impact cannot be inserted or reconstructed in a compound session`() {
        for ((recurve, compound) in listOf(profiles.recurve40 to profiles.compound40, profiles.recurve60 to profiles.compound60)) {
            val recurveEnd = end(listOf(impact(recurve, 0.075f)))
            assertFailsWith<IllegalArgumentException> { session(compound, listOf(recurveEnd)) }
            assertFailsWith<IllegalArgumentException> { session(compound, emptyList()).withEnd(recurveEnd) }
            assertFailsWith<IllegalArgumentException> { session(recurve, listOf(recurveEnd)).copy(targetType = compound) }
            assertFailsWith<IllegalArgumentException> {
                end(listOf(impact(compound, 0.075f))).withImpact(impact(recurve, 0.075f, "other", 2))
            }
        }
    }

    @Test
    fun `diameter and indoor flag never select a division implicitly`() {
        for ((recurve, compound) in listOf(profiles.recurve40 to profiles.compound40, profiles.recurve60 to profiles.compound60)) {
            assertEquals(recurve.diameterCm, compound.diameterCm)
            for (isIndoor in listOf(true, false)) {
                val recurveSession = session(recurve, listOf(end(listOf(impact(recurve, 0.075f))).validate(now))).copy(isIndoor = isIndoor)
                val compoundSession = session(compound, listOf(end(listOf(impact(compound, 0.075f))).validate(now))).copy(isIndoor = isIndoor)
                assertEquals(10, calculateSessionScore(recurveSession))
                assertEquals(9, calculateSessionScore(compoundSession))
            }
        }
    }

    @Test
    fun `published UUIDs are fixed valid v4 and distinct from all historical targets`() {
        for (case in cases) {
            assertEquals(case.uuid, case.target.uuid)
            val uuid = UUID.fromString(case.target.uuid)
            assertEquals(4, uuid.version())
            assertEquals(2, uuid.variant())
        }
        val allIds = (profiles.all + ClassicTargetFixtures.all).map { it.uuid }
        assertEquals(8, allIds.distinct().size)
    }

    @Test
    fun `published catalog and zones cannot be mutated and copies do not alter a definition`() {
        assertFailsWith<UnsupportedOperationException> { (profiles.all as MutableList<TargetType>)[0] = ClassicTargetFixtures.classic40 }
        for (target in profiles.all) {
            assertEquals(target, target.copy())
            assertEquals(target.hashCode(), target.copy().hashCode())
            assertFailsWith<UnsupportedOperationException> { (target.zones as MutableList<TargetZone>).clear() }
            val variant = target.copy(uuid = "custom-${target.uuid}", name = "Custom", defaultDistanceM = 12)
            assertEquals(12, variant.defaultDistanceM)
            assertEquals(if (target.diameterCm == 40) 18 else 25, target.defaultDistanceM)
        }
    }

    @Test
    fun `historical fixtures keep their identities defaults zones and generic X semantics`() {
        val historical = listOf(
            Triple(ClassicTargetFixtures.classic40, "11111111-1111-4111-8111-111111111111", 18),
            Triple(ClassicTargetFixtures.classic60, "22222222-2222-4222-8222-222222222222", 18),
            Triple(ClassicTargetFixtures.classic80, "33333333-3333-4333-8333-333333333333", 30),
            Triple(ClassicTargetFixtures.classic122, "44444444-4444-4444-8444-444444444444", 70),
        )
        assertEquals(listOf(40, 60, 80, 122), historical.map { it.first.diameterCm })
        for ((target, uuid, distance) in historical) {
            assertEquals(uuid, target.uuid)
            assertEquals(distance, target.defaultDistanceM)
            assertEquals(if (target.diameterCm <= 60) 3 else 6, target.defaultArrowCount)
            assertTrue(target.xRingEnabled)
            assertEquals("X", target.xRingLabel)
            assertEquals(0.05f, target.innerTenRadiusNormalized)
            val radii = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f)
            assertEquals(radii.dropLast(1), target.zones.map { it.innerRadiusNormalized })
            assertEquals(radii.drop(1), target.zones.map { it.outerRadiusNormalized })
            assertEquals((10 downTo 1).toList(), target.zones.map { it.score })
            assertEquals(10, impact(target, 0.075f).scoreFinal)
            assertTrue(impact(target, 0.025f).isX)
            assertFalse(impact(target, 0.075f).isX)
            assertEquals(1, summarizeSessionScore(session(target, listOf(end(listOf(impact(target, 0.025f))).validate(now)))).xCount)
        }
    }

    private fun point(radius: Float) = NormalizedPoint(radius, 0f)

    private fun impact(target: TargetType, radius: Float, uuid: String = "impact", index: Int = 1): ArrowImpact =
        scoreArrowImpact(uuid, index, target, point(radius), ScoringMode.COMPETITION, createdAt = now)

    private fun end(impacts: List<ArrowImpact>, uuid: String = "end", index: Int = 1) = End(uuid, index,
        EndStatus.EDITING, impacts, createdAt = now, updatedAt = impacts.maxOfOrNull { it.updatedAt } ?: now)

    private fun session(target: TargetType, ends: List<End>) = Session(uuid = "session", date = SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
        status = SessionStatus.IN_PROGRESS, startedAt = now, title = "Indoor profiles", targetType = target,
        distanceM = target.defaultDistanceM, arrowCountMode = ArrowCountMode.FREE, isIndoor = true,
        scoringMode = ScoringMode.COMPETITION, ends = ends, createdAt = now,
        updatedAt = ends.maxOfOrNull { it.updatedAt } ?: now)

    private data class ProfileCase(val target: TargetType, val diameterCm: Int, val distanceM: Int,
        val tenRadius: Float, val tenDiameterCm: Double, val uuid: String)
}
