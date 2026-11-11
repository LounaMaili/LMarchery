package com.lmarchery.feature.sessions

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.scoreArrowImpact
import java.time.Instant
import kotlin.test.*

class ImpactPickingTest {
    private val origin = NormalizedPoint(0f, 0f)
    private fun arrow(n: Int, x: Float = 0f, y: Float = 0f) = scoreArrowImpact(
        "46000000-0000-4000-8000-${n.toString().padStart(12, '0')}", n,
        ClassicTargetFixtures.classic40, NormalizedPoint(x, y), ScoringMode.TRAINING,
        createdAt = Instant.parse("2026-11-11T08:00:00Z"))

    @Test fun `empty target and empty hit return no candidate`() {
        assertTrue(findImpactCandidates(emptyList(), origin, .125f).isEmpty())
        assertTrue(findImpactCandidates(listOf(arrow(1, .5f)), origin, .125f).isEmpty())
    }
    @Test fun `zero tolerance matches only the exact position`() {
        val exact = arrow(1); val other = arrow(2, Float.MIN_VALUE)
        assertEquals(listOf(exact.uuid), findImpactCandidates(listOf(other, exact), origin, 0f))
    }
    @Test fun `boundary is inclusive and immediately adjacent Float is excluded`() {
        val exact = arrow(1, .125f); val outside = arrow(2, Math.nextUp(.125f))
        assertEquals(listOf(exact.uuid), findImpactCandidates(listOf(outside, exact), origin, .125f))
    }
    @Test fun `hit area is symmetric and circular rather than a score ring`() {
        val items = listOf(arrow(4, -.125f), arrow(3, 0f, -.125f), arrow(2, .125f), arrow(1, 0f, .125f), arrow(5, .125f, .125f))
        assertEquals(items.filter { it.index != 5 }.sortedBy { it.index }.map { it.uuid }, findImpactCandidates(items, origin, .125f))
    }
    @Test fun `all nearby candidates remain distinct in stable index order without closest winner`() {
        val items = listOf(arrow(3, .01f), arrow(1, .1f), arrow(2, .01f))
        val expected = items.sortedBy { it.index }.map { it.uuid }
        assertEquals(expected, findImpactCandidates(items, origin, .125f))
        assertEquals(expected, findImpactCandidates(items.reversed(), origin, .125f))
    }
    @Test fun `X and corner miss are selected geometrically without changing their decisions`() {
        val x = arrow(1); val miss = arrow(2, .9f, .9f); val items = listOf(x, miss)
        assertTrue(x.isX); assertTrue(miss.isMiss)
        assertEquals(listOf(x.uuid), findImpactCandidates(items, origin, 0f))
        assertEquals(listOf(miss.uuid), findImpactCandidates(items, miss.point, 0f))
        assertEquals(listOf(x, miss), items); assertTrue(x.isX); assertTrue(miss.isMiss)
    }
    @Test fun `finite extreme coordinates do not overflow intermediate distance`() {
        val far = arrow(1, Float.MAX_VALUE)
        val large = ImpactPickViewport(Float.MAX_VALUE, Float.MAX_VALUE)
        assertTrue(findImpactCandidates(listOf(far), NormalizedPoint(-Float.MAX_VALUE, 0f), Float.MAX_VALUE, large).isEmpty())
        assertEquals(listOf(far.uuid), findImpactCandidates(listOf(far), far.point, 0f, large))
    }
    @Test fun `invalid tolerances and duplicate UUIDs are rejected explicitly`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { findImpactCandidates(listOf(arrow(1)), origin, it) }
        }
        val same = arrow(1)
        assertFailsWith<IllegalArgumentException> { findImpactCandidates(listOf(same, same), origin, .125f) }
    }
    @Test fun `candidate list cannot be mutated by a caller`() {
        val candidate = findImpactCandidates(listOf(arrow(1)), origin, 0f)
        assertFailsWith<UnsupportedOperationException> { (candidate as MutableList).clear() }
    }
    @Test fun `off canvas impact cannot be picked from an empty visible edge`() {
        val outside = arrow(1, 1.1f)
        assertTrue(outside.isMiss)
        assertTrue(findImpactCandidates(listOf(outside), NormalizedPoint(1f, 0f), .2f).isEmpty())
        assertEquals(1.1f, outside.point.x)
    }
    @Test fun `rectangular viewport keeps visible misses outside the scoring circle`() {
        val visible = arrow(1, 1.25f)
        assertTrue(visible.isMiss)
        assertTrue(findImpactCandidates(listOf(visible), visible.point, .1f).isEmpty())
        assertEquals(listOf(visible.uuid), findImpactCandidates(listOf(visible), visible.point, .1f, ImpactPickViewport(1.5f, 1f)))
        assertTrue(findImpactCandidates(listOf(visible), NormalizedPoint(1.6f, 0f), .5f, ImpactPickViewport(1.5f, 1f)).isEmpty())
    }
    @Test fun `nonfinite or nonpositive viewport dimensions are rejected`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { ImpactPickViewport(it, 1f) }
            assertFailsWith<IllegalArgumentException> { ImpactPickViewport(1f, it) }
        }
    }
}
