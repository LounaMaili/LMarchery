package com.lmarchery.feature.scoring

import com.lmarchery.core.model.NormalizedPoint
import kotlin.test.*

class ImpactPickGestureTest {
    private val size = CanvasSize(100f, 100f)
    private val down = CanvasPoint(50f, 50f)
    @Test fun `single pointer tap returns the released normalized point once`() {
        val gesture = ImpactPickGesture(1, size, down, 8f)
        assertTrue(gesture.move(1, down, 1))
        assertEquals(NormalizedPoint(0f, 0f), gesture.release(1, down, 1))
        assertNull(gesture.release(1, down, 1)); assertTrue(gesture.finished)
    }
    @Test fun `scroll or drag beyond touch slop cancels permanently`() {
        val gesture = ImpactPickGesture(1, size, down, 8f)
        assertFalse(gesture.move(1, CanvasPoint(60f, 50f), 1))
        assertNull(gesture.release(1, down, 1))
    }
    @Test fun `touch slop exact boundary is reproducible without epsilon`() {
        val start = CanvasPoint(0f, 0f)
        assertNotNull(ImpactPickGesture(1, size, start, 8f).release(1, CanvasPoint(8f, 0f), 1))
        assertNull(ImpactPickGesture(1, size, start, 8f).release(1, CanvasPoint(Math.nextUp(8f), 0f), 1))
    }
    @Test fun `second pointer and different owner cannot become a selection`() {
        val gesture = ImpactPickGesture(1, size, down, 8f)
        assertFalse(gesture.move(1, down, 2)); assertNull(gesture.release(1, down, 1))
        assertNull(ImpactPickGesture(1, size, down, 8f).release(2, down, 1))
    }
    @Test fun `consumed or out of bounds events cancel selection`() {
        assertNull(ImpactPickGesture(1, size, down, 100f).release(1, down, 1, consumed = true))
        assertNull(ImpactPickGesture(1, size, down, 100f).release(1, CanvasPoint(-1f, 50f), 1))
    }
    @Test fun `disposal cancellation cannot resume on a later release`() {
        val gesture = ImpactPickGesture(1, size, down, 8f)
        gesture.cancel(); assertNull(gesture.release(1, down, 1))
    }
    @Test fun `invalid down and touch slop are rejected`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { ImpactPickGesture(1, size, down, it) }
        }
        assertFailsWith<IllegalArgumentException> { ImpactPickGesture(1, size, CanvasPoint(Float.NaN, 0f), 8f) }
    }
}
