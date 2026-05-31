package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreCalculatorTest {
    private val target = ClassicTargetFixtures.classic40

    @Test
    fun `center scores 10 and X`() {
        val result = calculateScore(target, NormalizedPoint(0f, 0f))

        assertEquals(10, result.score)
        assertEquals(0f, result.distanceFromCenter, absoluteTolerance = 0.0001f)
        assertTrue(result.isX)
        assertFalse(result.isMiss)
        assertFalse(result.isNearCordon)
    }

    @Test
    fun `classic target scores each concentric zone`() {
        val cases = listOf(
            0.05f to 10,
            0.15f to 9,
            0.25f to 8,
            0.35f to 7,
            0.45f to 6,
            0.55f to 5,
            0.65f to 4,
            0.75f to 3,
            0.85f to 2,
            0.95f to 1,
        )

        for ((radius, expectedScore) in cases) {
            val result = calculateScore(target, NormalizedPoint(radius, 0f))

            assertEquals(expectedScore, result.score, "Unexpected score at radius $radius")
            assertEquals(expectedScore, assertNotNull(result.zone).score)
            assertFalse(result.isMiss)
        }
    }

    @Test
    fun `exact boundaries belong to the inner higher scoring zone`() {
        val cases = listOf(
            0.10f to 10,
            0.20f to 9,
            0.30f to 8,
            0.40f to 7,
            0.50f to 6,
            0.60f to 5,
            0.70f to 4,
            0.80f to 3,
            0.90f to 2,
            1.00f to 1,
        )

        for ((radius, expectedScore) in cases) {
            val result = calculateScore(target, NormalizedPoint(radius, 0f))

            assertEquals(expectedScore, result.score, "Unexpected boundary score at radius $radius")
            assertFalse(result.isMiss)
        }
    }

    @Test
    fun `miss scores zero outside target`() {
        val result = calculateScore(target, NormalizedPoint(1.0001f, 0f))

        assertEquals(0, result.score)
        assertNull(result.zone)
        assertTrue(result.isMiss)
        assertFalse(result.isX)
        assertFalse(result.isNearCordon)
    }

    @Test
    fun `X ring can be disabled`() {
        val result = calculateScore(
            target.copy(xRingEnabled = false, innerTenRadiusNormalized = 0f),
            NormalizedPoint(0.01f, 0f),
        )

        assertEquals(10, result.score)
        assertFalse(result.isX)
    }

    @Test
    fun `X ring radius is configurable`() {
        val result = calculateScore(
            target.copy(innerTenRadiusNormalized = 0.03f),
            NormalizedPoint(0.04f, 0f),
        )

        assertEquals(10, result.score)
        assertFalse(result.isX)
    }

    @Test
    fun `impact near inner boundary exposes cordon zone above`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))

        assertEquals(9, result.score)
        assertTrue(result.isNearCordon)
        assertEquals(10, result.cordonZoneAbove)
    }

    @Test
    fun `impact away from boundary is not near cordon`() {
        val result = calculateScore(target, NormalizedPoint(0.15f, 0f))

        assertEquals(9, result.score)
        assertFalse(result.isNearCordon)
        assertNull(result.cordonZoneAbove)
    }

    @Test
    fun `training mode keeps calculated score`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))
        val decision = resolveFinalScore(result, ScoringMode.TRAINING)

        assertEquals(9, decision.scoreCalculated)
        assertEquals(9, decision.scoreFinal)
        assertFalse(decision.isCordDecisionManual)
    }

    @Test
    fun `competition mode uses upper score on cordon`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))
        val decision = resolveFinalScore(result, ScoringMode.COMPETITION)

        assertEquals(9, decision.scoreCalculated)
        assertEquals(10, decision.scoreFinal)
        assertFalse(decision.isCordDecisionManual)
    }

    @Test
    fun `manual cordon mode uses selected score and records manual decision`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))
        val decision = resolveFinalScore(
            result = result,
            scoringMode = ScoringMode.MANUAL_CORDON,
            userSelectedScore = 10,
        )

        assertEquals(9, decision.scoreCalculated)
        assertEquals(10, decision.scoreFinal)
        assertTrue(decision.isCordDecisionManual)
    }

    @Test
    fun `manual cordon mode defaults to calculated score without user selection`() {
        val result = calculateScore(target, NormalizedPoint(0.105f, 0f))
        val decision = resolveFinalScore(result, ScoringMode.MANUAL_CORDON)

        assertEquals(9, decision.scoreCalculated)
        assertEquals(9, decision.scoreFinal)
        assertFalse(decision.isCordDecisionManual)
    }
}
