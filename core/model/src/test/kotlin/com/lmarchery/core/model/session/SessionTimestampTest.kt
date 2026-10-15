package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.model.time.SessionDateTime
import java.time.ZoneId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionTimestampTest {
    private val ten = Instant.parse("2026-10-14T10:00:00Z")
    private val fivePast = ten.plusSeconds(300)
    private val tenPast = ten.plusSeconds(600)
    private val fifteenPast = ten.plusSeconds(900)
    private val target = TargetType(
        uuid = "11111111-1111-4111-8111-111111111111",
        name = "Timestamp test target",
        discipline = "test",
        diameterCm = 40,
        maxScore = 10,
        minScore = 1,
        defaultArrowCount = 3,
        defaultDistanceM = 18,
        isBuiltIn = true,
        xRingEnabled = false,
        xRingLabel = "",
        innerTenRadiusNormalized = 0f,
        zones = listOf(TargetZone(10, 0f, 0.1f, "yellow"), TargetZone(9, 0.1f, 1f, "yellow")),
    )

    @Test
    fun `final score rejects a timestamp before the previous modification`() {
        val original = impact(updatedAt = tenPast)

        assertFailsWith<IllegalArgumentException> {
            original.withFinalScore(target, correction(fivePast), fivePast)
        }
        assertEquals(tenPast, original.updatedAt)
    }

    @Test
    fun `final score preserves creation time and accepts equal or newer times`() {
        val original = impact(updatedAt = tenPast)

        for (time in listOf(tenPast, fifteenPast)) {
            val revised = original.withFinalScore(target, correction(time), time)
            assertEquals(ten, revised.createdAt)
            assertEquals(time, revised.updatedAt)
            assertEquals(9, revised.scoreFinal)
        }
    }

    @Test
    fun `impact constructor and copy reject a modification before creation`() {
        assertFailsWith<IllegalArgumentException> { impact(updatedAt = ten.minusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> {
            impact().copy(updatedAt = ten.minusSeconds(1))
        }
    }

    @Test
    fun `validation at 10h05 rejects an end last modified at 10h10`() {
        val original = end(updatedAt = tenPast)

        assertFailsWith<IllegalArgumentException> { original.validate(fivePast) }
        assertEquals(EndStatus.EDITING, original.status)
        assertEquals(tenPast, original.updatedAt)
    }

    @Test
    fun `validation accepts equal or newer timestamps and preserves creation`() {
        val original = end(updatedAt = tenPast)

        for (time in listOf(tenPast, fifteenPast)) {
            val validated = original.validate(time)
            assertEquals(EndStatus.VALIDATED, validated.status)
            assertEquals(ten, validated.createdAt)
            assertEquals(time, validated.updatedAt)
            assertEquals(time, validated.validatedAt)
        }
    }

    @Test
    fun `start rejects a timestamp before the previous draft modification`() {
        val draft = session(updatedAt = tenPast)

        assertFailsWith<IllegalArgumentException> { draft.start(fivePast) }
    }

    @Test
    fun `complete rejects a timestamp before the previous session modification`() {
        val running = session(updatedAt = tenPast, startedAt = ten)

        assertFailsWith<IllegalArgumentException> { running.complete(fivePast) }
    }

    @Test
    fun `normal session transitions accept equal or newer times and preserve creation`() {
        for (time in listOf(tenPast, fifteenPast)) {
            val started = session(updatedAt = tenPast).start(time)
            val completed = started.complete(time)
            assertEquals(ten, started.createdAt)
            assertEquals(ten, completed.createdAt)
            assertEquals(time, completed.updatedAt)
            assertEquals(time, completed.startedAt)
            assertEquals(time, completed.endedAt)
        }
    }

    @Test
    fun `older new impact preserves the newer parent modification`() {
        val original = end(updatedAt = fifteenPast, impacts = listOf(impact(updatedAt = tenPast)))
        val older = impact(uuid = "older-impact", index = 2, updatedAt = fivePast)

        val revised = original.withImpact(older)

        assertEquals(fifteenPast, revised.updatedAt)
        assertEquals(ten, revised.createdAt)
        assertEquals(fivePast, revised.impacts[1].updatedAt)
    }

    @Test
    fun `older new end preserves the newer session modification`() {
        val original = session(updatedAt = fifteenPast, ends = listOf(end(updatedAt = tenPast)))
        val older = end(uuid = "older-end", index = 2, updatedAt = fivePast)

        val revised = original.withEnd(older)

        assertEquals(fifteenPast, revised.updatedAt)
        assertEquals(ten, revised.createdAt)
        assertEquals(fivePast, revised.ends[1].updatedAt)
    }

    @Test
    fun `impact revision propagates through its end into its session`() {
        val originalImpact = impact()
        val originalEnd = end(impacts = listOf(originalImpact))
        val originalSession = session(ends = listOf(originalEnd), startedAt = ten)

        val revisedImpact = originalImpact.withFinalScore(target, correction(tenPast), tenPast)
        val revisedEnd = originalEnd.withImpact(revisedImpact)
        val revisedSession = originalSession.withEnd(revisedEnd)

        assertEquals(tenPast, revisedImpact.updatedAt)
        assertEquals(tenPast, revisedEnd.updatedAt)
        assertEquals(tenPast, revisedSession.updatedAt)
        assertEquals(revisedImpact, revisedSession.ends.single().impacts.single())
        assertEquals(ten, revisedImpact.createdAt)
        assertEquals(ten, revisedEnd.createdAt)
        assertEquals(ten, revisedSession.createdAt)
        assertEquals(ten, originalSession.updatedAt)
    }

    @Test
    fun `explicit integration timestamp before the child revision is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            end().withImpact(impact(updatedAt = tenPast), updatedAt = fivePast)
        }
        assertFailsWith<IllegalArgumentException> {
            session().withEnd(end(updatedAt = tenPast), updatedAt = fivePast)
        }
    }

    @Test
    fun `integration timestamp can advance the parent beyond the child revision`() {
        val revisedEnd = end().withImpact(impact(updatedAt = fivePast), updatedAt = tenPast)
        val revisedSession = session().withEnd(revisedEnd, updatedAt = fifteenPast)

        assertEquals(tenPast, revisedEnd.updatedAt)
        assertEquals(fifteenPast, revisedSession.updatedAt)
    }

    @Test
    fun `stale impact replacement is rejected instead of being hidden by parent time`() {
        val original = end(updatedAt = fifteenPast, impacts = listOf(impact(updatedAt = tenPast)))

        assertFailsWith<IllegalArgumentException> {
            original.withImpact(impact(updatedAt = fivePast), updatedAt = fifteenPast)
        }
    }

    @Test
    fun `stale end replacement is rejected instead of being hidden by session time`() {
        val original = session(updatedAt = fifteenPast, ends = listOf(end(updatedAt = tenPast)))

        assertFailsWith<IllegalArgumentException> {
            original.withEnd(end(updatedAt = fivePast), updatedAt = fifteenPast)
        }
    }

    @Test
    fun `replacing an end cannot roll back an existing impact revision`() {
        val originalEnd = end(updatedAt = tenPast, impacts = listOf(impact(updatedAt = tenPast)))
        val original = session(updatedAt = tenPast, ends = listOf(originalEnd))
        val inconsistentRevision = end(updatedAt = fifteenPast, impacts = listOf(impact(updatedAt = fivePast)))

        assertFailsWith<IllegalArgumentException> { original.withEnd(inconsistentRevision) }
    }

    @Test
    fun `replacement preserves creation times for existing child identities`() {
        val originalImpact = impact()
        val originalEnd = end(impacts = listOf(originalImpact))
        val changedImpact = impact(createdAt = fivePast, updatedAt = tenPast)
        assertFailsWith<IllegalArgumentException> { originalEnd.withImpact(changedImpact) }

        val originalSession = session(ends = listOf(originalEnd))
        assertFailsWith<IllegalArgumentException> {
            originalSession.withEnd(end(createdAt = fivePast, updatedAt = tenPast))
        }
        assertFailsWith<IllegalArgumentException> {
            originalSession.withEnd(end(updatedAt = tenPast, impacts = listOf(changedImpact)))
        }
    }

    @Test
    fun `end constructor and copy reject impacts newer than the parent`() {
        val newer = impact(updatedAt = tenPast)
        assertFailsWith<IllegalArgumentException> { end(updatedAt = fivePast, impacts = listOf(newer)) }
        assertFailsWith<IllegalArgumentException> { end().copy(impacts = listOf(newer)) }
    }

    @Test
    fun `session constructor and copy reject ends newer than the parent`() {
        val newer = end(updatedAt = tenPast)
        assertFailsWith<IllegalArgumentException> { session(updatedAt = fivePast, ends = listOf(newer)) }
        assertFailsWith<IllegalArgumentException> { session().copy(ends = listOf(newer)) }
    }

    @Test
    fun `children cannot have been created before their parent`() {
        val olderImpact = impact(createdAt = ten.minusSeconds(60), updatedAt = ten)
        assertFailsWith<IllegalArgumentException> { end(impacts = listOf(olderImpact)) }
        assertFailsWith<IllegalArgumentException> { end().copy(impacts = listOf(olderImpact)) }
        assertFailsWith<IllegalArgumentException> { end().withImpact(olderImpact) }

        val olderEnd = end(createdAt = ten.minusSeconds(60), updatedAt = ten)
        assertFailsWith<IllegalArgumentException> { session(ends = listOf(olderEnd)) }
        assertFailsWith<IllegalArgumentException> { session().copy(ends = listOf(olderEnd)) }
        assertFailsWith<IllegalArgumentException> { session().withEnd(olderEnd) }
    }

    @Test
    fun `validation cannot precede the last impact modification in constructed snapshots`() {
        val newer = impact(updatedAt = tenPast)
        assertFailsWith<IllegalArgumentException> {
            end(updatedAt = fifteenPast, validatedAt = fivePast, impacts = listOf(newer))
        }
        assertFailsWith<IllegalArgumentException> {
            end(updatedAt = fifteenPast, impacts = listOf(newer))
                .copy(status = EndStatus.VALIDATED, validatedAt = fivePast)
        }
    }

    @Test
    fun `integration cannot backdate validation of an existing editing end`() {
        val original = session(updatedAt = tenPast, ends = listOf(end(updatedAt = tenPast)))
        val revision = end(updatedAt = fifteenPast, validatedAt = fivePast)

        assertFailsWith<IllegalArgumentException> { original.withEnd(revision) }
    }

    @Test
    fun `constructed session cannot end before the last child modification`() {
        val newer = end(updatedAt = tenPast)
        assertFailsWith<IllegalArgumentException> {
            session(updatedAt = fifteenPast, startedAt = ten, endedAt = fivePast, ends = listOf(newer))
        }
        assertFailsWith<IllegalArgumentException> {
            session(updatedAt = fifteenPast, startedAt = ten, ends = listOf(newer))
                .copy(status = SessionStatus.COMPLETED, endedAt = fivePast)
        }
    }

    @Test
    fun `event timestamps cannot be newer than updatedAt in constructors or copy`() {
        assertFailsWith<IllegalArgumentException> { end(updatedAt = fivePast, validatedAt = tenPast) }
        assertFailsWith<IllegalArgumentException> {
            end().copy(status = EndStatus.VALIDATED, validatedAt = tenPast)
        }
        assertFailsWith<IllegalArgumentException> { session(startedAt = fivePast) }
        assertFailsWith<IllegalArgumentException> {
            session().copy(status = SessionStatus.IN_PROGRESS, startedAt = fivePast)
        }
        assertFailsWith<IllegalArgumentException> {
            session(updatedAt = fivePast, startedAt = ten, endedAt = tenPast)
        }
        assertFailsWith<IllegalArgumentException> {
            session(startedAt = ten).copy(status = SessionStatus.COMPLETED, endedAt = tenPast)
        }
    }

    @Test
    fun `constructor and copy reject updatedAt before creation for both aggregates`() {
        val beforeCreation = ten.minusSeconds(1)
        assertFailsWith<IllegalArgumentException> { end(updatedAt = beforeCreation) }
        assertFailsWith<IllegalArgumentException> { end().copy(updatedAt = beforeCreation) }
        assertFailsWith<IllegalArgumentException> { session(updatedAt = beforeCreation) }
        assertFailsWith<IllegalArgumentException> { session().copy(updatedAt = beforeCreation) }
    }

    @Test
    fun `coherent snapshots may record later metadata updates after validation or completion`() {
        val validated = end(updatedAt = fifteenPast, validatedAt = tenPast, impacts = listOf(impact(updatedAt = fivePast)))
        val completed = session(updatedAt = fifteenPast, startedAt = ten, endedAt = tenPast, ends = listOf(end(updatedAt = fivePast)))

        assertEquals(tenPast, validated.validatedAt)
        assertEquals(fifteenPast, validated.updatedAt)
        assertEquals(tenPast, completed.endedAt)
        assertEquals(fifteenPast, completed.updatedAt)
    }

    @Test
    fun `copy cannot backdate updatedAt even if the new snapshot would be coherent`() {
        assertFailsWith<IllegalArgumentException> { impact(updatedAt = tenPast).copy(updatedAt = fivePast) }
        assertFailsWith<IllegalArgumentException> { end(updatedAt = tenPast).copy(updatedAt = fivePast) }
        assertFailsWith<IllegalArgumentException> { session(updatedAt = tenPast).copy(updatedAt = fivePast) }
    }

    @Test
    fun `copy cannot change creation time of an existing value`() {
        assertFailsWith<IllegalArgumentException> { impact(updatedAt = tenPast).copy(createdAt = fivePast) }
        assertFailsWith<IllegalArgumentException> { end(updatedAt = tenPast).copy(createdAt = fivePast) }
        assertFailsWith<IllegalArgumentException> { session(updatedAt = tenPast).copy(createdAt = fivePast) }
    }

    @Test
    fun `copy cannot introduce a stale descendant beneath a newer aggregate timestamp`() {
        val originalEnd = end(updatedAt = tenPast, impacts = listOf(impact(updatedAt = tenPast)))
        val stale = impact(updatedAt = fivePast)
        assertFailsWith<IllegalArgumentException> {
            originalEnd.copy(updatedAt = fifteenPast, impacts = listOf(stale))
        }
        val originalSession = session(updatedAt = tenPast, ends = listOf(originalEnd))
        assertFailsWith<IllegalArgumentException> {
            originalSession.copy(updatedAt = fifteenPast, ends = listOf(end(updatedAt = fifteenPast, impacts = listOf(stale))))
        }
    }

    @Test
    fun `copy cannot backdate a transition while keeping a newer updatedAt`() {
        assertFailsWith<IllegalArgumentException> {
            end(updatedAt = tenPast).copy(status = EndStatus.VALIDATED, validatedAt = fivePast, updatedAt = fifteenPast)
        }
        assertFailsWith<IllegalArgumentException> {
            session(updatedAt = tenPast).copy(status = SessionStatus.IN_PROGRESS, startedAt = fivePast, updatedAt = fifteenPast)
        }
        assertFailsWith<IllegalArgumentException> {
            session(updatedAt = tenPast, startedAt = ten)
                .copy(status = SessionStatus.COMPLETED, endedAt = fivePast, updatedAt = fifteenPast)
        }
    }

    @Test
    fun `copies retain value equality hash codes and destructuring`() {
        val originalImpact = impact()
        val originalEnd = end(impacts = listOf(originalImpact))
        val originalSession = session(ends = listOf(originalEnd))
        assertEquals(originalImpact, originalImpact.copy())
        assertEquals(originalEnd, originalEnd.copy())
        assertEquals(originalSession, originalSession.copy())
        assertEquals(originalImpact.hashCode(), originalImpact.copy().hashCode())
        assertEquals(originalEnd.hashCode(), originalEnd.copy().hashCode())
        assertEquals(originalSession.hashCode(), originalSession.copy().hashCode())
        val (impactUuid, impactIndex) = originalImpact
        val (endUuid, endIndex) = originalEnd
        val (sessionUuid, date) = originalSession
        assertEquals("impact-1", impactUuid)
        assertEquals(1, impactIndex)
        assertEquals("end-1", endUuid)
        assertEquals(1, endIndex)
        assertEquals("session-1", sessionUuid)
        assertEquals(SessionDateTime.fromInstant(ten, ZoneId.of("UTC")), date)
        assertTrue(originalImpact.toString().startsWith("ArrowImpact("))
        assertTrue(originalEnd.toString().startsWith("End("))
        assertTrue(originalSession.toString().startsWith("Session("))
    }

    @Test
    fun `caller owned lists cannot bypass aggregate chronology after construction`() {
        val callerImpacts = mutableListOf(impact())
        val originalEnd = end(impacts = callerImpacts)
        callerImpacts.add(impact(uuid = "future-impact", index = 2, updatedAt = fifteenPast))
        assertEquals(1, originalEnd.impacts.size)
        assertFailsWith<UnsupportedOperationException> {
            (originalEnd.impacts as MutableList).add(impact(uuid = "other-impact", index = 2))
        }

        val callerEnds = mutableListOf(originalEnd)
        val originalSession = session(ends = callerEnds)
        callerEnds.add(end(uuid = "future-end", index = 2, updatedAt = fifteenPast))
        assertEquals(1, originalSession.ends.size)
        assertFailsWith<UnsupportedOperationException> {
            (originalSession.ends as MutableList).add(end(uuid = "other-end", index = 2))
        }
    }

    private fun impact(
        uuid: String = "impact-1",
        index: Int = 1,
        createdAt: Instant = ten,
        updatedAt: Instant = ten,
    ) = ArrowImpact(uuid, index, NormalizedPoint(0f, 0f), 0f, 10, 10, false, false, InputMode.MANUAL, false,
        createdAt = createdAt, updatedAt = updatedAt, targetType = target)

    private fun correction(time: Instant) =
        ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Timestamp regression fixture", time)

    private fun end(
        uuid: String = "end-1",
        index: Int = 1,
        createdAt: Instant = ten,
        updatedAt: Instant = ten,
        validatedAt: Instant? = null,
        impacts: List<ArrowImpact> = emptyList(),
    ) = End(uuid, index, if (validatedAt == null) EndStatus.EDITING else EndStatus.VALIDATED,
        impacts, createdAt = createdAt, updatedAt = updatedAt, validatedAt = validatedAt)

    private fun session(
        createdAt: Instant = ten,
        updatedAt: Instant = ten,
        startedAt: Instant? = null,
        endedAt: Instant? = null,
        ends: List<End> = emptyList(),
    ) = Session(
        uuid = "session-1", date = SessionDateTime.fromInstant(ten, ZoneId.of("UTC")),
        status = when {
            endedAt != null -> SessionStatus.COMPLETED
            startedAt != null -> SessionStatus.IN_PROGRESS
            else -> SessionStatus.DRAFT
        },
        startedAt = startedAt, endedAt = endedAt, title = "Timestamp tests", targetType = target,
        distanceM = 18, arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3,
        isIndoor = true, scoringMode = ScoringMode.TRAINING,
        ends = ends, createdAt = createdAt, updatedAt = updatedAt,
    )
}
