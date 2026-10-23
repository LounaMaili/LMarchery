package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.profiles.*
import com.lmarchery.feature.sessions.*
import kotlin.test.*

class ConfiguredEditorLifecycleTest {
    @Test fun `draft start first inherited end are separate acknowledged transitions`(): Unit = EditorHarness().use { h ->
        h.draft(); assertEquals(SessionStatus.DRAFT,h.ui.saved!!.status); assertEquals(InitialConfigurationOrigin.CREATION,h.ui.saved!!.initialConfigurationOrigin)
        assertTrue(h.ui.saved!!.ends.isEmpty()); h.c.start(); h.nextEnd()
        assertEquals(EndConfigurationOrigin.Initial,h.ui.selectedEnd!!.origin); assertEquals(3,h.requests.size)
        assertIs<ConfiguredWriteRequest.Create>(h.requests.first()); h.requests.drop(1).forEach { assertIs<ConfiguredWriteRequest.Update>(it) }
    }
    @Test fun `empty invalid title creates no UUID or request`(): Unit = EditorHarness().use { h -> h.c.create(); h.c.confirmForm(h.token)
        assertEquals(0,h.uuids); assertTrue(h.requests.isEmpty()); assertNotNull(h.ui.problem); assertNotNull(h.ui.form) }
    @Test fun `initial compound exact edition independent distance and custom count preserved`(): Unit = EditorHarness().use { h ->
        h.c.create(); h.c.title(h.token,"Poulies"); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.compound40.uuid)
        h.c.distance(h.token,"70"); h.c.count(h.token,"7"); h.c.indoor(h.token,false); h.c.scoring(h.token,ScoringMode.COMPETITION); h.c.confirmForm(h.token)
        val config=h.ui.saved!!.initialConfiguration
        assertEquals("2026-01-27",config.regulatoryEdition); assertEquals(70,config.distanceM); assertEquals(7,config.arrowCountPerEnd)
        assertFalse(config.isIndoor); h.c.start(); h.nextEnd(); h.tap(.075f); assertEquals(9,h.ui.selectedEnd!!.end.impacts.single().scoreFinal)
    }
    @Test fun `classic compound classic sequence never reinterprets previous arrows`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.075f); val first=h.ui.selectedEnd!!; assertEquals(10,first.end.impacts.single().scoreFinal)
        h.c.nextEnd(); h.c.explicitNext(h.token,true); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.compound40.uuid); h.c.distance(h.token,"70"); h.c.confirmForm(h.token)
        h.tap(.075f); assertEquals(9,h.ui.selectedEnd!!.end.impacts.single().scoreFinal)
        h.c.nextEnd(); h.c.explicitNext(h.token,true); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.recurve40.uuid); h.c.confirmForm(h.token); h.tap(.075f)
        assertEquals(listOf(10,9,10),h.ui.saved!!.ends.map { it.end.impacts.single().scoreFinal }); assertEquals(first,h.ui.saved!!.ends.first())
    }
    @Test fun `explicit equal capture followed by inheritance retains distinct provenance`(): Unit = EditorHarness().use { h ->
        h.editing(); val first=h.ui.selectedEnd!!; h.c.nextEnd(); h.c.explicitNext(h.token,true); h.c.confirmForm(h.token)
        val second=h.ui.selectedEnd!!; assertEquals(first.configuration,second.configuration); assertEquals(EndConfigurationOrigin.Explicit,second.origin)
        h.nextEnd(); assertEquals(EndConfigurationOrigin.Inherited(second.uuid),h.ui.selectedEnd!!.origin); assertEquals(first,h.ui.saved!!.ends.first())
    }
    @Test fun `cancel next form creates no end UUID date or provenance`(): Unit = EditorHarness().use { h -> h.editing()
        val before=h.ui.saved; val uuidCount=h.uuids; h.c.nextEnd(); val token=h.token; h.c.explicitNext(token,true); h.c.cancelForm(token); h.c.confirmForm(token)
        assertSame(before,h.ui.saved); assertEquals(uuidCount,h.uuids); assertEquals(3,h.requests.size) }
    @Test fun `stale form callbacks cannot change a newer form`(): Unit = EditorHarness().use { h -> h.editing(); h.c.nextEnd(); val old=h.token; h.c.cancelForm(old); h.c.nextEnd()
        val exact=h.ui.form; h.c.explicitNext(old,true); h.c.title(old,"Old"); h.c.count(old,"99"); h.c.confirmForm(old); assertEquals(exact,h.ui.form) }
    @Test fun `FIXED three six custom and FREE capacities are bounded by actual arrows only`() {
        for (count in listOf(3,6,7)) EditorHarness().use { h -> h.c.create(); h.c.title(h.token,"Capacity"); h.c.count(h.token,count.toString()); h.c.confirmForm(h.token); h.c.start(); h.nextEnd()
            repeat(count+2) { h.tap(.15f) }; assertEquals(count,h.ui.selectedEnd!!.end.impacts.size); assertFalse(h.ui.canAdd) }
        EditorHarness().use { h -> h.c.create(); h.c.title(h.token,"Free"); h.c.shortcut(h.token,EndArrowCountShortcut.FREE); h.c.confirmForm(h.token); h.c.start(); h.nextEnd()
            repeat(9) { h.tap(.15f) }; assertEquals(9,h.ui.selectedEnd!!.end.impacts.size); assertTrue(h.ui.canAdd) }
    }
    @Test fun `huge fixed capacity does not create any synthetic impact or view slot model`(): Unit = EditorHarness().use { h ->
        h.c.create(); h.c.title(h.token,"Large"); h.c.count(h.token,Int.MAX_VALUE.toString()); h.c.confirmForm(h.token); h.c.start(); h.nextEnd()
        assertTrue(h.ui.selectedEnd!!.end.impacts.isEmpty()); h.tap(.15f); assertEquals(1,h.ui.selectedEnd!!.end.impacts.size); assertTrue(h.ui.canAdd) }
    @Test fun `empty or partially filled fixed end can be validated by current domain`() {
        for (n in 0..1) EditorHarness().use { h -> h.editing(); repeat(n) { h.tap(.15f) }; h.c.validateEnd()
            assertEquals(EndStatus.VALIDATED,h.ui.selectedEnd!!.end.status); val before=h.requests.size; val ctx=h.ctx
            h.c.tapped(ctx,NormalizedPoint(.1f,0f)); h.c.selectImpact(ctx,f.id(1)); h.c.beginReposition(ctx); h.c.validateEnd()
            assertEquals(before,h.requests.size); h.nextEnd(); assertEquals(2,h.ui.saved!!.ends.size) }
    }
    @Test fun `completion freezes provisional ends and metadata without auto validation`(): Unit = EditorHarness().use { h -> h.editing(); h.tap(.15f); h.c.complete()
        assertEquals(SessionStatus.COMPLETED,h.ui.saved!!.status); assertEquals(EndStatus.EDITING,h.ui.selectedEnd!!.end.status)
        val before=h.requests.size; h.c.nextEnd(); h.c.editMetadata(); h.c.start(); h.c.complete(); h.tap(.25f); h.c.validateEnd(); assertEquals(before,h.requests.size) }
    @Test fun `metadata preserves civilian date whitespace optional empty strings and opaque references`(): Unit = EditorHarness(f.mixed()).use { h -> h.open(); val original=h.ui.saved!!.metadata
        h.c.editMetadata(); h.c.title(h.token,"  Title 🏹  "); h.c.notes(h.token,""); h.c.planned(h.token,"20"); h.c.confirmForm(h.token)
        val actual=h.ui.saved!!.metadata; assertEquals(original.date,actual.date); assertEquals(original.bowUuid,actual.bowUuid); assertEquals(original.arrowSetUuid,actual.arrowSetUuid)
        assertEquals("",actual.notes); assertEquals(20,actual.plannedEndCount); assertEquals("  Title 🏹  ",actual.title) }
    @Test fun `backdated transition does not issue an SQL request or discard form`(): Unit = EditorHarness(f.mixed()).use { h -> h.open(); h.c.editMetadata(); h.c.title(h.token,"Changed"); h.now=f.now.minusSeconds(1); h.c.confirmForm(h.token)
        assertTrue(h.requests.isEmpty()); assertNotNull(h.ui.form); assertEquals(ConfiguredProblemKind.VALIDATION,h.ui.problem!!.kind) }
    @Test fun `legacy rows explicitly refused rather than projected into editable configured sessions`(): Unit = EditorHarness().use { h -> h.readOverride=ConfiguredSessionReadResult.LegacyHomogeneous; h.c.open(f.id(1))
        assertNull(h.ui.saved); assertEquals(ConfiguredProblemKind.NOT_CONFIGURED,h.ui.problem!!.kind); h.c.start(); assertTrue(h.requests.isEmpty()) }
    @Test fun `editing owns common gate until explicit close including local forms`(): Unit = EditorHarness().use { h -> h.c.create(); assertTrue(h.gate); h.c.close(); assertTrue(h.gate)
        h.c.abandonLocalAndClose(); assertFalse(h.gate); assertEquals(1,h.releases); assertEquals(1,h.refreshes) }
    @Test fun `busy shared gate refuses entry without acquiring a second owner`(): Unit = EditorHarness(acquired=false).use { h -> h.c.create(); h.c.open(f.id(1)); assertFalse(h.ui.open); assertEquals(0,h.reads); assertEquals(0,h.releases) }
}
