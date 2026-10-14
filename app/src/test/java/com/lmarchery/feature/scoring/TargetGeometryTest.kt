package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TargetGeometryTest {
    @Test
    fun `canvas center maps to normalized origin`() {
        val point = canvasPointToNormalized(
            point = CanvasPoint(100f, 100f),
            canvasSize = CanvasSize(widthPx = 200f, heightPx = 200f),
        )

        assertEquals(0f, point.x, absoluteTolerance = 0.0001f)
        assertEquals(0f, point.y, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `canvas borders map to documented normalized coordinates`() {
        val canvasSize = CanvasSize(widthPx = 200f, heightPx = 200f)
        val right = canvasPointToNormalized(CanvasPoint(200f, 100f), canvasSize)
        val top = canvasPointToNormalized(CanvasPoint(100f, 0f), canvasSize)
        val bottom = canvasPointToNormalized(CanvasPoint(100f, 200f), canvasSize)

        assertEquals(1f, right.x, absoluteTolerance = 0.0001f)
        assertEquals(0f, right.y, absoluteTolerance = 0.0001f)
        assertEquals(0f, top.x, absoluteTolerance = 0.0001f)
        assertEquals(-1f, top.y, absoluteTolerance = 0.0001f)
        assertEquals(0f, bottom.x, absoluteTolerance = 0.0001f)
        assertEquals(1f, bottom.y, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `rectangular canvas uses min dimension and visual center`() {
        val canvasSize = CanvasSize(widthPx = 300f, heightPx = 200f)
        val center = canvasPointToNormalized(CanvasPoint(150f, 100f), canvasSize)
        val rightEdge = canvasPointToNormalized(CanvasPoint(250f, 100f), canvasSize)

        assertEquals(0f, center.x, absoluteTolerance = 0.0001f)
        assertEquals(0f, center.y, absoluteTolerance = 0.0001f)
        assertEquals(1f, rightEdge.x, absoluteTolerance = 0.0001f)
        assertEquals(0f, rightEdge.y, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `canvas corners remain explicit misses with radius above one`() {
        val point = canvasPointToNormalized(
            point = CanvasPoint(200f, 200f),
            canvasSize = CanvasSize(widthPx = 200f, heightPx = 200f),
        )

        assertTrue(hypot(point.x, point.y) > 1f)
    }

    @Test
    fun `normalized point maps back to canvas point`() {
        val point = normalizedPointToCanvas(
            point = NormalizedPoint(x = -0.5f, y = 0.25f),
            canvasSize = CanvasSize(widthPx = 200f, heightPx = 200f),
        )

        assertEquals(50f, point.x, absoluteTolerance = 0.0001f)
        assertEquals(125f, point.y, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `zero canvas size is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CanvasSize(widthPx = 0f, heightPx = 200f)
        }
    }
}
