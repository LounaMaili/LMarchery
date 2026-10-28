package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.calculateScore
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlin.test.*

class PrecisionAimTest {
    private val square = CanvasSize(400f, 400f)
    private val center = CanvasPoint(200f, 200f)

    @Test fun `pointer down move and release produce one normalized final point`() {
        val g = PrecisionGesture(7, square)
        assertEquals(NormalizedPoint(0f, 0f), g.move(7, center, 1))
        assertEquals(NormalizedPoint(.1f, 0f), g.move(7, CanvasPoint(220f, 200f), 1))
        assertEquals(NormalizedPoint(.2f, 0f), g.release(7, CanvasPoint(240f, 200f), 1))
        assertTrue(g.finished); assertNull(g.release(7, center, 1)); assertNull(g.move(7, center, 1))
    }
    @Test fun `short precise press still only returns a candidate`() {
        val g = PrecisionGesture(1, square)
        val down = g.move(1, center, 1)
        assertEquals(down, g.release(1, center, 1))
        assertTrue(g.finished)
    }
    @Test fun `second pointer or changed identity cancels until next gesture`() {
        listOf(1L to 2, 2L to 1).forEach { (id, count) ->
            val g = PrecisionGesture(1, square); g.move(1, center, 1)
            assertNull(g.move(id, center, count)); assertTrue(g.finished)
            assertNull(g.point); assertNull(g.release(1, center, 1))
        }
    }
    @Test fun `cancelled or consumed gesture cannot release a candidate`() {
        val g = PrecisionGesture(1, square); g.move(1, center, 1); g.cancel()
        assertNull(g.point); assertNull(g.release(1, center, 1))
        val consumed = PrecisionGesture(1, square); consumed.move(1, center, 1)
        assertNull(consumed.release(1, center, 1, consumed = true))
    }
    @Test fun `outside canvas cancels without clamping even if pointer returns inside`() {
        listOf(CanvasPoint(-1f, 200f), CanvasPoint(401f, 200f), CanvasPoint(200f, -1f), CanvasPoint(200f, 401f)).forEach {
            val g = PrecisionGesture(1, square); g.move(1, center, 1)
            assertNull(g.release(1, it, 1)); assertNull(g.point)
            assertNull(g.release(1, center, 1))
        }
    }
    @Test fun `corners and exact canvas edges remain unclamped misses or boundary points`() {
        val g = PrecisionGesture(1, square)
        val corner = assertNotNull(g.release(1, CanvasPoint(400f, 400f), 1))
        assertEquals(NormalizedPoint(1f, 1f), corner); assertTrue(calculateScore(WA.recurve40, corner).isMiss)
        assertTrue(aimPointInsideCanvas(CanvasPoint(400f, 200f), square))
        assertEquals(1, calculateScore(WA.recurve40, NormalizedPoint(1f, 0f)).score)
    }
    @Test fun `normalized point survives scaling portrait landscape and small canvas`() {
        val points = listOf(NormalizedPoint(0f, 0f), NormalizedPoint(.05f, -.1f), NormalizedPoint(.105f, .2f), NormalizedPoint(1f, 1f))
        listOf(CanvasSize(240f, 240f), square, CanvasSize(800f, 400f), CanvasSize(400f, 800f)).forEach { size ->
            points.forEach { p ->
                val restored = canvasPointToNormalized(normalizedPointToCanvas(p, size), size)
                assertEquals(p.x, restored.x, 0.000001f); assertEquals(p.y, restored.y, 0.000001f)
            }
        }
    }
    @Test fun `lens reticle and submitted candidate share exactly the same point`() {
        listOf(NormalizedPoint(.075f, 0f), NormalizedPoint(-.105f, .15f), NormalizedPoint(1f, 1f)).forEach { point ->
            val lens = aimLens(point, square)
            assertEquals(lens.center, lens.project(point, point, square))
            assertEquals(4f, lens.magnification)
            val neighbour = point.copy(x = point.x + .01f)
            val projected = lens.project(neighbour, point, square)
            assertEquals(square.radiusPx * .01f * 4f, projected.x - lens.center.x, .0001f)
        }
    }
    @Test fun `lens is opposite the finger and stays in bounds for either orientation`() {
        listOf(square, CanvasSize(800f, 400f), CanvasSize(200f, 320f)).forEach { size ->
            listOf(-1f, 1f).forEach { x -> listOf(-1f, 1f).forEach { y ->
                val p = NormalizedPoint(x, y); val lens = aimLens(p, size)
                assertTrue((lens.center.x - size.centerX) * x < 0)
                assertTrue((lens.center.y - size.centerY) * y < 0)
                assertTrue(lens.center.x - lens.radiusPx >= 0 && lens.center.x + lens.radiusPx <= size.widthPx)
                assertTrue(lens.center.y - lens.radiusPx >= 0 && lens.center.y + lens.radiusPx <= size.heightPx)
            } }
        }
    }
    @Test fun `classic and compound use actual rings and no added score tolerance`() {
        listOf(.025f to (10 to 10), .075f to (10 to 9), .105f to (9 to 9)).forEach { (radius, scores) ->
            val point = PrecisionGesture(1, square).release(1, normalizedPointToCanvas(NormalizedPoint(radius, 0f), square), 1)!!
            assertEquals(scores.first, calculateScore(WA.recurve40, point).score)
            assertEquals(scores.second, calculateScore(WA.compound40, point).score)
        }
    }
    @Test fun `nonfinite positions and canvas sizes are rejected`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFalse(aimPointInsideCanvas(CanvasPoint(it, 0f), square))
            assertFailsWith<IllegalArgumentException> { CanvasSize(it, 400f) }
            assertFailsWith<IllegalArgumentException> { CanvasSize(400f, it) }
        }
    }
    @Test fun `root multitouch guard blocks controls until every finger has lifted`() {
        val guard = AimMultiTouchGuard()
        assertFalse(guard.reject(1, true))
        assertTrue(guard.reject(2, true)) // Second finger may be outside the target.
        assertTrue(guard.reject(2, true))
        assertTrue(guard.reject(1, true))
        assertTrue(guard.reject(1, false))
        assertFalse(guard.reject(1, true))
    }
    @Test fun `precise target fits viewport height in landscape without changing normalized position`() {
        assertEquals(234f, precisionTargetMaxWidth(360f), .0001f)
        assertEquals(480f, precisionTargetMaxWidth(900f))
        assertFailsWith<IllegalArgumentException> { precisionTargetMaxWidth(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { precisionTargetMaxWidth(0f) }
        val point = NormalizedPoint(.075f, .105f)
        val size = CanvasSize(234f, 234f)
        val restored = canvasPointToNormalized(normalizedPointToCanvas(point, size), size)
        assertEquals(point.x, restored.x, .000001f); assertEquals(point.y, restored.y, .000001f)
    }

}
