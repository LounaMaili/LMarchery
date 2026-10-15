package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UtcDomainTimestampTest {
    private val target = ClassicTargetFixtures.classic40
    private val created = UtcTimestamps.parse("2026-10-25T02:50:00+02:00") // 00:50Z, before the clock overlap.
    private val changed = UtcTimestamps.parse("2026-10-25T02:10:00+01:00") // 01:10Z, after the clock overlap.
    private val stale = UtcTimestamps.parse("2026-10-25T02:55:00+02:00") // 00:55Z, despite its later wall time.

    @Test
    fun `manual decision propagates in instant order while preserving session civil date`() {
        val original = impact()
        val previousEnd = end(original)
        val previousSession = session(previousEnd)
        val judged = original.withFinalScore(target, judgment(changed), changed)
        val revised = previousSession.withEnd(previousEnd.withImpact(judged))
        assertEquals(changed, revised.updatedAt)
        assertEquals(changed, revised.ends.single().updatedAt)
        assertEquals(changed, revised.ends.single().impacts.single().finalScoreDecision.provenance.decidedAt)
        assertEquals(created, revised.createdAt)
        assertEquals(previousSession.date, revised.date)
        assertEquals("2026-10-25T02:50", revised.date.localDateTime.toString())
        assertEquals("Europe/Paris", revised.date.zoneId.id)
        assertEquals(created, revised.date.toInstant())
        assertEquals(created, previousSession.updatedAt)
        // The civil label is independent of the event dates; a consultation never rewrites it.
        val civil = SessionDateTime.of(LocalDateTime.parse("2026-07-15T00:30:00"), ZoneId.of("Europe/Paris"))
        val labelled = revised.copy(date = civil)
        assertEquals("2026-07-14T18:30", labelled.date.atZone(ZoneId.of("America/New_York")).toLocalDateTime().toString())
        assertEquals(civil, labelled.date)
        assertEquals(revised.createdAt, labelled.createdAt)
        assertEquals(revised.startedAt, labelled.startedAt)
    }

    @Test
    fun `old child integration cannot lower a parent revision expressed in a different offset`() {
        val parentDate = UtcTimestamps.parse("2026-10-25T10:40:00+09:00") // 01:40Z.
        val previousEnd = end(impact())
        val previous = session(previousEnd).copy(updatedAt = parentDate)
        val judged = impact().withFinalScore(target, judgment(changed), changed)
        val revised = previous.withEnd(previousEnd.withImpact(judged))
        assertEquals(parentDate, revised.updatedAt)
        assertTrue(revised.updatedAt.isAfter(revised.ends.single().updatedAt))
    }

    @Test
    fun `backdated judgments and repositionings are rejected even when local wall time looks later`() {
        val original = impact(updated = changed)
        assertFailsWith<IllegalArgumentException> { original.withFinalScore(target, judgment(stale), changed) }
        assertFailsWith<IllegalArgumentException> { original.reposition(NormalizedPoint(0.25f, 0f), stale) }
        val reconstructed = impact(updated = changed.plusSeconds(60), request = judgment(stale))
        assertFailsWith<IllegalArgumentException> { end(original).withImpact(reconstructed) }
        assertFailsWith<IllegalArgumentException> { session(end(original)).withEnd(end(reconstructed)) }
    }

    @Test
    fun `reposition evidence retains absolute manual and historical dates across an offset change`() {
        val judged = impact().withFinalScore(target, judgment(changed), changed)
        val movedAt = UtcTimestamps.parse("2026-10-25T02:20:00+01:00")
        val moved = judged.reposition(NormalizedPoint(0.25f, 0f), movedAt, "Position corrected after clock change")
        val trace = moved.repositions.single()
        assertEquals(created, trace.impactCreatedAt)
        assertEquals(changed, trace.previousUpdatedAt)
        assertEquals(changed, trace.invalidatedManualDecision!!.provenance.decidedAt)
        assertEquals(movedAt, trace.changedAt)
        assertEquals(8, moved.scoreCalculated)
        assertEquals(8, moved.scoreFinal)
        assertEquals(moved.repositions, moved.copy(note = "Checked", updatedAt = movedAt.plusNanos(1)).repositions)
        assertFailsWith<IllegalArgumentException> { moved.copy(updatedAt = movedAt.minusNanos(1)) }
    }

    @Test
    fun `validation and closure reject earlier instants with apparently later local times`() {
        val revisedEnd = end(impact(updated = changed))
        assertFailsWith<IllegalArgumentException> { revisedEnd.validate(stale) }
        val previous = session(revisedEnd)
        assertFailsWith<IllegalArgumentException> { previous.complete(stale) }
        val validated = revisedEnd.validate(changed)
        val completed = previous.withEnd(validated).complete(changed)
        assertEquals(changed, completed.endedAt)
        assertEquals(9, calculateSessionScore(completed))
    }

    @Test
    fun `reconstructed snapshots cannot hide inconsistent instant ordering`() {
        assertFailsWith<IllegalArgumentException> {
            scoreArrowImpact("impact", 1, target, NormalizedPoint(0.15f, 0f), ScoringMode.TRAINING,
                createdAt = changed, updatedAt = stale)
        }
        val child = impact(updated = changed)
        assertFailsWith<IllegalArgumentException> {
            End("end", 1, EndStatus.EDITING, listOf(child), createdAt = created, updatedAt = stale)
        }
        assertFailsWith<IllegalArgumentException> { session(end(child), updated = stale) }
        assertFailsWith<IllegalArgumentException> { child.copy(createdAt = created.minusNanos(1)) }
    }

    private fun judgment(date: Instant) = ScoreDecisionRequest.JudgeBoundary(
        BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f), BoundaryValue(10), date)

    private fun impact(updated: Instant = created, request: ScoreDecisionRequest = ScoreDecisionRequest.AcceptGeometry) =
        scoreArrowImpact("impact", 1, target, NormalizedPoint(0.15f, 0f), ScoringMode.COMPETITION,
            createdAt = created, updatedAt = updated, decision = request)

    private fun end(impact: ArrowImpact) = End("end", 1, EndStatus.EDITING, listOf(impact),
        createdAt = created, updatedAt = impact.updatedAt)

    private fun session(end: End, updated: Instant = end.updatedAt) = Session("session",
        SessionDateTime.fromInstant(created, ZoneId.of("Europe/Paris")), SessionStatus.IN_PROGRESS,
        startedAt = created, title = "UTC chronology", targetType = target, distanceM = 18,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
        ends = listOf(end), createdAt = created, updatedAt = updated)
}
