package com.lmarchery.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NormalizedPointTest {
    @Test
    fun `non finite coordinates are rejected on either axis`() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { NormalizedPoint(value, 0f) }
            assertFailsWith<IllegalArgumentException> { NormalizedPoint(0f, value) }
        }
    }

    @Test
    fun `copy also rejects non finite coordinates`() {
        val point = NormalizedPoint(0f, 0f)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { point.copy(x = value) }
            assertFailsWith<IllegalArgumentException> { point.copy(y = value) }
        }
    }

    @Test
    fun `finite coordinates are not restricted to the target disk`() {
        assertEquals(1.1f, NormalizedPoint(1.1f, 0f).x)
        assertEquals(-Float.MAX_VALUE, NormalizedPoint(-Float.MAX_VALUE, Float.MAX_VALUE).x)
    }
}
