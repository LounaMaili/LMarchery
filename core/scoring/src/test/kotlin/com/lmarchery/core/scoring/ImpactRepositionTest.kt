package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ImpactReposition
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImpactRepositionTest {
    private val target = ClassicTargetFixtures.classic40
    private val now = LocalDateTime.of(2026, 10, 15, 10, 0)
    private val later = now.plusMinutes(10)
    private val eight = NormalizedPoint(0.25f, 0f)
    private val boundary = BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f)

    @Test
    fun `geometric nine moves to eight with identity creation and position origin preserved`() {
        val original = impact(inputMode = InputMode.AUTOMATIC).copy(note = "Original note")
        val moved = original.reposition(eight, later)
        assertEquals(original.uuid, moved.uuid)
        assertEquals(original.index, moved.index)
        assertEquals(original.createdAt, moved.createdAt)
        assertEquals(original.targetType, moved.targetType)
        assertEquals(InputMode.AUTOMATIC, moved.inputMode)
        assertEquals(original.note, moved.note)
        assertEquals(later, moved.updatedAt)
        assertEquals(0.25f, moved.distanceFromCenter)
        assertEquals(8, moved.scoreCalculated)
        assertEquals(8, moved.scoreFinal)
        assertFalse(moved.isX)
        assertFalse(moved.isMiss)
        assertEquals(ScoreDecisionKind.GEOMETRY, moved.finalScoreDecision.provenance.kind)
        assertNull(moved.repositions.single().invalidatedManualDecision)
    }

    @Test
    fun `boundary judged nine to ten resets to eight and retains the invalidated decision`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val moved = original.reposition(eight, later, "Point placed in the wrong ring")
        assertEquals(9, original.scoreCalculated)
        assertEquals(10, original.scoreFinal)
        assertEquals(8, moved.scoreCalculated)
        assertEquals(8, moved.scoreFinal)
        assertFalse(moved.isCordDecisionManual)
        val trace = moved.repositions.single()
        assertEquals(original.finalScoreDecision, trace.invalidatedManualDecision)
        assertEquals(boundary, trace.previousDecision.provenance.boundary)
        assertEquals(original.point, trace.previousPoint)
        assertEquals(eight, trace.newPoint)
        assertEquals(now, trace.previousUpdatedAt)
        assertEquals(later, trace.changedAt)
        assertEquals("Point placed in the wrong ring", trace.reason)
    }

    @Test
    fun `exceptional correction is invalidated with its original reason and date`() {
        val original = impact().withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Initial scoring correction", now), now)
        val moved = original.reposition(eight, later, "Correcting the position instead")
        assertEquals(8, moved.scoreFinal)
        val old = moved.repositions.single().invalidatedManualDecision!!
        assertEquals(ScoreDecisionKind.EXCEPTIONAL_CORRECTION, old.provenance.kind)
        assertEquals("Initial scoring correction", old.provenance.reason)
        assertEquals(now, old.provenance.decidedAt)
        assertEquals(5, old.scoreFinal)
    }

    @Test
    fun `manual confirmation of the same score also requires invalidation evidence`() {
        val original = impact().withFinalScore(target, judgment(9, now), now)
        assertFailsWith<IllegalArgumentException> { original.reposition(eight, later) }
        assertEquals(original.finalScoreDecision,
            original.reposition(eight, later, "Misplaced point").repositions.single().invalidatedManualDecision)
    }

    @Test
    fun `X moves to nine without retaining X`() {
        val moved = impact(radius = 0f).reposition(NormalizedPoint(0.15f, 0f), later)
        assertEquals(9, moved.scoreFinal)
        assertFalse(moved.isX)
        assertFalse(moved.isMiss)
    }

    @Test
    fun `miss moves into target and a valid hit moves outside`() {
        val miss = impact(radius = 1.1f)
        val hit = miss.reposition(eight, later)
        assertEquals(8, hit.scoreFinal)
        assertFalse(hit.isMiss)
        val movedOut = hit.reposition(NormalizedPoint(1.1f, 0f), later.plusMinutes(1))
        assertEquals(0, movedOut.scoreFinal)
        assertTrue(movedOut.isMiss)
        assertFalse(movedOut.isX)
        assertEquals(2, movedOut.repositions.size)
    }

    @Test
    fun `new boundary judgment after reposition retains prior invalidation`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val moved = original.reposition(eight, later, "New position")
        val judged = moved.withFinalScore(target, judgment(10, later.plusMinutes(1)), later.plusMinutes(1))
        assertEquals(8, judged.scoreCalculated)
        assertEquals(10, judged.scoreFinal)
        assertEquals(moved.repositions, judged.repositions)
        assertTrue(judged.isCordDecisionManual)
        // A human judgment is deliberately permitted outside the UX suggestion tolerance.
        assertTrue(findBoundaryHints(target, eight).isEmpty())
    }

    @Test
    fun `repeated moves retain every invalidated manual decision without a general score log`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val first = original.reposition(eight, later, "First position correction")
        val judged = first.withFinalScore(target, judgment(9, later), later)
        val second = judged.reposition(NormalizedPoint(0.35f, 0f), later, "Second position correction")
        assertEquals(listOf(original.finalScoreDecision, judged.finalScoreDecision),
            second.repositions.map { it.invalidatedManualDecision })
        // Several legitimate revisions at the same time remain non-decreasing, without clock increments.
        assertEquals(later, second.updatedAt)
        assertEquals(7, second.scoreFinal)
        assertEquals(second, second.copy())
        assertEquals(second.hashCode(), second.copy().hashCode())
    }

    @Test
    fun `old objects and metadata copies preserve all decisions and evidence`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val moved = original.reposition(eight, later, "Misplaced point")
        assertEquals(NormalizedPoint(0.15f, 0f), original.point)
        assertEquals(10, original.scoreFinal)
        assertTrue(original.repositions.isEmpty())
        assertEquals(moved.repositions, moved.copy(note = "Checked", updatedAt = later.plusSeconds(1)).repositions)
        assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT, original.finalScoreDecision.provenance.kind)
    }

    @Test
    fun `backdated movement is rejected even after a metadata modification`() {
        val original = impact().copy(note = "Checked", updatedAt = later)
        assertFailsWith<IllegalArgumentException> { original.reposition(eight, later.minusNanos(1)) }
        assertEquals(later, original.reposition(eight, later).updatedAt)
    }

    @Test
    fun `manual invalidation needs a nonblank position reason`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        for (reason in listOf(null, "", " \t\n")) {
            assertFailsWith<IllegalArgumentException> { original.reposition(eight, later, reason) }
        }
        assertFailsWith<IllegalArgumentException> { impact().reposition(eight, later, " ") }
    }

    @Test
    fun `an unchanged point is rejected without clearing a manual decision`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        assertFailsWith<IllegalArgumentException> { original.reposition(original.point, later, "No actual move") }
        assertTrue(original.isCordDecisionManual)
    }

    @Test
    fun `unrepresentable distance and invalid coordinates cannot create a revision`() {
        val original = impact()
        assertFailsWith<IllegalArgumentException> { original.reposition(NormalizedPoint(Float.NaN, 0f), later) }
        assertFailsWith<IllegalArgumentException> { original.reposition(NormalizedPoint(Float.POSITIVE_INFINITY, 0f), later) }
        assertFailsWith<IllegalArgumentException> { original.reposition(NormalizedPoint(Float.MAX_VALUE, Float.MAX_VALUE), later) }
        assertTrue(original.repositions.isEmpty())
    }

    @Test
    fun `copy cannot move either geometric or manually judged impacts`() {
        for (original in listOf(impact(), impact().withFinalScore(target, judgment(10, now), now))) {
            assertFailsWith<IllegalArgumentException> { original.copy(point = eight, updatedAt = later) }
        }
        assertFailsWith<IllegalArgumentException> { impact().copy(inputMode = InputMode.CORRECTED) }
    }

    @Test
    fun `geometric reconstruction with same UUID cannot bypass end or session replacement`() {
        val original = impact()
        val raw = rebuild(original, point = eight, updatedAt = later)
        val end = end(listOf(original))
        val session = session(end)
        assertFailsWith<IllegalArgumentException> { end.withImpact(raw) }
        assertFailsWith<IllegalArgumentException> { end.copy(impacts = listOf(raw), updatedAt = later) }
        assertFailsWith<IllegalArgumentException> { session.withEnd(end(listOf(raw))) }
        assertFailsWith<IllegalArgumentException> { session.copy(ends = listOf(end(listOf(raw))), updatedAt = later) }
    }

    @Test
    fun `valid explicit revision propagates through end and session with parent monotonicity`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val previousEnd = end(listOf(original))
        val previousSession = session(previousEnd).copy(updatedAt = later.plusHours(1))
        val moved = original.reposition(eight, later, "Misplaced point")
        val nextEnd = previousEnd.withImpact(moved)
        val nextSession = previousSession.withEnd(nextEnd)
        assertEquals(later, nextEnd.updatedAt)
        assertEquals(previousSession.updatedAt, nextSession.updatedAt)
        assertEquals(moved, nextSession.ends.single().impacts.single())
        assertEquals(8, calculateEndScore(nextEnd))
        assertEquals(10, calculateEndScore(previousEnd))
        assertEquals(0, calculateSessionScore(nextSession)) // Editing ends are still excluded.
        val validated = nextSession.withEnd(nextEnd.validate(later))
        assertEquals(8, calculateSessionScore(validated))
    }

    @Test
    fun `a batched reposition chain can be integrated without intermediate aggregate updates`() {
        val original = impact()
        val moved = original.reposition(eight, later).reposition(NormalizedPoint(0.35f, 0f), later.plusSeconds(1))
        assertEquals(moved, end(listOf(original)).withImpact(moved).impacts.single())
    }

    @Test
    fun `a manual decision then a move can be integrated in one aggregate update`() {
        val original = impact()
        val judged = original.withFinalScore(target, judgment(10, now.plusMinutes(1)), now.plusMinutes(1))
        val moved = judged.reposition(eight, later, "Corrected point")
        assertEquals(moved, end(listOf(original)).withImpact(moved).impacts.single())
        assertEquals(judged.finalScoreDecision, moved.repositions.single().invalidatedManualDecision)
    }

    @Test
    fun `statistics use new final flags after reposition and validation`() {
        val x = impact(radius = 0f)
        val miss = impact(radius = 1.1f, uuid = "miss", index = 2)
        val previous = end(listOf(x, miss))
        val corrected = previous.withImpact(x.reposition(eight, later))
            .withImpact(miss.reposition(NormalizedPoint(0f, 0f), later)).validate(later)
        val summary = summarizeSessionScore(session(corrected))
        assertEquals(18, summary.totalScore)
        assertEquals(1, summary.xCount)
        assertEquals(0, summary.missCount)
        assertEquals(1, summary.maxScoreCount)
        val outside = end(listOf(x.reposition(NormalizedPoint(1.1f, 0f), later))).validate(later)
        assertEquals(1, summarizeSessionScore(session(outside)).missCount)
        assertEquals(0, summarizeSessionScore(session(outside)).xCount)
    }

    @Test
    fun `reconstruction cannot erase or truncate earlier invalidations even when point returns to original`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val first = original.reposition(eight, later, "First correction")
        val second = first.reposition(original.point, later.plusMinutes(1))
        assertNotEquals(original.finalScoreDecision, second.finalScoreDecision)
        val noHistory = rebuild(second, repositions = emptyList())
        val truncated = rebuild(second, repositions = listOf(second.repositions.last()))
        for (forged in listOf(noHistory, truncated)) {
            assertFailsWith<IllegalArgumentException> { end(listOf(second)).withImpact(forged) }
        }
    }

    @Test
    fun `evidence cannot be borrowed from another UUID profile position or origin`() {
        val moved = impact().reposition(eight, later)
        assertFailsWith<IllegalArgumentException> { rebuild(moved, uuid = "another") }
        assertFailsWith<IllegalArgumentException> { rebuild(moved, point = NormalizedPoint(0.35f, 0f)) }
        assertFailsWith<IllegalArgumentException> { rebuild(moved, inputMode = InputMode.CORRECTED) }
        assertFailsWith<IllegalArgumentException> {
            ArrowImpact(moved.uuid, moved.index, moved.point, inputMode = moved.inputMode,
                createdAt = now.minusSeconds(1), updatedAt = later, targetType = target, repositions = moved.repositions)
        }
        assertFailsWith<IllegalArgumentException> {
            ArrowImpact(moved.uuid, moved.index, moved.point, inputMode = moved.inputMode,
                createdAt = now, updatedAt = later, targetType = ClassicTargetFixtures.classic60, repositions = moved.repositions)
        }
    }

    @Test
    fun `a parallel position history cannot rewrite existing evidence`() {
        val original = impact().withFinalScore(target, judgment(10, now), now)
        val first = original.reposition(eight, later, "Recorded correction")
        val rewritten = original.reposition(eight, later, "Different explanation")
        assertFailsWith<IllegalArgumentException> { end(listOf(first)).withImpact(rewritten) }
        assertFailsWith<IllegalArgumentException> { session(end(listOf(first))).withEnd(end(listOf(rewritten))) }
    }

    @Test
    fun `reordered and future traces and old manual decisions are rejected by construction`() {
        val first = impact().reposition(eight, later)
        val second = first.reposition(NormalizedPoint(0.35f, 0f), later.plusMinutes(1))
        assertFailsWith<IllegalArgumentException> { rebuild(second, repositions = second.repositions.reversed()) }
        assertFailsWith<IllegalArgumentException> { rebuild(first, updatedAt = later.minusSeconds(1)) }
        val backdated = FinalScoreDecision.resolve(target, eight, judgment(10, now))
        assertFailsWith<IllegalArgumentException> {
            ArrowImpact(first.uuid, first.index, eight, inputMode = first.inputMode, createdAt = now, updatedAt = later,
                targetType = target, finalScoreDecision = backdated, repositions = first.repositions)
        }
    }

    @Test
    fun `evidence of an older decision or metadata revision cannot replace a newer baseline`() {
        val old = impact()
        val moved = old.reposition(eight, later)
        val metadata = old.copy(updatedAt = now.plusMinutes(1))
        assertFailsWith<IllegalArgumentException> { end(listOf(metadata)).withImpact(moved) }
        val judged = old.withFinalScore(target, judgment(10, now), now)
        assertFailsWith<IllegalArgumentException> { end(listOf(judged)).withImpact(moved) }
    }

    @Test
    fun `history has defensive storage and survives later score corrections`() {
        val moved = impact().reposition(eight, later)
        val mutable = moved.repositions.toMutableList()
        val reconstructed = rebuild(moved, repositions = mutable)
        mutable.clear()
        assertEquals(moved, reconstructed)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (reconstructed.repositions as MutableList<ImpactReposition>).clear() }
        val corrected = reconstructed.withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(1), "New score correction", later), later)
        assertEquals(moved.repositions, corrected.repositions)
    }

    @Test
    fun `session replacement checks impacts even under a different end UUID`() {
        val original = impact()
        val previous = session(end(listOf(original)))
        val forgedEnd = end(listOf(rebuild(original, point = eight, updatedAt = later)), uuid = "new-end")
        assertFailsWith<IllegalArgumentException> { previous.withEnd(forgedEnd) } // Duplicate impact UUID too.
        assertFailsWith<IllegalArgumentException> { previous.copy(ends = listOf(forgedEnd), updatedAt = later) }
        val explicitEnd = end(listOf(original.reposition(eight, later)), uuid = "new-end")
        assertEquals(eight, previous.copy(ends = listOf(explicitEnd), updatedAt = later).ends.single().impacts.single().point)
    }

    @Test
    fun `a traced impact cannot disappear through aggregate copy`() {
        val moved = impact().reposition(eight, later)
        val end = end(listOf(moved))
        assertFailsWith<IllegalArgumentException> { end.copy(impacts = emptyList()) }
        assertFailsWith<IllegalArgumentException> { session(end).copy(ends = emptyList()) }
    }

    @Test
    fun `validated end rejects movement through methods copy and reconstructed session replacement`() {
        val original = impact()
        val validated = end(listOf(original)).validate(now)
        val moved = original.reposition(eight, now)
        assertFailsWith<IllegalArgumentException> { validated.withImpact(moved) }
        assertFailsWith<IllegalArgumentException> { validated.copy(impacts = listOf(moved)) }
        assertFailsWith<IllegalArgumentException> { validated.copy(status = EndStatus.EDITING, validatedAt = null) }
        val forged = end(listOf(moved)).validate(now)
        assertFailsWith<IllegalArgumentException> { session(validated).withEnd(forged) }
        assertFailsWith<IllegalArgumentException> { session(validated).copy(ends = listOf(forged)) }
        assertFailsWith<IllegalArgumentException> { session(validated).copy(ends = listOf(end(listOf(moved), uuid = "new-end"))) }
        assertEquals(validated.impacts, validated.copy(notes = "Metadata", updatedAt = later).impacts)
    }

    @Test
    fun `completed session rejects reposition and reopening through all replacement paths`() {
        val original = impact()
        val previousEnd = end(listOf(original))
        val completed = session(previousEnd).complete(now)
        val changedEnd = previousEnd.withImpact(original.reposition(eight, now))
        assertFailsWith<IllegalArgumentException> { completed.withEnd(changedEnd) }
        assertFailsWith<IllegalArgumentException> { completed.copy(ends = listOf(changedEnd)) }
        assertFailsWith<IllegalArgumentException> { completed.copy(status = SessionStatus.IN_PROGRESS, endedAt = null) }
        assertEquals(completed.ends, completed.copy(notes = "Metadata", updatedAt = later).ends)
    }

    private fun judgment(score: Int, date: LocalDateTime) = ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(score), date)

    private fun impact(radius: Float = 0.15f, inputMode: InputMode = InputMode.MANUAL,
        uuid: String = "impact", index: Int = 1): ArrowImpact =
        scoreArrowImpact(uuid, index, target, NormalizedPoint(radius, 0f), ScoringMode.COMPETITION,
            inputMode = inputMode, createdAt = now)

    private fun rebuild(source: ArrowImpact, point: NormalizedPoint = source.point, uuid: String = source.uuid,
        inputMode: InputMode = source.inputMode, updatedAt: LocalDateTime = source.updatedAt,
        repositions: List<ImpactReposition> = source.repositions): ArrowImpact =
        ArrowImpact(uuid, source.index, point, inputMode = inputMode, createdAt = source.createdAt,
            updatedAt = updatedAt, targetType = source.targetType, repositions = repositions)

    private fun end(impacts: List<ArrowImpact>, uuid: String = "end") = End(uuid, 1, EndStatus.EDITING,
        impacts, createdAt = now, updatedAt = impacts.maxOfOrNull { it.updatedAt } ?: now)

    private fun session(end: End) = Session(uuid = "session", date = now, status = SessionStatus.IN_PROGRESS,
        startedAt = now, title = "Reposition tests", targetType = target, distanceM = 18,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
        ends = listOf(end), createdAt = now, updatedAt = end.updatedAt)
}
