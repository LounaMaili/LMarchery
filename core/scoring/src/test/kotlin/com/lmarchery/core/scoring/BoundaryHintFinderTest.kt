package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundaryHintFinderTest {
    private val target = ClassicTargetFixtures.classic40

    @Test
    fun `all classic zone boundaries are detected from both sides on all four fixtures`() {
        for (fixture in ClassicTargetFixtures.all) {
            for ((inner, outer) in fixture.zones.zipWithNext()) {
                val radius = inner.outerRadiusNormalized
                for (pointRadius in listOf(radius - 0.005f, radius + 0.005f)) {
                    val hint = findBoundaryHints(fixture, NormalizedPoint(pointRadius, 0f)).single()
                    assertEquals(BoundaryType.SCORE_ZONES, hint.type)
                    assertEquals(radius, hint.radiusNormalized)
                    assertEquals(BoundaryValue(inner.score), hint.inner)
                    assertEquals(BoundaryValue(outer.score), hint.outer)
                    assertTrue(hint.distanceToBoundaryNormalized in 0.0..0.015f.toDouble())
                }
            }
        }
    }

    @Test
    fun `exact radii include every zone boundary X and the target edge`() {
        val expected = target.zones.dropLast(1).map { it.outerRadiusNormalized to BoundaryType.SCORE_ZONES } +
            listOf(0.05f to BoundaryType.X_RING, 1f to BoundaryType.TARGET_EDGE)
        for ((radius, type) in expected) {
            val hint = findBoundaryHints(target, NormalizedPoint(radius, 0f), 0f).single()
            assertEquals(type, hint.type)
            assertEquals(radius, hint.radiusNormalized)
            assertEquals(0.0, hint.distanceToBoundaryNormalized)
        }
    }

    @Test
    fun `X boundary carries the same numeric score with distinct X status on both sides`() {
        for (radius in listOf(0.04f, 0.06f)) {
            val hint = findBoundaryHints(target, NormalizedPoint(radius, 0f)).single()
            assertEquals(BoundaryType.X_RING, hint.type)
            assertEquals(0.05f, hint.radiusNormalized)
            assertEquals(BoundaryValue(10, isX = true), hint.inner)
            assertEquals(BoundaryValue(10), hint.outer)
            assertFalse(hint.inner.isMiss)
            assertFalse(hint.outer.isMiss)
        }
    }

    @Test
    fun `edge is detected inside and outside and identifies M explicitly`() {
        for (radius in listOf(0.99f, 1.01f)) {
            val hint = findBoundaryHints(target, NormalizedPoint(radius, 0f)).single()
            assertEquals(BoundaryType.TARGET_EDGE, hint.type)
            assertEquals(1f, hint.radiusNormalized)
            assertEquals(BoundaryValue(1), hint.inner)
            assertEquals(BoundaryValue(0), hint.outer)
            assertTrue(hint.outer.isMiss)
            assertFalse(hint.outer.isX)
        }
    }

    @Test
    fun `disabled X produces neither an X boundary nor X side values`() {
        val disabled = target.copy(xRingEnabled = false)
        assertTrue(findBoundaryHints(disabled, NormalizedPoint(0.05f, 0f)).isEmpty())
        val hints = findBoundaryHints(disabled, NormalizedPoint(0f, 0f), 1f)
        assertEquals(10, hints.size)
        assertTrue(hints.none { it.type == BoundaryType.X_RING || it.inner.isX || it.outer.isX })
    }

    @Test
    fun `zero tolerance excludes adjacent Float radii on either side`() {
        for (radius in target.zones.map { it.outerRadiusNormalized } + target.innerTenRadiusNormalized) {
            for (adjacent in listOf(Math.nextDown(radius), Math.nextUp(radius))) {
                assertTrue(findBoundaryHints(target, NormalizedPoint(adjacent, 0f), 0f).isEmpty())
            }
        }
        assertEquals(
            findBoundaryHints(target, NormalizedPoint(0.1f, 0f), 0f),
            findBoundaryHints(target, NormalizedPoint(0.1f, 0f), -0f),
        )
    }

    @Test
    fun `touch tolerance is inclusive at exactly representable endpoints`() {
        val binary = binaryTarget()
        for (radius in listOf(0.375f, 0.625f)) {
            val hint = findBoundaryHints(binary, NormalizedPoint(radius, 0f), 0.125f).single()
            assertEquals(0.5f, hint.radiusNormalized)
            assertEquals(0.125, hint.distanceToBoundaryNormalized)
        }
    }

    @Test
    fun `adjacent Floats immediately beyond tolerance are excluded on both sides`() {
        val binary = binaryTarget()
        for (radius in listOf(Math.nextDown(0.375f), Math.nextUp(0.625f))) {
            assertTrue(findBoundaryHints(binary, NormalizedPoint(radius, 0f), 0.125f).isEmpty())
        }
        for (radius in listOf(Math.nextUp(0.375f), Math.nextDown(0.625f))) {
            assertEquals(1, findBoundaryHints(binary, NormalizedPoint(radius, 0f), 0.125f).size)
        }
    }

    @Test
    fun `default touch tolerance remains 0_015`() {
        val point = NormalizedPoint(0.11f, 0f)
        assertEquals(findBoundaryHints(target, point, 0.015f), findBoundaryHints(target, point))
        assertEquals(1, findBoundaryHints(target, point).size)
        assertTrue(findBoundaryHints(target, NormalizedPoint(0.12f, 0f)).isEmpty())
    }

    @Test
    fun `multiple candidates are sorted by distance then radius without losing ties`() {
        val sparse = sparseTarget().copy(xRingEnabled = true, innerTenRadiusNormalized = 0.125f)
        val hints = findBoundaryHints(sparse, NormalizedPoint(0.5f, 0f), 0.5f)
        assertEquals(listOf(0.25f, 0.75f, 0.125f, 1f), hints.map { it.radiusNormalized })
        assertEquals(listOf(0.25, 0.25, 0.375, 0.5), hints.map { it.distanceToBoundaryNormalized })
        assertEquals(listOf(BoundaryType.SCORE_ZONES, BoundaryType.SCORE_ZONES,
            BoundaryType.X_RING, BoundaryType.TARGET_EDGE), hints.map { it.type })
    }

    @Test
    fun `input zone order does not affect suggestions or their order`() {
        val point = NormalizedPoint(0.5f, 0f)
        assertEquals(findBoundaryHints(target, point, 1f),
            findBoundaryHints(target.copy(zones = target.zones.reversed()), point, 1f))
    }

    @Test
    fun `non consecutive scores and a peripheral score above one come from actual zones`() {
        val sparse = sparseTarget()
        val expected = listOf(Triple(0.25f, 10, 6), Triple(0.75f, 6, 3), Triple(1f, 3, 0))
        for ((radius, innerScore, outerScore) in expected) {
            val hint = findBoundaryHints(sparse, NormalizedPoint(radius, 0f), 0f).single()
            assertEquals(BoundaryValue(innerScore), hint.inner)
            assertEquals(BoundaryValue(outerScore), hint.outer)
        }
    }

    @Test
    fun `X does not assume that the maximum score is ten`() {
        val custom = binaryTarget().copy(
            maxScore = 12,
            zones = listOf(TargetZone(12, 0f, 0.5f, "center"), TargetZone(1, 0.5f, 1f, "outer")),
            xRingEnabled = true,
            innerTenRadiusNormalized = 0.25f,
        )
        val hint = findBoundaryHints(custom, NormalizedPoint(0.25f, 0f), 0f).single()
        assertEquals(BoundaryType.X_RING, hint.type)
        assertEquals(BoundaryValue(12, isX = true), hint.inner)
        assertEquals(BoundaryValue(12), hint.outer)
    }

    @Test
    fun `coincident X and zone limits stay distinct and describe actual sides`() {
        val coincident = target.copy(innerTenRadiusNormalized = 0.1f)
        val hints = findBoundaryHints(coincident, NormalizedPoint(0.1f, 0f), 0f)
        assertEquals(listOf(BoundaryType.SCORE_ZONES, BoundaryType.X_RING), hints.map { it.type })
        for (hint in hints) {
            assertEquals(0.1f, hint.radiusNormalized)
            assertEquals(BoundaryValue(10, isX = true), hint.inner)
            assertEquals(BoundaryValue(9), hint.outer)
        }
    }

    @Test
    fun `coincident X and target edge do not invent an absent non X scoring region`() {
        val coincident = target.copy(
            zones = listOf(TargetZone(10, 0f, 1f, "center")),
            innerTenRadiusNormalized = 1f,
        )
        val hints = findBoundaryHints(coincident, NormalizedPoint(1f, 0f), 0f)
        assertEquals(listOf(BoundaryType.X_RING, BoundaryType.TARGET_EDGE), hints.map { it.type })
        for (hint in hints) {
            assertEquals(BoundaryValue(10, isX = true), hint.inner)
            assertEquals(BoundaryValue(0), hint.outer)
        }
    }

    @Test
    fun `adjacent Float zone boundaries do not rely on epsilon sampling`() {
        val nextRadius = Math.nextUp(0.5f)
        val narrow = binaryTarget().copy(zones = listOf(
            TargetZone(10, 0f, 0.5f, "center"),
            TargetZone(7, 0.5f, nextRadius, "narrow"),
            TargetZone(1, nextRadius, 1f, "outer"),
        ))
        val hints = findBoundaryHints(narrow, NormalizedPoint(0.5f, 0f), Math.ulp(0.5f))
        assertEquals(listOf(0.5f, nextRadius), hints.map { it.radiusNormalized })
        assertEquals(listOf(BoundaryValue(10), BoundaryValue(7)), hints.map { it.inner })
        assertEquals(listOf(BoundaryValue(7), BoundaryValue(1)), hints.map { it.outer })
    }

    @Test
    fun `radial convention agrees with existing geometry across axes and diagonals`() {
        val binary = binaryTarget()
        val expected = findBoundaryHints(binary, NormalizedPoint(0.5f, 0f), 0f)
        for (point in listOf(NormalizedPoint(-0.5f, 0f), NormalizedPoint(0f, -0.5f),
            NormalizedPoint(0.3f, 0.4f), NormalizedPoint(-0.3f, -0.4f))) {
            assertEquals(0.5f, calculateScore(binary, point).distanceFromCenter)
            assertEquals(expected, findBoundaryHints(binary, point, 0f))
        }
    }

    @Test
    fun `hints leave geometric results legacy cordon fields and final decisions unchanged`() {
        for (radius in listOf(0.049f, 0.051f, 0.095f, 0.105f, 0.195f, 0.995f, 1.005f)) {
            val point = NormalizedPoint(radius, 0f)
            val before = calculateScore(target, point)
            val decisions = ScoringMode.entries.associateWith { resolveFinalScore(before, it, before.score) }
            findBoundaryHints(target, point, 0.2f)
            val after = calculateScore(target, point)
            assertEquals(before, after)
            for (mode in ScoringMode.entries) assertEquals(decisions[mode], resolveFinalScore(after, mode, after.score))
        }
        // Characterize the known legacy defect, deliberately left for the next PR.
        val legacy = calculateScore(target, NormalizedPoint(0.105f, 0f))
        assertEquals(9, legacy.score)
        assertTrue(legacy.isNearCordon)
        assertEquals(10, legacy.cordonZoneAbove)
        assertEquals(10, resolveFinalScore(legacy, ScoringMode.COMPETITION).scoreFinal)
    }

    @Test
    fun `invalid tolerances are explicitly rejected`() {
        for (tolerance in listOf(-0.015f, -Float.MIN_VALUE, Float.NaN,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            val error = assertFailsWith<IllegalArgumentException> {
                findBoundaryHints(target, NormalizedPoint(0f, 0f), tolerance)
            }
            assertTrue(error.message.orEmpty().contains("touchToleranceNormalized"))
        }
    }

    @Test
    fun `non finite coordinates are rejected by the point input`() {
        for (coordinate in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { findBoundaryHints(target, NormalizedPoint(coordinate, 0f)) }
            assertFailsWith<IllegalArgumentException> { findBoundaryHints(target, NormalizedPoint(0f, coordinate)) }
        }
    }

    @Test
    fun `center and points well outside have no candidates at default tolerance`() {
        for (point in listOf(NormalizedPoint(0f, 0f), NormalizedPoint(Float.MIN_VALUE, 0f),
            NormalizedPoint(1.1f, 0f), NormalizedPoint(0f, -1.1f),
            NormalizedPoint(1e20f, -1e20f), NormalizedPoint(Float.MAX_VALUE, 0f))) {
            assertTrue(findBoundaryHints(target, point).isEmpty())
        }
    }

    @Test
    fun `unrepresentable radial distances retain the explicit input error`() {
        val error = assertFailsWith<IllegalArgumentException> {
            findBoundaryHints(target, NormalizedPoint(Float.MAX_VALUE, Float.MAX_VALUE))
        }
        assertTrue(error.message.orEmpty().contains("Float"))
    }

    @Test
    fun `large finite tolerance and coordinates never overflow boundary distances`() {
        val hints = findBoundaryHints(target, NormalizedPoint(Float.MAX_VALUE, 0f), Float.MAX_VALUE)
        assertEquals(11, hints.size)
        assertTrue(hints.all { it.distanceToBoundaryNormalized.isFinite() })
    }

    private fun binaryTarget(): TargetType = target.copy(
        zones = listOf(TargetZone(10, 0f, 0.5f, "center"), TargetZone(1, 0.5f, 1f, "outer")),
        xRingEnabled = false,
        innerTenRadiusNormalized = 0f,
    )

    private fun sparseTarget(): TargetType = target.copy(
        minScore = 3,
        zones = listOf(TargetZone(10, 0f, 0.25f, "center"),
            TargetZone(6, 0.25f, 0.75f, "middle"), TargetZone(3, 0.75f, 1f, "outer")),
        xRingEnabled = false,
        innerTenRadiusNormalized = 0f,
    )
}
