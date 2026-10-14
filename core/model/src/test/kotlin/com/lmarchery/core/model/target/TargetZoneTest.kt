package com.lmarchery.core.model.target

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetZoneTest {
    @Test
    fun `radii must be finite`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { zone(inner = value) }
            assertFailsWith<IllegalArgumentException> { zone(outer = value) }
        }
    }

    @Test
    fun `zone must have positive width inside the normalized disk`() {
        for ((inner, outer) in listOf(-0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.2f, 0f to 1.1f)) {
            assertFailsWith<IllegalArgumentException> { zone(inner, outer) }
        }
        assertEquals(1f, zone(0f, 1f).outerRadiusNormalized)
    }

    @Test
    fun `zero is reserved for misses outside the scoring zones`() {
        assertFailsWith<IllegalArgumentException> { zone().copy(score = 0) }
        assertFailsWith<IllegalArgumentException> { zone().copy(score = -1) }
    }

    @Test
    fun `copy validates both radii`() {
        val original = zone()
        assertFailsWith<IllegalArgumentException> { original.copy(outerRadiusNormalized = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { original.copy(innerRadiusNormalized = -0.1f) }
        assertFailsWith<IllegalArgumentException> { original.copy(outerRadiusNormalized = 0f) }
    }

    private fun zone(inner: Float = 0f, outer: Float = 0.1f): TargetZone =
        TargetZone(10, inner, outer, "yellow")
}
