package com.lmarchery.core.scoring

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionScoreCalculatorTest {
    private val target = ClassicTargetFixtures.classic40

    @Test
    fun `end score is the sum of final arrow scores`() {
        assertEquals(24, calculateEndScore(listOf(10, 8, 6)))
    }

    @Test
    fun `session score is the sum of end scores`() {
        assertEquals(72, calculateSessionScore(listOf(24, 23, 25)))
    }

    @Test
    fun `max possible score for fixed arrow count uses planned end count when present`() {
        val maxPossible = calculateMaxPossibleScore(
            targetType = target,
            arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 3,
            plannedEndCount = 20,
            recordedEndCount = 4,
            totalImpactCount = 12,
        )

        assertEquals(600, maxPossible)
    }

    @Test
    fun `max possible score for fixed arrow count uses recorded ends without planned count`() {
        val maxPossible = calculateMaxPossibleScore(
            targetType = target,
            arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 6,
            plannedEndCount = null,
            recordedEndCount = 4,
            totalImpactCount = 21,
        )

        assertEquals(240, maxPossible)
    }

    @Test
    fun `max possible score for free arrow count uses recorded impacts`() {
        val maxPossible = calculateMaxPossibleScore(
            targetType = target,
            arrowCountMode = ArrowCountMode.FREE,
            arrowCountPerEnd = null,
            plannedEndCount = null,
            recordedEndCount = 4,
            totalImpactCount = 11,
        )

        assertEquals(110, maxPossible)
    }
}
