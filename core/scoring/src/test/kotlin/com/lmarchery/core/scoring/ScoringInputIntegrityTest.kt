package com.lmarchery.core.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringInputIntegrityTest {
    private val target = ClassicTargetFixtures.classic40

    @Test
    fun `ordinary outside impacts remain misses with their actual distance`() {
        for (point in listOf(NormalizedPoint(1.1f, 0f), NormalizedPoint(-1.1f, 0f), NormalizedPoint(0f, -1.1f))) {
            val result = calculateScore(target, point)
            assertEquals(1.1f, result.distanceFromCenter)
            assertEquals(0, result.score)
            assertTrue(result.isMiss)
            assertFalse(result.isX)
            assertNull(result.zone)
        }
    }

    @Test
    fun `large finite coordinates do not overflow intermediate squares`() {
        for (point in listOf(
            NormalizedPoint(1e20f, -1e20f),
            NormalizedPoint(Float.MAX_VALUE, 0f),
            NormalizedPoint(0f, -Float.MAX_VALUE),
            NormalizedPoint(Float.MAX_VALUE / 2f, Float.MAX_VALUE / 2f),
        )) {
            val result = calculateScore(target, point)
            assertEquals(hypot(point.x.toDouble(), point.y.toDouble()).toFloat(), result.distanceFromCenter)
            assertTrue(result.distanceFromCenter.isFinite())
            assertTrue(result.isMiss)
            assertEquals(0, result.score)
        }
    }

    @Test
    fun `unrepresentable distances are explicitly rejected rather than clamped`() {
        for (point in listOf(
            NormalizedPoint(Float.MAX_VALUE, Float.MAX_VALUE),
            NormalizedPoint(-Float.MAX_VALUE, Float.MAX_VALUE),
            NormalizedPoint(Float.MAX_VALUE, Float.MAX_VALUE / 2f),
        )) {
            val error = assertFailsWith<IllegalArgumentException> { calculateScore(target, point) }
            assertTrue(error.message.orEmpty().contains("Float"))
        }
    }

    @Test
    fun `small finite coordinates retain their non zero distance`() {
        val result = calculateScore(target, NormalizedPoint(Float.MIN_VALUE, 0f))
        assertEquals(Float.MIN_VALUE, result.distanceFromCenter)
        assertEquals(10, result.score)
    }

    @Test
    fun `results and copies cannot contain non finite or negative distances`() {
        val valid = calculateScore(target, NormalizedPoint(0f, 0f))
        for (distance in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertFailsWith<IllegalArgumentException> { valid.copy(distanceFromCenter = distance) }
            assertFailsWith<IllegalArgumentException> {
                ScoreResult(10, valid.zone, distance, true, false, false, null)
            }
        }
    }

    @Test
    fun `all four classic fixtures retain geometric scores and inclusive boundaries`() {
        assertEquals(listOf(40, 60, 80, 122), ClassicTargetFixtures.all.map { it.diameterCm })
        for (fixture in ClassicTargetFixtures.all) {
            assertEquals(fixture, fixture.copy())
            assertEquals(fixture, fixture.copy(zones = fixture.zones.toMutableList()))
            for (zone in fixture.zones) {
                val midpoint = (zone.innerRadiusNormalized + zone.outerRadiusNormalized) / 2f
                assertEquals(zone.score, calculateScore(fixture, NormalizedPoint(midpoint, 0f)).score)
                val boundary = zone.outerRadiusNormalized
                assertEquals(zone.score, calculateScore(fixture, NormalizedPoint(boundary, 0f)).score)
                assertEquals(zone.score, calculateScore(fixture, NormalizedPoint(Math.nextDown(boundary), 0f)).score)
                assertEquals(zone.score - 1, calculateScore(fixture, NormalizedPoint(Math.nextUp(boundary), 0f)).score)
            }
            assertEquals(1, calculateScore(fixture, NormalizedPoint(0.6f, 0.8f)).score)
            assertTrue(calculateScore(fixture, NormalizedPoint(0.05f, 0f)).isX)
            assertFalse(calculateScore(fixture, NormalizedPoint(Math.nextUp(0.05f), 0f)).isX)
        }
    }

    @Test
    fun `corrected documentation examples follow the existing geometry`() {
        val cases = listOf(
            NormalizedPoint(0.30f, 0.20f) to 7,
            NormalizedPoint(0.41f, -0.30f) to 5,
            NormalizedPoint(0.195f, -0.02f) to 9,
            NormalizedPoint(0.85f, 0.50f) to 1,
        )
        for ((point, score) in cases) assertEquals(score, calculateScore(target, point).score)

        val trainingEnd = listOf(
            NormalizedPoint(0.05f, -0.02f), NormalizedPoint(0.22f, 0.15f), NormalizedPoint(0.41f, -0.30f),
        )
        assertEquals(23, trainingEnd.sumOf {
            resolveFinalScore(target, it, ScoringMode.TRAINING).scoreFinal
        })
        val competitionEnd = listOf(
            NormalizedPoint(0f, 0f), NormalizedPoint(0.195f, -0.02f), NormalizedPoint(0.85f, 0.50f),
        )
        assertEquals(20, competitionEnd.sumOf {
            resolveFinalScore(target, it, ScoringMode.COMPETITION).scoreFinal
        })
        assertFalse(calculateScore(target, trainingEnd.first()).isX)
    }
}
