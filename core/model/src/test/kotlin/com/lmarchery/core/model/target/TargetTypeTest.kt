package com.lmarchery.core.model.target

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame

class TargetTypeTest {
    private val zones = listOf(
        TargetZone(10, 0f, 0.1f, "yellow"),
        TargetZone(7, 0.1f, 0.6f, "red"),
        TargetZone(1, 0.6f, 1f, "white"),
    )

    @Test
    fun `full contiguous targets accept input order and sparse score values`() {
        assertEquals(zones, target().zones)
        assertEquals(zones.reversed(), target(zones = zones.reversed()).zones)
        assertEquals(0, target(minScore = 0).minScore)
    }

    @Test
    fun `empty or incomplete radial coverage is rejected`() {
        assertFailsWith<IllegalArgumentException> { target(zones = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            target(zones = zones.drop(1))
        }
        assertFailsWith<IllegalArgumentException> {
            target(zones = zones.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            target(zones = listOf(TargetZone(10, 0.01f, 1f, "yellow")))
        }
    }

    @Test
    fun `holes and overlaps are rejected without a tolerance`() {
        for (inner in listOf(0.09f, 0.11f, Math.nextDown(0.1f), Math.nextUp(0.1f))) {
            val inconsistent = zones.toMutableList()
            inconsistent[1] = inconsistent[1].copy(innerRadiusNormalized = inner)
            assertFailsWith<IllegalArgumentException> { target(zones = inconsistent) }
        }
    }

    @Test
    fun `duplicate radial definitions are rejected`() {
        assertFailsWith<IllegalArgumentException> { target(zones = zones + zones.first()) }
    }

    @Test
    fun `zone scores must lie within declared bounds`() {
        assertFailsWith<IllegalArgumentException> { target(minScore = -1) }
        assertFailsWith<IllegalArgumentException> { target(minScore = 2) }
        assertFailsWith<IllegalArgumentException> { target(maxScore = 9) }
        assertFailsWith<IllegalArgumentException> { target(minScore = 11) }
    }

    @Test
    fun `central zone must score the declared maximum`() {
        assertFailsWith<IllegalArgumentException> { target(maxScore = 11) }
    }

    @Test
    fun `scores must decrease strictly from the center`() {
        for (middleScore in listOf(10, 1)) {
            val repeated = zones.toMutableList()
            repeated[1] = repeated[1].copy(score = middleScore)
            assertFailsWith<IllegalArgumentException> { target(zones = repeated) }
        }
        assertFailsWith<IllegalArgumentException> {
            target(zones = listOf(
                TargetZone(10, 0f, 0.1f, "yellow"),
                TargetZone(1, 0.1f, 0.6f, "white"),
                TargetZone(7, 0.6f, 1f, "red"),
            ))
        }
    }

    @Test
    fun `X radius must be finite non negative and inside the central zone`() {
        for (radius in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 0.11f)) {
            assertFailsWith<IllegalArgumentException> { target(xRadius = radius) }
            assertFailsWith<IllegalArgumentException> { target(xRadius = radius, xRingEnabled = false) }
        }
        assertFailsWith<IllegalArgumentException> { target(xRadius = 0f) }
        assertEquals(0.1f, target(xRadius = 0.1f).innerTenRadiusNormalized)
    }

    @Test
    fun `disabled X can keep its configuration or use a zero radius`() {
        val disabled = target().copy(xRingEnabled = false)
        assertEquals(0.05f, disabled.innerTenRadiusNormalized)
        assertEquals("X", disabled.xRingLabel)
        assertEquals(0f, target(xRadius = 0f, xRingEnabled = false).innerTenRadiusNormalized)
    }

    @Test
    fun `constructor snapshots a mutable list and protects exposed zones`() {
        val supplied = zones.toMutableList()
        val value = target(zones = supplied)
        val originalHash = value.hashCode()
        val lookup = setOf(value)
        supplied.clear()

        assertEquals(zones, value.zones)
        assertEquals(originalHash, value.hashCode())
        assertEquals(true, value in lookup)
        assertFailsWith<UnsupportedOperationException> { (value.zones as MutableList<TargetZone>).clear() }
    }

    @Test
    fun `copy snapshots its own supplied list`() {
        val original = target()
        val supplied = zones.reversed().toMutableList()
        val copied = original.copy(zones = supplied)
        supplied.clear()

        assertEquals(zones.reversed(), copied.zones)
        assertEquals(zones, original.zones)
        assertFailsWith<UnsupportedOperationException> { (copied.zones as MutableList<TargetZone>)[0] = zones.first() }
    }

    @Test
    fun `copy revalidates topology scores and X configuration`() {
        val original = target()
        assertFailsWith<IllegalArgumentException> { original.copy(zones = zones.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { original.copy(maxScore = 11) }
        assertFailsWith<IllegalArgumentException> { original.copy(minScore = 2) }
        assertFailsWith<IllegalArgumentException> { original.copy(innerTenRadiusNormalized = 0.2f) }
    }

    @Test
    fun `content equality hash string and decomposition are preserved`() {
        val original = target()
        val copied = original.copy()
        val rebuilt = target(zones = original.zones.toList())
        assertNotSame(original, copied)
        assertEquals(original, copied)
        assertEquals(original, rebuilt)
        assertEquals(original.hashCode(), copied.hashCode())
        assertEquals(original.toString(), copied.toString())
        assertNotEquals(original, original.copy(name = "Different"))

        val (uuid, name, discipline, diameter, maximum, minimum, arrows, distance,
            builtIn, xEnabled, xLabel, xRadius, decomposedZones) = copied
        assertEquals(listOf<Any>("test-target", "Test target", "test", 40, 10, 1, 3, 18,
            false, true, "X", 0.05f, zones), listOf<Any>(uuid, name, discipline, diameter,
            maximum, minimum, arrows, distance, builtIn, xEnabled, xLabel, xRadius, decomposedZones))
    }

    private fun target(
        zones: List<TargetZone> = this.zones,
        minScore: Int = 1,
        maxScore: Int = 10,
        xRadius: Float = 0.05f,
        xRingEnabled: Boolean = true,
    ): TargetType = TargetType(
        uuid = "test-target",
        name = "Test target",
        discipline = "test",
        diameterCm = 40,
        maxScore = maxScore,
        minScore = minScore,
        defaultArrowCount = 3,
        defaultDistanceM = 18,
        isBuiltIn = false,
        xRingEnabled = xRingEnabled,
        xRingLabel = "X",
        innerTenRadiusNormalized = xRadius,
        zones = zones,
    )
}
