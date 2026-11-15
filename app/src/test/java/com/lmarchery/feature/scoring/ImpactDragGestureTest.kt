package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.test.*

class ImpactDragGestureTest {
    private val size = CanvasSize(1000f, 800f)
    private val start = NormalizedPoint(.25f, 0f)
    private fun gesture(down: CanvasPoint = CanvasPoint(610f, 410f), radius: Float = 24f) =
        ImpactDragGesture(7, size, down, start, radius, 8f)

    @Test fun `grab offset is retained and release only emits a candidate`() {
        val g = gesture(); assertTrue(g.accepted)
        assertEquals(start, g.move(7, CanvasPoint(614f, 410f), 1))
        val next = g.move(7, CanvasPoint(690f, 450f), 1)!!
        assertEquals(.45f, next.x, .000001f); assertEquals(.1f, next.y, .000001f)
        assertEquals(next, g.release(7, CanvasPoint(690f, 450f), 1))
        assertNull(g.release(7, CanvasPoint(690f, 450f), 1)); assertTrue(g.finished)
    }
    @Test fun `tap and exact slop cannot propose a move`() {
        for (dx in listOf(0f, 8f)) {
            val g = gesture(); assertNull(g.release(7, CanvasPoint(610f + dx, 410f), 1)); assertTrue(g.finished)
        }
    }
    @Test fun `only selected marker hit area is eligible and boundary is inclusive`() {
        assertTrue(gesture(CanvasPoint(624f, 400f)).accepted)
        assertFalse(gesture(CanvasPoint(Float.fromBits(624f.toBits() + 1), 400f)).accepted)
        assertFalse(gesture(CanvasPoint(200f, 400f)).accepted)
    }
    @Test fun `second pointer wrong identity and consumed scroll cancel terminally`() {
        for ((id, count, consumed) in listOf(Triple(7L, 2, false), Triple(8L, 1, false), Triple(7L, 1, true))) {
            val g = gesture(); assertNull(g.move(id, CanvasPoint(700f, 410f), count, consumed))
            assertNull(g.release(7, CanvasPoint(700f, 410f), 1)); assertTrue(g.finished)
        }
    }
    @Test fun `finger or shifted marker leaving canvas cancels without clamping`() {
        for (p in listOf(CanvasPoint(-1f, 410f), CanvasPoint(1010f, 410f), CanvasPoint(700f, -1f), CanvasPoint(700f, 801f))) {
            val g = gesture(); assertNull(g.move(7, p, 1)); assertTrue(g.finished)
        }
        val g = gesture(CanvasPoint(590f, 400f)) // Grab 10 px left of marker.
        assertNull(g.move(7, CanvasPoint(995f, 400f), 1)) // Marker would be beyond 1000.
    }
    @Test fun `visible square corners remain misses rather than being snapped to circle`() {
        val g = gesture(CanvasPoint(600f, 400f))
        val p = g.release(7, CanvasPoint(990f, 790f), 1)!!
        assertTrue(p.x * p.x + p.y * p.y > 1f)
    }
    @Test fun `invisible marker and invalid down are refused`() {
        assertFalse(ImpactDragGesture(1, size, CanvasPoint(999f, 400f), NormalizedPoint(2f, 0f), 100f, 8f).accepted)
        assertFalse(gesture(CanvasPoint(Float.NaN, 0f)).accepted)
    }
    @Test fun `invalid radii and slop are rejected`() {
        for (n in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { gesture(radius = n) }
            assertFailsWith<IllegalArgumentException> { ImpactDragGesture(1, size, CanvasPoint(600f,400f), start, 24f, n) }
        }
    }
    @Test fun `explicit cancellation cannot later release`() {
        val g = gesture(); g.move(7, CanvasPoint(700f, 410f), 1); g.cancel()
        assertNull(g.release(7, CanvasPoint(700f, 410f), 1))
    }
}
