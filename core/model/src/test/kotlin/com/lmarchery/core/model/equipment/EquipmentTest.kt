package com.lmarchery.core.model.equipment

import java.time.Instant
import kotlin.test.*

class EquipmentTest {
    private val now = Instant.parse("2026-10-17T10:00:00.123456789Z")
    private val id = "70000000-0000-4000-8000-000000000001"
    private fun bow(uuid: String = id, name: String = " Arc ", weight: Float? = null,
        created: Instant = now, updated: Instant = now) = Bow(uuid, name, drawWeightLbs = weight, createdAt = created, updatedAt = updated)
    private fun arrows(uuid: String = id, name: String = " Flèches ", created: Instant = now, updated: Instant = now) =
        ArrowSet(uuid, name, createdAt = created, updatedAt = updated)

    @Test fun `minimal values retain identity nanoseconds and optional nulls`() {
        assertEquals(now, bow().createdAt)
        assertEquals(now.nano, arrows().updatedAt.nano)
        assertNull(bow().drawWeightLbs)
        assertNull(arrows().length)
        assertEquals(" Arc ", bow().name)
    }
    @Test fun `all descriptive fields are retained verbatim without unit inference`() {
        val bow = Bow(id, " Arc 🏹 ", " custom division ", 35.25f, "", " ", "\nnotes", now, now)
        assertEquals(" custom division ", bow.type)
        assertEquals("", bow.brand)
        assertEquals(" ", bow.model)
        val arrows = ArrowSet(id, "Jeu", "500 / 1916", "28 1/2 pouces", "100 gr?", "", " ", "bois/carbone", "\n", now, now)
        assertEquals("500 / 1916", arrows.spine)
        assertEquals("28 1/2 pouces", arrows.length)
        assertEquals("100 gr?", arrows.pointWeight)
        assertEquals("", arrows.nock)
        assertEquals(" ", arrows.vanes)
        assertEquals(arrows, arrows.copy())
        assertEquals(bow, bow.copy())
    }
    @Test fun `blank names are rejected for both kinds`() {
        listOf("", " ", "\n\t").forEach { name ->
            assertFailsWith<IllegalArgumentException> { bow(name = name) }
            assertFailsWith<IllegalArgumentException> { arrows(name = name) }
        }
    }
    @Test fun `UUID must be canonical lowercase v4 variant 2 for both kinds`() {
        listOf("", "not-uuid", "7-0-4-8-1", id.replace("4000", "1000"), id.replace("8000", "0000"),
            "AAAAAAAA-0000-4000-8000-000000000001").forEach { uuid ->
            assertFailsWith<IllegalArgumentException> { bow(uuid = uuid) }
            assertFailsWith<IllegalArgumentException> { arrows(uuid = uuid) }
        }
    }
    @Test fun `draw weight rejects zero negative NaN and infinities`() {
        listOf(0f, -0.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { bow(weight = it) }
        }
    }
    @Test fun `finite positive weight extrema and fractional pounds are valid`() {
        listOf(Float.MIN_VALUE, 0.5f, 35.125f, Float.MAX_VALUE).forEach { value ->
            assertEquals(value.toRawBits(), bow(weight = value).drawWeightLbs!!.toRawBits())
        }
    }
    @Test fun `construction rejects inverted chronology for both kinds`() {
        assertFailsWith<IllegalArgumentException> { bow(updated = now.minusNanos(1)) }
        assertFailsWith<IllegalArgumentException> { arrows(updated = now.minusNanos(1)) }
    }
    @Test fun `copies preserve original values and content equality with consistent hashes`() {
        val oldBow = bow()
        val oldArrows = arrows()
        val nextBow = oldBow.copy(name = "New", updatedAt = now.plusNanos(1))
        val nextArrows = oldArrows.copy(spine = "600", updatedAt = now.plusNanos(1))
        assertEquals(bow(), oldBow)
        assertEquals(arrows(), oldArrows)
        assertNotEquals(oldBow, nextBow)
        assertNotEquals(oldArrows, nextArrows)
        assertEquals(oldBow.uuid, nextBow.uuid)
        assertEquals(oldArrows.createdAt, nextArrows.createdAt)
        assertEquals(oldBow.hashCode(), bow().hashCode())
        assertEquals(oldArrows.hashCode(), arrows().hashCode())
        assertNotEquals<Equipment>(oldBow, oldArrows)
    }
    @Test fun `copy cannot change UUID or creation even to independently legal values`() {
        val other = id.replace("000001", "000002")
        assertFailsWith<IllegalArgumentException> { bow().copy(uuid = other) }
        assertFailsWith<IllegalArgumentException> { arrows().copy(uuid = other) }
        assertFailsWith<IllegalArgumentException> { bow().copy(createdAt = now.minusNanos(1)) }
        assertFailsWith<IllegalArgumentException> { arrows().copy(createdAt = now.minusNanos(1)) }
    }
    @Test fun `copy cannot backdate a revision even after creation`() {
        val later = now.plusSeconds(600)
        assertFailsWith<IllegalArgumentException> { bow(updated = later).copy(updatedAt = now.plusSeconds(300)) }
        assertFailsWith<IllegalArgumentException> { arrows(updated = later).copy(updatedAt = now.plusSeconds(300)) }
    }
    @Test fun `equal modification timestamps are permitted without artificial clock increment`() {
        assertEquals(now, bow().copy(notes = "A").updatedAt)
        assertEquals(now, arrows().copy(length = "700 mm").updatedAt)
    }
    @Test fun `copy revalidates names weights and optional fields can be cleared`() {
        assertFailsWith<IllegalArgumentException> { bow().copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { arrows().copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { bow().copy(drawWeightLbs = Float.NaN) }
        assertNull(bow(weight = 30f).copy(drawWeightLbs = null).drawWeightLbs)
        assertNull(arrows().copy(length = "28").copy(length = null).length)
    }
    @Test fun `independent reconstruction must pass contextual revision validation`() {
        val previous = bow(updated = now.plusSeconds(2))
        assertFailsWith<IllegalArgumentException> { validateEquipmentUpdate(previous, bow()) }
        assertFailsWith<IllegalArgumentException> { validateEquipmentUpdate(previous, bow(created = now.minusSeconds(1), updated = previous.updatedAt)) }
        assertFailsWith<IllegalArgumentException> { validateEquipmentUpdate(previous, arrows(updated = previous.updatedAt)) }
        validateEquipmentUpdate(previous, previous.copy(name = "Revised"))
    }
    @Test fun `equality includes every optional property and timestamps`() {
        val bow = bow()
        listOf(bow.copy(type = "t"), bow.copy(drawWeightLbs = 1f), bow.copy(brand = "b"), bow.copy(model = "m"),
            bow.copy(notes = "n"), bow.copy(updatedAt = now.plusNanos(1))).forEach { assertNotEquals(bow, it) }
        val arrows = arrows()
        listOf(arrows.copy(spine = "s"), arrows.copy(length = "l"), arrows.copy(pointWeight = "p"), arrows.copy(nock = "n"),
            arrows.copy(vanes = "v"), arrows.copy(material = "m"), arrows.copy(notes = "n"), arrows.copy(updatedAt = now.plusNanos(1)))
            .forEach { assertNotEquals(arrows, it) }
    }
}
