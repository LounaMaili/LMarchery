package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.model.time.SessionDateTime
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class SessionUpdateTest {
    private val now = Instant.parse("2026-10-15T10:00:00.123456789Z")
    private val target = TargetType("target", "Test", "test", 40, 10, 1, 3, 18, false,
        true, "X", 0.05f, (10 downTo 1).map { score ->
            TargetZone(score, (10 - score) / 10f, (11 - score) / 10f, "test")
        })
    private fun draft() = Session("session", SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
        SessionStatus.DRAFT, title = "Test", targetType = target, distanceM = 18,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.TRAINING,
        createdAt = now, updatedAt = now)
    private fun impact() = ArrowImpact("impact", 1, NormalizedPoint(0.105f, 0f), inputMode = InputMode.MANUAL,
        createdAt = now, updatedAt = now, targetType = target)
    private fun end(impact: ArrowImpact = impact()) = End("end", 1, EndStatus.EDITING,
        listOf(impact), createdAt = now, updatedAt = impact.updatedAt)
    private fun populated() = draft().start(now).withEnd(end())

    @Test fun `explicit equipment operation changes only metadata in every lifecycle status`() {
        for (old in listOf(draft(), populated(), populated().complete(now.plusSeconds(1)))) {
            val next = old.withEquipmentReferences("opaque-bow", "opaque-arrows", now.plusSeconds(2))
            validateSessionUpdate(old, next)
            assertEquals(old.copy(bowUuid = "opaque-bow", arrowSetUuid = "opaque-arrows", updatedAt = now.plusSeconds(2)), next)
            assertEquals(old.ends, next.ends); assertEquals(old.endedAt, next.endedAt); assertEquals(old.status, next.status)
        }
    }
    @Test fun `equipment metadata rejects backdating while unchanged references keep the original value`() {
        val old = populated().copy(updatedAt = now.plusSeconds(1))
        assertFailsWith<IllegalArgumentException> { old.withEquipmentReferences("bow", null, now) }
        assertSame(old, old.withEquipmentReferences(old.bowUuid, old.arrowSetUuid, now))
    }
    @Test fun `equipment revision never reopens score editing after completion`() {
        val old = populated().complete(now.plusSeconds(1)).withEquipmentReferences("bow", null, now.plusSeconds(2))
        assertFailsWith<IllegalArgumentException> { old.withEnd(end()) }
        assertFailsWith<IllegalArgumentException> { old.copy(status = SessionStatus.IN_PROGRESS, endedAt = null) }
        assertFailsWith<IllegalArgumentException> { old.copy(ends = emptyList()) }
    }

    // Deliberately reconstruct isolated snapshots: unlike copy, this has no previous-state context.
    private fun rebuild(s: Session, uuid: String = s.uuid, target: TargetType = s.targetType,
        status: SessionStatus = s.status, started: Instant? = s.startedAt, ended: Instant? = s.endedAt,
        ends: List<End> = s.ends, created: Instant = s.createdAt, updated: Instant = s.updatedAt) =
        Session(uuid, s.date, status, started, ended, title = s.title, targetType = target,
            distanceM = s.distanceM, arrowCountMode = s.arrowCountMode, isIndoor = s.isIndoor,
            scoringMode = s.scoringMode, ends = ends, createdAt = created, updatedAt = updated)
    private fun rejected(old: Session, next: Session) {
        assertFailsWith<IllegalArgumentException> { validateSessionUpdate(old, next) }
    }

    @Test fun `metadata and additions follow existing domain operations`() {
        val old = draft()
        val next = old.copy(title = "Renamed").start(now.plusSeconds(1)).withEnd(end())
        validateSessionUpdate(old, next)
        assertEquals(now.plusSeconds(1), next.updatedAt)
    }
    @Test fun `multiple forward lifecycle operations can be saved together`() {
        val old = draft()
        validateSessionUpdate(old, old.start(now.plusSeconds(1)).complete(now.plusSeconds(2)))
    }
    @Test fun `identity creation and target definition cannot change`() {
        val old = draft()
        rejected(old, rebuild(old, uuid = "other"))
        rejected(old, rebuild(old, created = now.minusSeconds(1)))
        rejected(old, rebuild(old, target = target.copy(name = "Changed under same UUID")))
    }
    @Test fun `started session cannot return to draft or change its recorded start`() {
        val old = draft().start(now)
        rejected(old, rebuild(old, status = SessionStatus.DRAFT, started = null))
        rejected(old, rebuild(old, started = now.plusSeconds(1), updated = now.plusSeconds(1)))
    }
    @Test fun `new lifecycle events cannot predate the previous revision`() {
        val old = draft().copy(updatedAt = now.plusSeconds(10))
        rejected(old, rebuild(old, status = SessionStatus.IN_PROGRESS, started = now.plusSeconds(5)))
        val running = old.start(now.plusSeconds(10)).copy(updatedAt = now.plusSeconds(20))
        rejected(running, rebuild(running, status = SessionStatus.COMPLETED, ended = now.plusSeconds(15)))
    }
    @Test fun `untraced ends and impacts cannot be removed`() {
        val old = populated()
        rejected(old, rebuild(old, ends = emptyList()))
        rejected(old, rebuild(old, ends = listOf(end().copy(impacts = emptyList()))))
    }
    @Test fun `impact cannot move between existing or newly created ends`() {
        val old = populated()
        val moved = end().copy(uuid = "other-end", index = 2)
        rejected(old, rebuild(old, ends = listOf(end().copy(impacts = emptyList()), moved)))
    }
    @Test fun `reconstructed coordinate change needs explicit evidence`() {
        val old = populated()
        val moved = ArrowImpact("impact", 1, NormalizedPoint(0.25f, 0f), inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = target)
        rejected(old, rebuild(old, ends = listOf(end(moved))))
    }
    @Test fun `manual decisions cannot disappear through reconstruction`() {
        val judged = impact().withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Observed", now), now)
        val old = draft().start(now).withEnd(end(judged))
        rejected(old, rebuild(old, ends = listOf(end(impact()))))
    }
    @Test fun `reposition evidence must keep its exact prefix`() {
        val moved = impact().reposition(NormalizedPoint(0.25f, 0f), now.plusSeconds(1))
        val old = draft().start(now).withEnd(end(moved))
        val untraced = ArrowImpact("impact", 1, moved.point, inputMode = moved.inputMode,
            createdAt = now, updatedAt = moved.updatedAt, targetType = target)
        rejected(old, rebuild(old, ends = listOf(end(untraced))))
    }
    @Test fun `manual correction then multiple explicit moves remain valid`() {
        val old = populated()
        val revised = impact().withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Observed", now), now)
            .reposition(NormalizedPoint(0.25f, 0f), now.plusSeconds(1), "Position correction")
            .reposition(NormalizedPoint(0f, 0f), now.plusSeconds(2))
        validateSessionUpdate(old, old.withEnd(end(revised)))
    }
    @Test fun `validated end cannot reopen or change impacts`() {
        val validated = end().validate(now)
        val old = draft().start(now).withEnd(validated)
        rejected(old, rebuild(old, ends = listOf(end())))
        val edited = impact().copy(note = "Changed")
        rejected(old, rebuild(old, ends = listOf(End("end", 1, EndStatus.VALIDATED,
            listOf(edited), createdAt = now, updatedAt = now, validatedAt = now))))
        validateSessionUpdate(old, old.withEnd(validated.copy(notes = "Allowed parent metadata")))
    }
    @Test fun `completed session keeps closure and descendants but allows own metadata`() {
        val old = populated().complete(now)
        rejected(old, rebuild(old, status = SessionStatus.IN_PROGRESS, ended = null))
        rejected(old, rebuild(old, ends = listOf(end().copy(notes = "Changed"))))
        rejected(old, rebuild(old, started = now.plusSeconds(1), ended = now.plusSeconds(2), updated = now.plusSeconds(2)))
        validateSessionUpdate(old, old.copy(title = "Completed notes", updatedAt = now.plusSeconds(1)))
    }
    @Test fun `old child revisions and changed origins are rejected`() {
        val revised = impact().copy(note = "New", updatedAt = now.plusSeconds(2))
        val old = draft().start(now).withEnd(end(revised))
        rejected(old, rebuild(old, ends = listOf(end())))
        val otherOrigin = ArrowImpact("impact", 1, revised.point, inputMode = InputMode.AUTOMATIC,
            createdAt = now, updatedAt = revised.updatedAt, targetType = target)
        rejected(old, rebuild(old, ends = listOf(end(otherOrigin))))
    }
}
