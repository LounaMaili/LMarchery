package com.lmarchery.core.model.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SessionDateTimeTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `French summer and winter retain civil time and explicit original zone`() {
        for ((text, offset, utc) in listOf(
            Triple("2026-07-14T18:00:00", "+02:00", "2026-07-14T16:00:00Z"),
            Triple("2026-01-14T18:00:00", "+01:00", "2026-01-14T17:00:00Z"),
        )) {
            val local = LocalDateTime.parse(text)
            val date = SessionDateTime.of(local, paris)
            assertEquals(local, date.localDateTime)
            assertEquals(paris, date.zoneId)
            assertEquals(ZoneOffset.of(offset), date.offset)
            assertEquals(Instant.parse(utc), date.toInstant())
            assertEquals(date, SessionDateTime.fromInstant(date.toInstant(), paris))
        }
    }

    @Test
    fun `nonexistent spring civil time is rejected instead of shifted forward`() {
        val local = LocalDateTime.parse("2026-03-29T02:30:00")
        assertFailsWith<IllegalArgumentException> { SessionDateTime.of(local, paris) }
        for (offset in listOf(ZoneOffset.ofHours(1), ZoneOffset.ofHours(2))) {
            assertFailsWith<IllegalArgumentException> { SessionDateTime(local, paris, offset) }
        }
    }

    @Test
    fun `repeated autumn time requires a choice and preserves both possible instants`() {
        val local = LocalDateTime.parse("2026-10-25T02:30:00")
        assertFailsWith<IllegalArgumentException> { SessionDateTime.of(local, paris) }
        val summer = SessionDateTime.of(local, paris, ZoneOffset.ofHours(2))
        val winter = SessionDateTime.of(local, paris, ZoneOffset.ofHours(1))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), summer.toInstant())
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), winter.toInstant())
        assertNotEquals(summer, winter)
        assertEquals(summer, SessionDateTime.fromInstant(summer.toInstant(), paris))
        assertEquals(winter, SessionDateTime.fromInstant(winter.toInstant(), paris))
    }

    @Test
    fun `constructor and copy cannot forge an incompatible offset zone or gap`() {
        val date = SessionDateTime.of(LocalDateTime.parse("2026-07-14T18:00:00"), paris)
        assertFailsWith<IllegalArgumentException> { date.copy(offset = ZoneOffset.ofHours(1)) }
        assertFailsWith<IllegalArgumentException> { date.copy(zoneId = ZoneId.of("Asia/Tokyo")) }
        assertFailsWith<IllegalArgumentException> { date.copy(localDateTime = LocalDateTime.parse("2026-03-29T02:30:00")) }
        assertEquals(date, date.copy())
        assertEquals(date.hashCode(), date.copy().hashCode())
    }

    @Test
    fun `a bare fixed offset cannot replace the original IANA zone`() {
        val local = LocalDateTime.parse("2026-07-14T18:00:00")
        assertFailsWith<IllegalArgumentException> { SessionDateTime.of(local, ZoneOffset.ofHours(2)) }
        assertFailsWith<IllegalArgumentException> { SessionDateTime.fromInstant(Instant.EPOCH, ZoneOffset.UTC) }
        assertEquals("UTC", SessionDateTime.fromInstant(Instant.EPOCH, ZoneId.of("UTC")).zoneId.id)
    }

    @Test
    fun `consultation across midnight preserves original calendar date time zone and offset`() {
        val local = LocalDateTime.parse("2026-07-15T00:30:00")
        val date = SessionDateTime.of(local, paris)
        assertEquals(Instant.parse("2026-07-14T22:30:00Z"), date.toInstant())
        val view = date.atZone(ZoneId.of("America/New_York"))
        assertEquals("2026-07-14T18:30", view.toLocalDateTime().toString())
        assertEquals(date.toInstant(), view.toInstant())
        assertEquals(local, date.localDateTime)
        assertEquals(paris, date.zoneId)
        assertEquals(ZoneOffset.ofHours(2), date.offset)
    }
}
