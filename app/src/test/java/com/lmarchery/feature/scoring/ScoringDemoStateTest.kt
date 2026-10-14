package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoringDemoStateTest {
    private val now = LocalDateTime.parse("2026-05-30T10:00:00")

    @Test
    fun `adds at most three impacts with stable indexes`() {
        val fullState = ScoringDemoState()
            .addImpact(NormalizedPoint(0f, 0f), now, "impact-1")
            .addImpact(NormalizedPoint(0.15f, 0f), now, "impact-2")
            .addImpact(NormalizedPoint(1.1f, 0f), now, "impact-3")

        val afterFourthTap = fullState.addImpact(NormalizedPoint(0.25f, 0f), now, "impact-4")

        assertEquals(3, afterFourthTap.impacts.size)
        assertFalse(afterFourthTap.canAddImpact)
        assertEquals(listOf(1, 2, 3), afterFourthTap.impacts.map { it.index })
        assertEquals(listOf("impact-1", "impact-2", "impact-3"), afterFourthTap.impacts.map { it.uuid })
    }

    @Test
    fun `scores impacts through core scoring`() {
        val state = ScoringDemoState()
            .addImpact(NormalizedPoint(0f, 0f), now, "impact-1")
            .addImpact(NormalizedPoint(0.15f, 0f), now, "impact-2")
            .addImpact(NormalizedPoint(1.1f, 0f), now, "impact-3")

        val center = state.impacts[0]
        val zoneNine = state.impacts[1]
        val miss = state.impacts[2]

        assertEquals(10, center.scoreCalculated)
        assertEquals(10, center.scoreFinal)
        assertTrue(center.isX)

        assertEquals(9, zoneNine.scoreCalculated)
        assertEquals(9, zoneNine.scoreFinal)
        assertFalse(zoneNine.isX)

        assertEquals(0, miss.scoreCalculated)
        assertEquals(0, miss.scoreFinal)
        assertTrue(miss.isMiss)

        assertEquals(19, state.totalScore)
        assertEquals(30, state.maxPossibleScore)
    }

    @Test
    fun `reset clears in memory impacts`() {
        val state = ScoringDemoState()
            .addImpact(NormalizedPoint(0f, 0f), now, "impact-1")
            .reset()

        assertTrue(state.impacts.isEmpty())
        assertTrue(state.canAddImpact)
        assertEquals(0, state.totalScore)
    }
}
