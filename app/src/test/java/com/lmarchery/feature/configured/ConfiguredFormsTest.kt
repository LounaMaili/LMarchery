package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.model.session.*
import com.lmarchery.feature.sessions.*
import kotlin.test.*

class ConfiguredFormsTest {
    @Test fun `invalid distances and counts reject empty zero negatives fractions nonfinite and integer overflow`() {
        val base=ConfigurationDraft()
        for (text in listOf("","0","-1","1.5","NaN","Infinity","2147483648"," 18 ")) {
            assertFailsWith<IllegalArgumentException>(text) { base.copy(distance=text).configuration() }
            assertFailsWith<IllegalArgumentException>(text) { base.copy(fixedCount=text).configuration() }
        }
    }
    @Test fun `FREE ignores disabled stale count text while FIXED retains exact custom capacity`() {
        assertNull(ConfigurationDraft(arrowCountMode=ArrowCountMode.FREE,fixedCount="not a count").configuration().arrowCountPerEnd)
        assertEquals(11,ConfigurationDraft(fixedCount="11").configuration().arrowCountPerEnd)
    }
    @Test fun `draft reconstruction preserves full profile edition distance and immutable original`() {
        val before=f.compound
        assertEquals(before,ConfigurationDraft.from(before).configuration())
        val after=ConfigurationDraft.from(before).copy(distance="25").configuration()
        assertEquals(70,before.distanceM);assertEquals(25,after.distanceM);assertEquals(before.targetType,after.targetType);assertEquals(before.regulatoryEdition,after.regulatoryEdition)
    }
    @Test fun `metadata preserves every string and absent field rather than normalizing blanks`() {
        val old=f.mixed().metadata
        assertEquals(old,ConfiguredMetadataDraft.from(old).metadata(old.date))
        val value=ConfiguredMetadataDraft(" é 🏹 ","",location="",notes=" \n",equipment=EquipmentSelection(old.bowUuid,old.arrowSetUuid)).metadata(old.date)
        assertEquals("",value.locationName);assertEquals(" \n",value.notes);assertNull(value.plannedEndCount);assertEquals(old.date,value.date)
    }
    @Test fun `planned count must be omitted or strictly positive in range`() {
        val old=f.mixed().metadata
        for (text in listOf("0","-1","1.5","NaN","2147483648"," ")) assertFailsWith<IllegalArgumentException> { ConfiguredMetadataDraft("Title",text).metadata(old.date) }
        assertEquals(Int.MAX_VALUE,ConfiguredMetadataDraft("Title",Int.MAX_VALUE.toString()).metadata(old.date).plannedEndCount)
    }
    @Test fun `selection context incorporates session end impact and epoch`(): Unit = EditorHarness().use { h ->
        h.editing(); val first=h.ctx; h.tap(.15f); assertNotEquals(first,h.ctx)
        val after=h.ctx; h.selectFirst(); assertNotEquals(after,h.ctx); assertNotNull(h.ctx.owner.impactUuid)
        h.closeSelection(); assertNull(h.ctx.owner.impactUuid)
    }
    @Test fun `abandon candidate never changes confirmed mixed score presentation`(): Unit = EditorHarness(f.mixed()).use { h ->
        h.open(); val before=presentConfiguredScore(h.ui.saved!!); h.c.selectEnd(h.ui.saved!!.ends[1].uuid); h.selectFirst(); h.c.beginReposition(h.ctx)
        h.c.repositionPoint(h.ctx,com.lmarchery.core.model.NormalizedPoint(.95f,0f)); h.closeSelection()
        assertEquals(before,presentConfiguredScore(h.ui.saved!!));assertTrue(h.requests.isEmpty())
    }
    @Test fun `three six free shortcuts do not silently change any other configuration field`(): Unit = EditorHarness().use { h ->
        h.c.create(); val base=(h.ui.form as ConfiguredForm.Creation).configuration
        for (shortcut in EndArrowCountShortcut.entries) {
            h.c.shortcut(h.token,shortcut); val now=(h.ui.form as ConfiguredForm.Creation).configuration
            assertEquals(base.profile,now.profile);assertEquals(base.distance,now.distance);assertEquals(base.indoor,now.indoor);assertEquals(base.scoringMode,now.scoringMode)
            assertEquals(shortcut.arrowCountMode,now.arrowCountMode);assertEquals(shortcut.arrowCountPerEnd,now.configuration().arrowCountPerEnd)
        }
    }
}
