package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplicitFinalScoreDecisionTest {
    private val target = ClassicTargetFixtures.classic40
    private val now = LocalDateTime.parse("2026-10-15T10:00:00")
    private val tenNine = BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f)
    private val xTen = BoundaryReference(BoundaryType.X_RING, 0.05f)
    private val edge = BoundaryReference(BoundaryType.TARGET_EDGE, 1f)

    @Test
    fun `all modes accept geometry without requiring judgments for hints`() {
        val point = NormalizedPoint(0.105f, 0f)
        assertEquals(tenNine, findBoundaryHints(target, point).single().reference)
        for (mode in ScoringMode.entries) {
            val resolved = resolveFinalScore(target, point, mode)
            assertEquals(9, resolved.scoreCalculated)
            assertEquals(9, resolved.scoreFinal)
            assertEquals(ScoreDecisionKind.GEOMETRY, resolved.provenance.kind)
            assertNull(resolved.provenance.decidedAt)
            assertFalse(resolved.provenance.isManual)
        }
        assertEquals(EndStatus.VALIDATED, end(listOf(impact()), validated = false).validate(now).status)
    }

    @Test
    fun `explicit upper boundary judgment preserves geometry and manual provenance`() {
        val value = impact(request = judgment(BoundaryValue(10)), inputMode = InputMode.AUTOMATIC)
        assertEquals(9, value.scoreCalculated)
        assertEquals(10, value.scoreFinal)
        assertFalse(value.isX)
        assertTrue(value.isCordDecisionManual)
        assertEquals(InputMode.AUTOMATIC, value.inputMode)
        assertEquals(tenNine, value.finalScoreDecision.provenance.boundary)
        assertEquals(now, value.finalScoreDecision.provenance.decidedAt)
    }

    @Test
    fun `manual confirmation of the same numeric score remains manual`() {
        val value = impact(request = judgment(BoundaryValue(9)))
        assertEquals(value.scoreCalculated, value.scoreFinal)
        assertTrue(value.isCordDecisionManual)
        assertTrue(value.finalScoreDecision.provenance.isManual)
        assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT, value.finalScoreDecision.provenance.kind)
    }

    @Test
    fun `human judgment is allowed outside the touch tolerance`() {
        val point = NormalizedPoint(0.5f, 0f)
        assertTrue(findBoundaryHints(target, point).none { it.reference == tenNine })
        val value = impact(radius = 0.5f, request = judgment(BoundaryValue(10)))
        assertEquals(6, value.scoreCalculated)
        assertEquals(10, value.scoreFinal)
    }

    @Test
    fun `legacy touch tolerance never promotes the final score`() {
        for (tolerance in listOf(0f, 0.015f, 2f, Float.MAX_VALUE)) {
            for (mode in ScoringMode.entries) {
                val value = scoreArrowImpact("impact", 1, target, NormalizedPoint(0.105f, 0f), mode,
                    createdAt = now, cordonToleranceNormalized = tolerance)
                assertEquals(9, value.scoreFinal)
            }
        }
    }

    @Test
    fun `nonexistent profile boundaries are rejected`() {
        for (reference in listOf(BoundaryReference(BoundaryType.SCORE_ZONES, 0.123f),
            BoundaryReference(BoundaryType.TARGET_EDGE, 0.9f))) {
            assertFailsWith<IllegalArgumentException> {
                impact(request = ScoreDecisionRequest.JudgeBoundary(reference, BoundaryValue(10), now))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            impact(profile = target.copy(xRingEnabled = false),
                request = ScoreDecisionRequest.JudgeBoundary(xTen, BoundaryValue(10), now))
        }
    }

    @Test
    fun `a legal target value still must be a side of the judged boundary`() {
        for (selected in listOf(BoundaryValue(8), BoundaryValue(0), BoundaryValue(10, true))) {
            assertFailsWith<IllegalArgumentException> { impact(request = judgment(selected)) }
        }
    }

    @Test
    fun `forged hint values are never trusted as boundary definitions`() {
        val actual = findBoundaryHints(target, NormalizedPoint(0.105f, 0f)).single()
        val forged = actual.copy(inner = BoundaryValue(99), outer = BoundaryValue(98))
        assertFailsWith<IllegalArgumentException> {
            impact(request = ScoreDecisionRequest.JudgeBoundary(forged.reference, forged.inner, now))
        }
        val resolved = impact(request = ScoreDecisionRequest.JudgeBoundary(forged.reference, BoundaryValue(10), now))
        assertEquals(10, resolved.scoreFinal) // Reconstructed real 10/9; forged sides are irrelevant.
    }

    @Test
    fun `non consecutive target scores constrain corrections and boundary sides`() {
        val sparse = target.copy(minScore = 5, zones = listOf(
            TargetZone(10, 0f, 0.25f, "center"), TargetZone(8, 0.25f, 0.75f, "middle"),
            TargetZone(5, 0.75f, 1f, "outer")))
        assertFailsWith<IllegalArgumentException> { impact(profile = sparse, request = correction(BoundaryValue(6))) }
        val reference = BoundaryReference(BoundaryType.SCORE_ZONES, 0.25f)
        val value = impact(profile = sparse,
            request = ScoreDecisionRequest.JudgeBoundary(reference, BoundaryValue(8), now))
        assertEquals(8, value.scoreFinal)
        assertFailsWith<IllegalArgumentException> {
            impact(profile = sparse, request = ScoreDecisionRequest.JudgeBoundary(reference, BoundaryValue(6), now))
        }
    }

    @Test
    fun `invalid manual scores are rejected with target context`() {
        for (score in listOf(-1, 11, 99)) {
            assertFailsWith<IllegalArgumentException> { impact(request = correction(BoundaryValue(score))) }
        }
        for (score in 0..10) assertEquals(score, impact(request = correction(BoundaryValue(score))).scoreFinal)
    }

    @Test
    fun `geometric miss changed to one clears the final miss flag`() {
        val value = impact(radius = 1.1f,
            request = ScoreDecisionRequest.JudgeBoundary(edge, BoundaryValue(1), now))
        assertEquals(0, value.scoreCalculated)
        assertEquals(1, value.scoreFinal)
        assertFalse(value.isMiss)
        assertFalse(value.isX)
    }

    @Test
    fun `geometric X changed to nine or M clears final X and derives M`() {
        for (score in listOf(9, 0)) {
            val value = impact(radius = 0f, request = correction(BoundaryValue(score)))
            assertEquals(10, value.scoreCalculated)
            assertTrue(value.finalScoreDecision.geometricValue.isX)
            assertFalse(value.isX)
            assertEquals(score == 0, value.isMiss)
        }
    }

    @Test
    fun `ten and ten X are distinct explicit judgments with the same score`() {
        for (isX in listOf(false, true)) {
            val value = impact(radius = 0.06f,
                request = ScoreDecisionRequest.JudgeBoundary(xTen, BoundaryValue(10, isX), now))
            assertEquals(10, value.scoreFinal)
            assertEquals(isX, value.isX)
            assertTrue(value.isCordDecisionManual)
            assertFalse(value.isMiss)
        }
    }

    @Test
    fun `maximum score never implies X automatically after a correction`() {
        val value = impact(radius = 0f, request = correction(BoundaryValue(10)))
        assertTrue(value.finalScoreDecision.geometricValue.isX)
        assertFalse(value.isX)
    }

    @Test
    fun `X requires activation maximum score and cannot coexist with M`() {
        assertFailsWith<IllegalArgumentException> { impact(request = correction(BoundaryValue(9, true))) }
        assertFailsWith<IllegalArgumentException> { impact(request = correction(BoundaryValue(0, true))) }
        assertFailsWith<IllegalArgumentException> {
            impact(profile = target.copy(xRingEnabled = false), request = correction(BoundaryValue(10, true)))
        }
    }

    @Test
    fun `exceptional corrections require a non blank reason and preserve position source`() {
        for (reason in listOf("", " ", "\t\n")) {
            assertFailsWith<IllegalArgumentException> {
                impact(request = ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(8), reason, now))
            }
        }
        val value = impact(request = correction(BoundaryValue(8)), inputMode = InputMode.CORRECTED)
        assertEquals(InputMode.CORRECTED, value.inputMode)
        assertEquals(ScoreDecisionKind.EXCEPTIONAL_CORRECTION, value.finalScoreDecision.provenance.kind)
        assertEquals("Misplaced point", value.finalScoreDecision.provenance.reason)
        assertEquals(now, value.finalScoreDecision.provenance.decidedAt)
        assertTrue(value.finalScoreDecision.provenance.isManual)
        assertFalse(value.isCordDecisionManual) // Manual correction is distinct from a boundary judgment.
    }

    @Test
    fun `started incomplete requests cannot fall back to geometric acceptance`() {
        for (request in listOf(ScoreDecisionRequest.JudgeBoundary(tenNine, null, now),
            ScoreDecisionRequest.ExceptionalCorrection(null, "Misplaced point", now))) {
            assertFailsWith<IllegalArgumentException> { impact(request = request) }
        }
    }

    @Test
    fun `constructor checks supplied scalar assertions against the validated decision`() {
        val point = NormalizedPoint(0.105f, 0f)
        fun reconstruct(distance: Float? = null, calculated: Int? = null, final: Int? = null,
            x: Boolean? = null, miss: Boolean? = null, manual: Boolean? = null) = ArrowImpact(
            uuid = "impact", index = 1, point = point, distanceFromCenter = distance,
            scoreCalculated = calculated, scoreFinal = final, isX = x, isMiss = miss,
            isCordDecisionManual = manual, inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = target)
        assertEquals(9, reconstruct(distance = 0.105f, calculated = 9, final = 9,
            x = false, miss = false, manual = false).scoreFinal)
        for (attempt in listOf<() -> ArrowImpact>(
            { reconstruct(distance = Float.NaN) }, { reconstruct(calculated = 10) },
            { reconstruct(final = 10) }, { reconstruct(x = true) }, { reconstruct(miss = true) },
            { reconstruct(manual = true) })) assertFailsWith<IllegalArgumentException> { attempt() }
    }

    @Test
    fun `a validated result for another target or point cannot be transplanted`() {
        for (decision in listOf(
            resolveFinalScore(ClassicTargetFixtures.classic60, NormalizedPoint(0.105f, 0f), ScoringMode.TRAINING),
            resolveFinalScore(target, NormalizedPoint(0.2f, 0f), ScoringMode.TRAINING))) {
            assertFailsWith<IllegalArgumentException> {
                ArrowImpact(uuid = "impact", index = 1, point = NormalizedPoint(0.105f, 0f),
                    targetType = target, finalScoreDecision = decision, inputMode = InputMode.MANUAL,
                    createdAt = now, updatedAt = now)
            }
        }
    }

    @Test
    fun `manual date must fit the reconstructed impact lifetime`() {
        for (date in listOf(now.minusSeconds(1), now.plusSeconds(1))) {
            assertFailsWith<IllegalArgumentException> { impact(request = judgment(BoundaryValue(10), date)) }
        }
    }

    @Test
    fun `backdated decisions are rejected even with a newer updatedAt`() {
        val original = impact(updatedAt = now.plusMinutes(10))
        assertFailsWith<IllegalArgumentException> {
            original.withFinalScore(target, judgment(BoundaryValue(10), now.plusMinutes(5)), now.plusMinutes(15))
        }
        assertEquals(9, original.scoreFinal)
    }

    @Test
    fun `an identical old judgment cannot be resubmitted as a newly backdated action`() {
        val request = judgment(BoundaryValue(10))
        val original = impact(request = request).copy(note = "Later metadata", updatedAt = now.plusMinutes(10))
        assertFailsWith<IllegalArgumentException> {
            original.withFinalScore(target, request, now.plusMinutes(15))
        }
    }

    @Test
    fun `new decisions preserve creation and require updatedAt to cover their date`() {
        val original = impact()
        val date = now.plusMinutes(5)
        val revised = original.withFinalScore(target, judgment(BoundaryValue(10), date), date)
        assertEquals(original.createdAt, revised.createdAt)
        assertEquals(date, revised.updatedAt)
        assertEquals(date, revised.finalScoreDecision.provenance.decidedAt)
        assertFailsWith<IllegalArgumentException> {
            original.withFinalScore(target, judgment(BoundaryValue(10), date), date.minusSeconds(1))
        }
    }

    @Test
    fun `metadata copies preserve the structured decision and scalar copies cannot change it`() {
        val original = impact(request = judgment(BoundaryValue(10)))
        val copied = original.copy(note = "Checked", updatedAt = now.plusMinutes(1))
        assertEquals(original.finalScoreDecision, copied.finalScoreDecision)
        assertEquals(original.createdAt, copied.createdAt)
        assertFailsWith<IllegalArgumentException> { original.copy(scoreFinal = 9) }
        assertFailsWith<IllegalArgumentException> { original.copy(scoreCalculated = 8) }
        assertFailsWith<IllegalArgumentException> { original.copy(isX = true) }
        assertFailsWith<IllegalArgumentException> { original.copy(createdAt = now.minusSeconds(1)) }
    }

    @Test
    fun `manual provenance cannot be erased through a geometric reset`() {
        val original = impact(request = judgment(BoundaryValue(10)))
        assertFailsWith<IllegalArgumentException> {
            original.withFinalScore(target, ScoreDecisionRequest.AcceptGeometry, now.plusMinutes(1))
        }
        val container = end(listOf(original), validated = false)
        assertFailsWith<IllegalArgumentException> { container.withImpact(impact(updatedAt = now.plusMinutes(1))) }
    }

    @Test
    fun `moving an adjudicated impact stays unavailable through copy or reconstruction replacement`() {
        val original = impact(request = judgment(BoundaryValue(10)))
        assertFailsWith<IllegalArgumentException> { original.copy(point = NormalizedPoint(0.3f, 0f)) }
        val container = end(listOf(original), validated = false)
        val moved = impact(radius = 0.3f, request = judgment(BoundaryValue(10), now.plusMinutes(1)),
            updatedAt = now.plusMinutes(1))
        assertFailsWith<IllegalArgumentException> { container.withImpact(moved) }
    }

    @Test
    fun `backdated reconstructed decisions cannot bypass deep aggregate replacement checks`() {
        val original = impact(updatedAt = now.plusMinutes(10))
        val previousEnd = end(listOf(original), validated = false)
        val previousSession = session(previousEnd)
        val stale = impact(request = judgment(BoundaryValue(10), now.plusMinutes(5)),
            updatedAt = now.plusMinutes(15))
        assertFailsWith<IllegalArgumentException> { previousEnd.withImpact(stale) }
        assertFailsWith<IllegalArgumentException> { previousSession.withEnd(end(listOf(stale), validated = false)) }
    }

    @Test
    fun `wrong target contexts are rejected by updates ends and sessions`() {
        val original = impact()
        val other = impact(profile = ClassicTargetFixtures.classic60, uuid = "other", index = 2)
        assertFailsWith<IllegalArgumentException> { original.withFinalScore(other.targetType, judgment(BoundaryValue(10)), now) }
        assertFailsWith<IllegalArgumentException> { end(listOf(original, other)) }
        assertFailsWith<IllegalArgumentException> { session(end(listOf(other))) }
        assertFailsWith<IllegalArgumentException> { session(end(listOf(original))).copy(targetType = other.targetType) }
    }

    @Test
    fun `corrected final flags drive end session totals X and miss counts`() {
        val values = listOf(
            impact(radius = 0f, request = correction(BoundaryValue(9)), uuid = "nine", index = 1),
            impact(radius = 1.1f, request = ScoreDecisionRequest.JudgeBoundary(edge, BoundaryValue(1), now), uuid = "one", index = 2),
            impact(radius = 0f, request = correction(BoundaryValue(0)), uuid = "miss", index = 3),
            impact(radius = 0.075f, request = ScoreDecisionRequest.JudgeBoundary(xTen, BoundaryValue(10, true), now), uuid = "X", index = 4),
            impact(radius = 0.075f, request = ScoreDecisionRequest.JudgeBoundary(xTen, BoundaryValue(10), now), uuid = "ten", index = 5),
        )
        val recordedEnd = end(values)
        val recordedSession = session(recordedEnd)
        val summary = summarizeSessionScore(recordedSession)
        assertEquals(30, calculateEndScore(recordedEnd))
        assertEquals(30, calculateSessionScore(recordedSession))
        assertEquals(30, summary.totalScore)
        assertEquals(50, summary.maxPossibleScore)
        assertEquals(2, summary.maxScoreCount)
        assertEquals(1, summary.xCount)
        assertEquals(1, summary.missCount)
    }

    @Test
    fun `normal typed modification propagates monotonically through existing parent methods`() {
        val originalImpact = impact()
        val originalEnd = end(listOf(originalImpact), validated = false)
        val originalSession = session(originalEnd)
        val date = now.plusMinutes(5)
        val revised = originalImpact.withFinalScore(target, judgment(BoundaryValue(10), date), date)
        val revisedEnd = originalEnd.withImpact(revised).validate(date)
        val revisedSession = originalSession.withEnd(revisedEnd)
        assertEquals(date, revised.updatedAt)
        assertEquals(date, revisedEnd.updatedAt)
        assertEquals(date, revisedSession.updatedAt)
        assertEquals(now, revised.createdAt)
        assertEquals(now, originalSession.updatedAt)
    }

    @Test
    fun `validated geometry agrees with the unchanged geometric engine on every fixture`() {
        for (profile in ClassicTargetFixtures.all) {
            for (radius in listOf(0f, 0.05f, 0.1f, 0.105f, 0.195f, 0.3f, 0.5f, 0.9f, 1f, 1.1f, Float.MAX_VALUE)) {
                val point = NormalizedPoint(radius, 0f)
                val geometric = calculateScore(profile, point)
                val resolved = resolveFinalScore(profile, point, ScoringMode.COMPETITION)
                assertEquals(geometric.distanceFromCenter, resolved.distanceFromCenter)
                assertEquals(BoundaryValue(geometric.score, geometric.isX), resolved.geometricValue)
                assertEquals(resolved.geometricValue, resolved.value)
                assertEquals(geometric.isMiss, resolved.isMiss)
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy uncontextualized resolver and legal scalar overrides remain closed`() {
        val geometric = calculateScore(target, NormalizedPoint(0.105f, 0f))
        assertFailsWith<IllegalArgumentException> { resolveFinalScore(geometric, ScoringMode.COMPETITION) }
        assertFailsWith<IllegalArgumentException> {
            scoreArrowImpact("legacy", 1, target, NormalizedPoint(0.105f, 0f), ScoringMode.MANUAL_CORDON,
                userSelectedScore = 10, createdAt = now)
        }
    }

    private fun judgment(value: BoundaryValue, date: LocalDateTime = now) =
        ScoreDecisionRequest.JudgeBoundary(tenNine, value, date)

    private fun correction(value: BoundaryValue) =
        ScoreDecisionRequest.ExceptionalCorrection(value, "Misplaced point", now)

    private fun impact(radius: Float = 0.105f, request: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry,
        profile: TargetType = target, updatedAt: LocalDateTime = now, inputMode: InputMode = InputMode.MANUAL,
        uuid: String = "impact", index: Int = 1): ArrowImpact =
        scoreArrowImpact(uuid, index, profile, NormalizedPoint(radius, 0f), ScoringMode.COMPETITION,
            decision = request, inputMode = inputMode, createdAt = now, updatedAt = updatedAt)

    private fun end(impacts: List<ArrowImpact>, validated: Boolean = true): End {
        val date = impacts.maxOfOrNull { it.updatedAt } ?: now
        return End("end", 1, if (validated) EndStatus.VALIDATED else EndStatus.EDITING, impacts,
            createdAt = now, updatedAt = date, validatedAt = if (validated) date else null)
    }

    private fun session(end: End): Session = Session(uuid = "session", date = now,
        status = SessionStatus.IN_PROGRESS, startedAt = now, title = "Decision tests", targetType = target,
        distanceM = 18, arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
        ends = listOf(end), createdAt = now, updatedAt = end.updatedAt)
}
