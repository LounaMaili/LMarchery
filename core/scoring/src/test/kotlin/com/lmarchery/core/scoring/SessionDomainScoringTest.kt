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
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDomainScoringTest {
    private val target = ClassicTargetFixtures.classic40
    private val now: LocalDateTime = LocalDateTime.parse("2026-05-30T10:00:00")

    @Test
    fun `end score uses final scores and not calculated scores`() {
        val end = end(
            uuid = "end-1",
            impacts = listOf(
                impact(uuid = "impact-1", index = 1, scoreCalculated = 9, scoreFinal = 10),
                impact(uuid = "impact-2", index = 2, scoreCalculated = 8, scoreFinal = 8),
            ),
        )

        assertEquals(18, calculateEndScore(end))
    }

    @Test
    fun `session score uses validated ends only`() {
        val session = session(
            ends = listOf(
                end(
                    uuid = "validated-end",
                    index = 1,
                    status = EndStatus.VALIDATED,
                    impacts = listOf(impact(uuid = "impact-1", index = 1, scoreFinal = 10)),
                ),
                end(
                    uuid = "editing-end",
                    index = 2,
                    status = EndStatus.EDITING,
                    impacts = listOf(impact(uuid = "impact-2", index = 1, scoreFinal = 9)),
                ),
            ),
        )

        assertEquals(10, calculateSessionScore(session))
    }

    @Test
    fun `session max possible uses planned end count when fixed and present`() {
        val session = session(plannedEndCount = 20, arrowCountPerEnd = 3)

        assertEquals(600, calculateMaxPossibleScore(session))
    }

    @Test
    fun `session max possible uses recorded ends when fixed without planned count`() {
        val session = session(
            plannedEndCount = null,
            arrowCountPerEnd = 3,
            ends = listOf(
                end(uuid = "end-1", index = 1),
                end(uuid = "end-2", index = 2, status = EndStatus.EDITING),
            ),
        )

        assertEquals(60, calculateMaxPossibleScore(session))
    }

    @Test
    fun `session max possible uses recorded impacts in free mode`() {
        val session = session(
            arrowCountMode = ArrowCountMode.FREE,
            arrowCountPerEnd = null,
            ends = listOf(
                end(
                    uuid = "end-1",
                    index = 1,
                    impacts = listOf(
                        impact(uuid = "impact-1", index = 1),
                        impact(uuid = "impact-2", index = 2),
                    ),
                ),
                end(
                    uuid = "end-2",
                    index = 2,
                    status = EndStatus.EDITING,
                    impacts = listOf(impact(uuid = "impact-3", index = 1)),
                ),
            ),
        )

        assertEquals(30, calculateMaxPossibleScore(session))
    }

    @Test
    fun `summary counts validated max score X and misses`() {
        val session = session(
            plannedEndCount = 1,
            arrowCountPerEnd = 3,
            ends = listOf(
                end(
                    uuid = "end-1",
                    index = 1,
                    impacts = listOf(
                        impact(uuid = "impact-1", index = 1, scoreFinal = 10, isX = true),
                        impact(uuid = "impact-2", index = 2, scoreFinal = 10, isX = false),
                        impact(uuid = "impact-3", index = 3, scoreFinal = 0, isMiss = true),
                    ),
                ),
                end(
                    uuid = "editing-end",
                    index = 2,
                    status = EndStatus.EDITING,
                    impacts = listOf(impact(uuid = "impact-4", index = 1, scoreFinal = 10, isX = true)),
                ),
            ),
        )

        val summary = summarizeSessionScore(session)

        assertEquals(20, summary.totalScore)
        assertEquals(30, summary.maxPossibleScore)
        assertEquals(66.666, summary.percentage ?: 0.0, absoluteTolerance = 0.001)
        assertEquals(20.0, summary.averagePerEnd)
        assertEquals(20.0 / 3.0, summary.averagePerArrow ?: 0.0, absoluteTolerance = 0.001)
        assertEquals(1, summary.validatedEndCount)
        assertEquals(2, summary.recordedEndCount)
        assertEquals(3, summary.validatedImpactCount)
        assertEquals(4, summary.recordedImpactCount)
        assertEquals(2, summary.maxScoreCount)
        assertEquals(1, summary.xCount)
        assertEquals(1, summary.missCount)
    }

    @Test
    fun `empty free session summary avoids division by zero`() {
        val session = session(
            arrowCountMode = ArrowCountMode.FREE,
            arrowCountPerEnd = null,
            ends = emptyList(),
        )

        val summary = summarizeSessionScore(session)

        assertEquals(0, summary.totalScore)
        assertEquals(0, summary.maxPossibleScore)
        assertNull(summary.percentage)
        assertNull(summary.averagePerEnd)
        assertNull(summary.averagePerArrow)
    }

    @Test
    fun `score arrow impact keeps point distance calculated score and final score consistent`() {
        val impact = scoreArrowImpact(
            uuid = "impact-1",
            index = 1,
            targetType = target,
            point = NormalizedPoint(0.105f, 0f),
            scoringMode = ScoringMode.MANUAL_CORDON,
            decision = ScoreDecisionRequest.JudgeBoundary(
                BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f), BoundaryValue(10), now),
            createdAt = now,
        )

        assertEquals(0.105f, impact.distanceFromCenter, absoluteTolerance = 0.0001f)
        assertEquals(9, impact.scoreCalculated)
        assertEquals(10, impact.scoreFinal)
        assertEquals(InputMode.MANUAL, impact.inputMode)
        assertTrue(impact.isCordDecisionManual)
        assertFalse(impact.isMiss)
    }

    private fun session(
        plannedEndCount: Int? = null,
        arrowCountMode: ArrowCountMode = ArrowCountMode.FIXED,
        arrowCountPerEnd: Int? = 3,
        ends: List<End> = emptyList(),
    ): Session = Session(
        uuid = "session-1",
        date = now,
        status = SessionStatus.IN_PROGRESS,
        startedAt = now,
        plannedEndCount = plannedEndCount,
        title = "Training",
        targetType = target,
        distanceM = 18,
        arrowCountMode = arrowCountMode,
        arrowCountPerEnd = arrowCountPerEnd,
        isIndoor = true,
        scoringMode = ScoringMode.TRAINING,
        ends = ends,
        createdAt = now,
        updatedAt = now,
    )

    private fun end(
        uuid: String,
        index: Int = 1,
        status: EndStatus = EndStatus.VALIDATED,
        impacts: List<ArrowImpact> = emptyList(),
    ): End = End(
        uuid = uuid,
        index = index,
        status = status,
        impacts = impacts,
        createdAt = now,
        updatedAt = now,
        validatedAt = if (status == EndStatus.VALIDATED) now else null,
    )

    private fun impact(
        uuid: String,
        index: Int,
        scoreCalculated: Int = 10,
        scoreFinal: Int = scoreCalculated,
        isX: Boolean = false,
        isMiss: Boolean = false,
    ): ArrowImpact {
        require(isMiss == (scoreFinal == 0))
        val radius = when (scoreCalculated) {
            0 -> 1.1f
            10 -> if (isX) 0.025f else 0.075f
            else -> target.zones.first { it.score == scoreCalculated }.let {
                (it.innerRadiusNormalized + it.outerRadiusNormalized) / 2f
            }
        }
        val point = NormalizedPoint(radius, 0f)
        val geometry = calculateScore(target, point)
        val selected = BoundaryValue(scoreFinal, isX)
        val request = if (selected == BoundaryValue(geometry.score, geometry.isX)) {
            ScoreDecisionRequest.AcceptGeometry
        } else ScoreDecisionRequest.ExceptionalCorrection(selected, "Aggregate test fixture correction", now)
        return scoreArrowImpact(uuid, index, target, point, ScoringMode.TRAINING,
            decision = request, createdAt = now)
    }
}
