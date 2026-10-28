package com.lmarchery.feature.equipment

import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.arrows
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.model.equipment.*
import com.lmarchery.data.EquipmentKind
import kotlin.test.*

class EquipmentDraftTest {
    @Test fun `unchanged draft preserves exact value and Float bits without creating revision`() {
        listOf(bow(), arrows(), bow().copy(drawWeightLbs = Float.MIN_VALUE), bow().copy(drawWeightLbs = Float.MAX_VALUE)).forEach { original ->
            assertSame(original, EquipmentDraft.from(original).revision(original, now.minusSeconds(1)) { error("No new UUID") })
        }
    }
    @Test fun `null empty Unicode whitespace and raw units remain distinct in both directions`() {
        val d = EquipmentDraft(EquipmentKind.ARROW_SET, " Nom 🏹 ", spine = "", length = " 28 pouces ? ", point = "100 gr ?", notes = "\n é ")
        val a = assertIs<ArrowSet>(d.revision(null, now) { arrows().uuid })
        assertEquals(d, EquipmentDraft.from(a)); assertEquals("", a.spine); assertNull(a.nock)
        assertEquals(" 28 pouces ? ", a.length); assertEquals(" Nom 🏹 ", a.name)
    }
    @Test fun `optional fields can explicitly switch between null and empty without defaults`() {
        val draft = EquipmentDraft(EquipmentKind.BOW).withField(EquipmentField.BRAND, "")
        assertEquals("", draft.brand); assertNull(draft.withField(EquipmentField.BRAND, null).brand)
        assertNull(draft.power); assertFailsWith<IllegalArgumentException> { draft.withField(EquipmentField.SPINE, "500") }
    }
    @Test fun `invalid names and powers are rejected without inventing a number`() {
        listOf("", "  ", "\n").forEach { name -> assertFailsWith<IllegalArgumentException> {
            EquipmentDraft(EquipmentKind.BOW, name).revision(null, now) { bow().uuid }
        } }
        listOf("", "abc", "NaN", "Infinity", "-Infinity", "0", "-0.0", "-1", "1e999", "1e-999", "2,5").forEach { power ->
            assertFailsWith<IllegalArgumentException>(power) { EquipmentDraft(EquipmentKind.BOW, "Arc", power = power).revision(null, now) { bow().uuid } }
        }
    }
    @Test fun `revision preserves identity and creation with explicit monotone timestamp`() {
        val old = bow(); val d = EquipmentDraft.from(old).copy(name = "Nouveau", power = null)
        val next = assertIs<Bow>(d.revision(old, now.plusNanos(1)) { error("No new UUID") })
        assertEquals(old.uuid, next.uuid); assertEquals(old.createdAt, next.createdAt)
        assertEquals(now.plusNanos(1), next.updatedAt); assertNull(next.drawWeightLbs)
        assertFailsWith<IllegalArgumentException> { d.revision(old, now.minusNanos(1)) { error("No new UUID") } }
    }
    @Test fun `wrong equipment kind cannot be revised through another form`() {
        assertFailsWith<IllegalArgumentException> { EquipmentDraft(EquipmentKind.BOW, "Arc").revision(arrows(), now) { bow().uuid } }
        assertFailsWith<IllegalArgumentException> { EquipmentDraft(EquipmentKind.ARROW_SET, "Flèches").revision(bow(), now) { arrows().uuid } }
    }
}
