package com.lmarchery.core.model.time

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UtcTimestampsTest {
    @Test
    fun `different explicit offsets identify the same instant`() {
        val values = listOf("2026-04-28T16:02:00Z", "2026-04-28T17:02:00+01:00", "2026-04-28T18:02:00+02:00")
        val instant = Instant.parse("2026-04-28T16:02:00Z")
        assertEquals(listOf(instant, instant, instant), values.map(UtcTimestamps::parse))
        assertEquals("2026-04-28T16:02:00Z", UtcTimestamps.format(instant))
    }

    @Test
    fun `offset changes do not reverse event ordering across the autumn overlap`() {
        val earlier = UtcTimestamps.parse("2026-10-25T02:50:00+02:00")
        val later = UtcTimestamps.parse("2026-10-25T02:10:00+01:00")
        assertTrue(later.isAfter(earlier))
        assertEquals(1200L, later.epochSecond - earlier.epochSecond)
    }

    @Test
    fun `UTC formatting round trips nanoseconds and dates before the epoch`() {
        for (value in listOf("2026-04-28T16:02:00.123456789Z", "2026-04-28T16:02:00.000000001Z", "1969-12-31T23:59:59.999999999Z")) {
            val instant = Instant.parse(value)
            assertEquals(instant, UtcTimestamps.parse(UtcTimestamps.format(instant)))
            assertTrue(UtcTimestamps.format(instant).endsWith("Z"))
        }
    }

    @Test
    fun `naive invalid and unknown-offset strings are rejected without normalization`() {
        for (text in listOf("2026-04-28", "2026-04-28T18:02:00", "", "not-a-date",
            "2026-02-29T10:00:00Z", "2026-04-28T24:00:00Z", "2016-12-31T23:59:60Z",
            "2026-04-28T18:02:00+25:00", "2026-04-28T18:02:00-00:00", "2026-04-28T18:02:00-00",
            "2026-04-28T18:02:00+02:00[Europe/Paris]", " 2026-04-28T16:02:00Z", "2026-04-28T16:02:00Z ")) {
            assertFailsWith<IllegalArgumentException>(text) { UtcTimestamps.parse(text) }
        }
    }

    @Test
    fun `formatting an unrepresentable civil calendar instant fails explicitly`() {
        assertFailsWith<IllegalArgumentException> { UtcTimestamps.format(Instant.MAX) }
        assertFailsWith<IllegalArgumentException> { UtcTimestamps.format(Instant.MIN) }
    }

    @Test
    fun `consultation zone changes only the display not the stored instant`() {
        val instant = UtcTimestamps.parse("2026-07-14T18:00:00+02:00")
        val paris = instant.atZone(ZoneId.of("Europe/Paris"))
        val tokyo = instant.atZone(ZoneId.of("Asia/Tokyo"))
        assertEquals(18, paris.hour)
        assertEquals(1, tokyo.hour)
        assertEquals(instant, paris.toInstant())
        assertEquals(instant, tokyo.toInstant())
    }
}
