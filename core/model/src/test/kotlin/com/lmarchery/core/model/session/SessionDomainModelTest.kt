package com.lmarchery.core.model.session

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.model.time.SessionDateTime
import java.time.ZoneId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionDomainModelTest {
    private val now: Instant = Instant.parse("2026-05-30T10:00:00Z")
    private val target = TargetType(
        uuid = "11111111-1111-4111-8111-111111111111",
        name = "Test target",
        discipline = "test",
        diameterCm = 40,
        maxScore = 10,
        minScore = 1,
        defaultArrowCount = 3,
        defaultDistanceM = 18,
        isBuiltIn = true,
        xRingEnabled = true,
        xRingLabel = "X",
        innerTenRadiusNormalized = 0.05f,
        zones = listOf(
            TargetZone(score = 10, innerRadiusNormalized = 0f, outerRadiusNormalized = 1f, colorName = "yellow"),
        ),
    )

    @Test
    fun `session transitions from draft to in progress to completed`() {
        val draft = session(status = SessionStatus.DRAFT, startedAt = null)

        assertNull(draft.startedAt)
        assertNull(draft.endedAt)

        val started = draft.start(now.plusSeconds(60))
        assertEquals(SessionStatus.IN_PROGRESS, started.status)
        assertEquals(now.plusSeconds(60), started.startedAt)
        assertEquals(now.plusSeconds(60), started.updatedAt)

        val completed = started.complete(now.plusSeconds(3600))
        assertEquals(SessionStatus.COMPLETED, completed.status)
        assertEquals(now.plusSeconds(3600), completed.endedAt)
        assertEquals(now.plusSeconds(3600), completed.updatedAt)
    }

    @Test
    fun `end validates from editing to validated`() {
        val editing = end(status = EndStatus.EDITING)

        assertNull(editing.validatedAt)

        val validated = editing.validate(now.plusSeconds(300))
        assertEquals(EndStatus.VALIDATED, validated.status)
        assertEquals(now.plusSeconds(300), validated.validatedAt)
        assertEquals(now.plusSeconds(300), validated.updatedAt)
    }

    @Test
    fun `fixed arrow count requires a positive arrow count per end`() {
        assertFailsWith<IllegalArgumentException> {
            session(arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = null)
        }

        assertFailsWith<IllegalArgumentException> {
            session(arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 0)
        }
    }

    @Test
    fun `free arrow count rejects arrow count per end`() {
        assertFailsWith<IllegalArgumentException> {
            session(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = 3)
        }
    }

    @Test
    fun `end rejects duplicate impact indexes`() {
        val first = impact(uuid = "impact-1", index = 1)
        val second = impact(uuid = "impact-2", index = 1)

        assertFailsWith<IllegalArgumentException> {
            end(impacts = listOf(first, second))
        }
    }

    @Test
    fun `with impact refreshes end updatedAt and keeps unique indexes`() {
        val editing = end(status = EndStatus.EDITING)
        val newImpact = impact(uuid = "impact-1", index = 1, updatedAt = now.plusSeconds(120))

        val updated = editing.withImpact(newImpact)

        assertEquals(listOf(newImpact), updated.impacts)
        assertEquals(now.plusSeconds(120), updated.updatedAt)
    }

    @Test
    fun `completed session cannot be modified`() {
        val completed = session(
            status = SessionStatus.COMPLETED,
            startedAt = now.plusSeconds(60),
            endedAt = now.plusSeconds(3600),
        )

        assertFailsWith<IllegalArgumentException> {
            completed.withEnd(end())
        }
    }

    @Test
    fun `impact constructors and copies reject non finite or negative distances`() {
        val original = impact(uuid = "impact-1", index = 1)
        for (distance in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertFailsWith<IllegalArgumentException> {
                impact(uuid = "impact-1", index = 1, distanceFromCenter = distance)
            }
            assertFailsWith<IllegalArgumentException> { original.copy(distanceFromCenter = distance) }
        }
    }

    private fun session(
        status: SessionStatus = SessionStatus.IN_PROGRESS,
        startedAt: Instant? = now.plusSeconds(60),
        endedAt: Instant? = null,
        arrowCountMode: ArrowCountMode = ArrowCountMode.FIXED,
        arrowCountPerEnd: Int? = 3,
    ): Session = Session(
        uuid = "session-1",
        date = SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
        status = status,
        startedAt = startedAt,
        endedAt = endedAt,
        plannedEndCount = null,
        title = "Training",
        targetType = target,
        distanceM = 18,
        arrowCountMode = arrowCountMode,
        arrowCountPerEnd = arrowCountPerEnd,
        isIndoor = true,
        scoringMode = ScoringMode.TRAINING,
        createdAt = now,
        updatedAt = listOfNotNull(now, startedAt, endedAt).max(),
    )

    private fun end(
        status: EndStatus = EndStatus.VALIDATED,
        impacts: List<ArrowImpact> = emptyList(),
    ): End = End(
        uuid = "end-1",
        index = 1,
        status = status,
        impacts = impacts,
        createdAt = now,
        updatedAt = now,
        validatedAt = if (status == EndStatus.VALIDATED) now else null,
    )

    private fun impact(
        uuid: String,
        index: Int,
        updatedAt: Instant = now,
        distanceFromCenter: Float = 0f,
    ): ArrowImpact = ArrowImpact(
        uuid = uuid,
        index = index,
        point = NormalizedPoint(0f, 0f),
        distanceFromCenter = distanceFromCenter,
        scoreCalculated = 10,
        scoreFinal = 10,
        isX = true,
        isMiss = false,
        inputMode = InputMode.MANUAL,
        isCordDecisionManual = false,
        createdAt = now,
        updatedAt = updatedAt,
        targetType = target,
    )
}
