package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Contract regressions first reproduced on the legacy entry points before the API migration. */
@Suppress("DEPRECATION")
class FinalScoreIntegrityRegressionTest {
    private val target = ClassicTargetFixtures.classic40
    private val now = LocalDateTime.parse("2026-10-15T10:00:00")

    @Test
    fun `competition keeps geometric nine without an explicit decision`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))
        assertEquals(9, result.score)
        for (mode in ScoringMode.entries) {
            assertEquals(9, resolveFinalScore(target, NormalizedPoint(0.105f, 0f), mode).scoreFinal)
        }
    }

    @Test
    fun `legacy manual parameter cannot admit scores absent from a classic target`() {
        for (score in listOf(-1, 11, 99)) {
            assertFailsWith<IllegalArgumentException> {
                scoreArrowImpact("impact", 1, target, NormalizedPoint(0.105f, 0f),
                    ScoringMode.MANUAL_CORDON, userSelectedScore = score, createdAt = now)
            }
        }
    }

    @Test
    fun `legacy mutation without target or structured decision cannot set ninety nine`() {
        val impact = geometricImpact()
        assertFailsWith<IllegalArgumentException> {
            impact.withFinalScore(99, true, now.plusMinutes(1))
        }
    }

    @Test
    fun `copy cannot make a zero score neither miss nor compatible with X`() {
        val impact = geometricImpact()
        assertFailsWith<IllegalArgumentException> {
            impact.copy(scoreFinal = 0, isMiss = false, isX = true, updatedAt = now.plusMinutes(1))
        }
    }

    @Test
    fun `constructor cannot invent a final score without structured provenance`() {
        assertFailsWith<IllegalArgumentException> {
            ArrowImpact(
                uuid = "impact", index = 1, point = NormalizedPoint(0f, 0f),
                distanceFromCenter = 0f, scoreCalculated = 10, scoreFinal = 99,
                isX = false, isMiss = false, inputMode = InputMode.MANUAL,
                isCordDecisionManual = true, createdAt = now, updatedAt = now,
            )
        }
    }

    @Test
    fun `a free copy cannot forge manual provenance with a boolean`() {
        val impact = geometricImpact()
        assertFailsWith<IllegalArgumentException> {
            impact.copy(isCordDecisionManual = true, updatedAt = now.plusMinutes(1))
        }
    }

    private fun geometricImpact(): ArrowImpact = scoreArrowImpact(
        uuid = "impact", index = 1, targetType = target, point = NormalizedPoint(0f, 0f),
        scoringMode = ScoringMode.TRAINING, createdAt = now,
    )
}
