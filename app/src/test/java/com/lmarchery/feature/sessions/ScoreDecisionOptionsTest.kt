package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlin.test.*

class ScoreDecisionOptionsTest {
    private fun zone(score: Int, inner: Float, outer: Float) = TargetZone(score, inner, outer, "Synthetic", "#FFFFFF")

    @Test fun `nonconsecutive scores and multiple nearby limits are read from the actual profile`() {
        // Narrow synthetic zones exercise multiple hints; current WA indoor profiles have wider zones.
        val target = ClassicTargetFixtures.classic40.copy(xRingEnabled = false,
            zones = listOf(zone(10, 0f, 0.1f), zone(8, 0.1f, 0.11f), zone(5, 0.11f, 1f)), minScore = 5)
        val options = scoreDecisionOptions(target, NormalizedPoint(0.105f, 0f))
        assertEquals(setOf(0.1f, 0.11f), options.nearby.map { it.radiusNormalized }.toSet())
        assertEquals(setOf(BoundaryValue(10) to BoundaryValue(8), BoundaryValue(8) to BoundaryValue(5)),
            options.nearby.map { it.inner to it.outer }.toSet())
        assertEquals(listOf(10, 8, 5, 0), options.legalValues.map { it.score })
        assertFalse(BoundaryValue(6) in options.legalValues)
        assertEquals(BoundaryValue(5), options.other.single().inner)
        assertEquals(BoundaryValue(0), options.other.single().outer)
    }

    @Test fun `generic X distinction and target edge remain exact profile values`() {
        val target = ClassicTargetFixtures.classic40
        val options = scoreDecisionOptions(target, NormalizedPoint(0.05f, 0f))
        val x = options.nearby.single { it.type == BoundaryType.X_RING }
        assertEquals(BoundaryValue(10, true), x.inner); assertEquals(BoundaryValue(10), x.outer)
        assertEquals(0.0, x.distanceToBoundaryNormalized)
        assertTrue(BoundaryValue(10, true) in options.legalValues)
        assertTrue(BoundaryValue(10) in options.legalValues)
        assertEquals(target.scoreBoundaries().map { it.reference }.toSet(),
            (options.nearby + options.other).map { it.reference }.toSet())
    }

    @Test fun `options preserve geometric and final results in every scoring mode`() {
        val target = IndoorProfile.RECURVE_40.target
        com.lmarchery.core.model.session.ScoringMode.entries.forEach { mode ->
            val point = NormalizedPoint(0.105f, 0f)
            val before = com.lmarchery.core.scoring.resolveFinalScore(target, point, mode)
            scoreDecisionOptions(target, point)
            assertEquals(before, com.lmarchery.core.scoring.resolveFinalScore(target, point, mode))
            assertEquals(9, before.scoreFinal)
        }
    }
}
