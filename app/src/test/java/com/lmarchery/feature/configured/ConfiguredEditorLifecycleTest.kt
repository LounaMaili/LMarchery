package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.profiles.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import kotlin.test.*

class ConfiguredEditorLifecycleTest {
    @Test
    fun `quick start applies a suggestion and the initial end in one acknowledged create`() {
        EditorHarness().use { editor ->
            val c = editor.c
            c.create()
            val token = editor.token
            c.applySuggestion(token, SessionSuggestion.INDOOR_COMPOUND)
            c.distance(token, "20")
            assertTrue(editor.requests.isEmpty())

            val now = editor.now
            val acknowledgement = CompletableDeferred<Unit>()
            editor.hold = acknowledgement
            c.createAndStart(token)
            c.createAndStart(token)
            c.confirmForm(token)
            assertNull(editor.ui.saved)
            val request = assertIs<ConfiguredWriteRequest.Create>(editor.requests.single())
            val session = request.proposed
            assertEquals(SessionStatus.IN_PROGRESS, session.status)
            assertEquals(InitialConfigurationOrigin.CREATION, session.initialConfigurationOrigin)
            assertEquals(now, session.createdAt)
            assertEquals(now, session.startedAt)
            assertEquals(now, session.updatedAt)
            assertEquals("Séance à 20 m · ${WorldArcheryIndoorTargetProfiles.compound40.name}", session.metadata.title)
            assertEquals(SessionSuggestion.INDOOR_COMPOUND.configuration.configuration().copy(distanceM = 20), session.initialConfiguration)
            val first = session.ends.single()
            assertEquals(1, first.end.index)
            assertEquals(EndConfigurationOrigin.Initial, first.origin)
            assertEquals(session.initialConfiguration, first.configuration)
            assertEquals(now, first.end.createdAt)
            assertTrue(first.end.impacts.isEmpty())
            assertEquals(2, editor.uuids)
            acknowledgement.complete(Unit)
            assertEquals(session, editor.ui.saved)
            assertEquals(first, editor.ui.selectedEnd)
            c.createAndStart(token)
            assertEquals<ConfiguredWriteRequest>(request, editor.requests.single())
        }
    }

    @Test
    fun `uncertain quick start keeps a typed title and reconciles exact content without replay`() {
        EditorHarness().use { editor ->
            val c = editor.c
            c.create()
            val token = editor.token
            c.title(token, " Entraînement personnel ")
            editor.write = {
                editor.stored = it.proposed
                ConfiguredWriteResult.CommitUnconfirmed(it, IOException("Synthetic lost acknowledgement"))
            }
            c.createAndStart(token)
            val request = assertIs<ConfiguredWriteRequest.Create>(editor.requests.single())
            assertEquals(" Entraînement personnel ", request.proposed.metadata.title)
            assertEquals(request, editor.ui.pending?.request)
            assertNull(editor.ui.saved)
            c.createAndStart(token)
            c.start()
            c.reconcile()
            assertEquals(request.proposed, editor.ui.saved)
            assertNull(editor.ui.pending)
            assertEquals<ConfiguredWriteRequest>(request, editor.requests.single())
            assertEquals(2, editor.uuids)
        }
    }

    @Test
    fun `an invalid quick form and a callback from a closed form submit neither draft nor start`() {
        EditorHarness().use { editor ->
            val c = editor.c
            c.create()
            val invalid = editor.token
            c.distance(invalid, "0")
            c.createAndStart(invalid)
            assertEquals(ConfiguredProblemKind.VALIDATION, editor.ui.problem?.kind)
            c.abandonLocalAndClose()
            c.create()
            c.createAndStart(invalid)
            assertEquals(0, editor.uuids)
            assertTrue(editor.requests.isEmpty())
            assertNull(editor.ui.saved)
        }
    }

    @Test fun `starting a saved draft includes one initial end in the same update`(): Unit = EditorHarness().use { h ->
        h.draft(); assertEquals(SessionStatus.DRAFT,h.ui.saved!!.status); assertEquals(InitialConfigurationOrigin.CREATION,h.ui.saved!!.initialConfigurationOrigin)
        val draft = h.ui.saved!!
        assertTrue(draft.ends.isEmpty())
        h.c.start()
        h.c.start()
        assertEquals(2, h.requests.size)
        assertIs<ConfiguredWriteRequest.Create>(h.requests.first())
        val started = assertIs<ConfiguredWriteRequest.Update>(h.requests.last())
        assertEquals(draft, started.expected)
        assertEquals(SessionStatus.IN_PROGRESS, started.proposed.status)
        assertEquals(EndConfigurationOrigin.Initial, started.proposed.ends.single().origin)
        assertEquals(draft.initialConfiguration, h.ui.selectedEnd!!.configuration)
        assertEquals(started.proposed.startedAt, h.ui.selectedEnd!!.end.createdAt)
    }
    @Test fun `empty invalid title creates no UUID or request`(): Unit = EditorHarness().use { h -> h.c.create(); h.c.confirmForm(h.token)
        assertEquals(0,h.uuids); assertTrue(h.requests.isEmpty()); assertNotNull(h.ui.problem); assertNotNull(h.ui.form) }
    @Test fun `initial compound exact edition independent distance and custom count preserved`(): Unit = EditorHarness().use { h ->
        h.c.create(); h.c.title(h.token,"Poulies"); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.compound40.uuid)
        h.c.distance(h.token,"70"); h.c.count(h.token,"7"); h.c.indoor(h.token,false); h.c.scoring(h.token,ScoringMode.COMPETITION); h.c.confirmForm(h.token)
        val config=h.ui.saved!!.initialConfiguration
        assertEquals("2026-01-27",config.regulatoryEdition); assertEquals(70,config.distanceM); assertEquals(7,config.arrowCountPerEnd)
        assertFalse(config.isIndoor); h.c.start(); h.tap(.075f); assertEquals(9,h.ui.selectedEnd!!.end.impacts.single().scoreFinal)
    }
    @Test fun `classic compound classic sequence never reinterprets previous arrows`(): Unit = EditorHarness().use { h ->
        h.editing(); h.tap(.075f); val first=h.ui.selectedEnd!!; assertEquals(10,first.end.impacts.single().scoreFinal)
        h.c.editNextEnd(h.ui.epoch); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.compound40.uuid); h.c.distance(h.token,"70"); h.c.confirmForm(h.token)
        h.tap(.075f); assertEquals(9,h.ui.selectedEnd!!.end.impacts.single().scoreFinal)
        h.c.editNextEnd(h.ui.epoch); h.c.chooseProfile(h.token,WorldArcheryIndoorTargetProfiles.recurve40.uuid); h.c.confirmForm(h.token); h.tap(.075f)
        assertEquals(listOf(10,9,10),h.ui.saved!!.ends.map { it.end.impacts.single().scoreFinal }); assertEquals(first,h.ui.saved!!.ends.first())
    }
    @Test fun `explicit equal capture followed by inheritance retains distinct provenance`(): Unit = EditorHarness().use { h ->
        h.editing(); val first=h.ui.selectedEnd!!; h.c.editNextEnd(h.ui.epoch); h.c.confirmForm(h.token)
        val second=h.ui.selectedEnd!!; assertEquals(first.configuration,second.configuration); assertEquals(EndConfigurationOrigin.Explicit,second.origin)
        h.nextEnd(); assertEquals(EndConfigurationOrigin.Inherited(second.uuid),h.ui.selectedEnd!!.origin); assertEquals(first,h.ui.saved!!.ends.first())
    }
    @Test fun `cancel next form creates no end UUID date or provenance`(): Unit = EditorHarness().use { h -> h.editing()
        val before=h.ui.saved; val uuidCount=h.uuids; h.c.editNextEnd(h.ui.epoch); val token=h.token; h.c.cancelForm(token); h.c.confirmForm(token)
        assertSame(before,h.ui.saved); assertEquals(uuidCount,h.uuids); assertEquals(2,h.requests.size) }
    @Test fun `stale form callbacks cannot change a newer form`(): Unit = EditorHarness().use { h -> h.editing(); h.c.editNextEnd(h.ui.epoch); val old=h.token; h.c.cancelForm(old); h.c.editNextEnd(h.ui.epoch)
        val exact=h.ui.form; h.c.distance(old,"90"); h.c.title(old,"Old"); h.c.count(old,"99"); h.c.confirmForm(old); assertEquals(exact,h.ui.form) }
    @Test fun `FIXED three six custom and FREE capacities are bounded by actual arrows only`() {
        for (count in listOf(3,6,7)) EditorHarness().use { h -> h.c.create(); h.c.title(h.token,"Capacity"); h.c.count(h.token,count.toString()); h.c.confirmForm(h.token); h.c.start()
            repeat(count+2) { h.tap(.15f) }; assertEquals(count,h.ui.selectedEnd!!.end.impacts.size); assertFalse(h.ui.canAdd) }
        EditorHarness().use { h -> h.c.create(); h.c.title(h.token,"Free"); h.c.shortcut(h.token,EndArrowCountShortcut.FREE); h.c.confirmForm(h.token); h.c.start()
            repeat(9) { h.tap(.15f) }; assertEquals(9,h.ui.selectedEnd!!.end.impacts.size); assertTrue(h.ui.canAdd) }
    }
    @Test fun `huge fixed capacity does not create any synthetic impact or view slot model`(): Unit = EditorHarness().use { h ->
        h.c.create(); h.c.title(h.token,"Large"); h.c.count(h.token,Int.MAX_VALUE.toString()); h.c.confirmForm(h.token); h.c.start()
        assertTrue(h.ui.selectedEnd!!.end.impacts.isEmpty()); h.tap(.15f); assertEquals(1,h.ui.selectedEnd!!.end.impacts.size); assertTrue(h.ui.canAdd) }
    @Test fun `empty or partially filled fixed end can be validated by current domain`() {
        for (n in 0..1) EditorHarness().use { h -> h.editing(); repeat(n) { h.tap(.15f) }; h.c.validateEnd()
            assertEquals(EndStatus.VALIDATED,h.ui.selectedEnd!!.end.status); val before=h.requests.size; val ctx=h.ctx
            h.c.tapped(ctx,NormalizedPoint(.1f,0f)); h.c.selectImpact(ctx,f.id(1)); h.c.beginReposition(ctx); h.c.validateEnd()
            assertEquals(before,h.requests.size); h.nextEnd(); assertEquals(2,h.ui.saved!!.ends.size) }
    }
    @Test fun `early completion preserves validated and provisional ends with one acknowledged update`() = EditorHarness().use { h ->
        h.c.create()
        h.c.title(h.token, "Entraînement")
        h.c.planned(h.token, "10")
        h.c.confirmForm(h.token)
        h.c.start()
        h.tap(.15f)
        h.c.validateEnd()
        h.nextEnd()
        h.tap(.25f)
        val active = h.ui.saved!!
        assertEquals(listOf(EndStatus.VALIDATED, EndStatus.EDITING), active.ends.map { it.end.status })

        val acknowledged = CompletableDeferred<Unit>()
        h.hold = acknowledged
        val count = h.requests.size
        h.c.complete()
        h.c.complete()
        assertEquals(count + 1, h.requests.size)
        assertEquals(active, h.ui.saved)
        val request = assertIs<ConfiguredWriteRequest.Update>(h.requests.last())
        assertEquals(active, request.expected)
        assertEquals(SessionStatus.COMPLETED, request.proposed.status)
        assertEquals(10, request.proposed.metadata.plannedEndCount)
        assertEquals(active.ends, request.proposed.ends)
        acknowledged.complete(Unit)
        assertEquals(request.proposed, h.ui.saved)
        h.c.nextEnd(h.ui.epoch)
        h.c.editMetadata()
        h.c.start()
        h.c.complete()
        h.tap(.25f)
        h.c.validateEnd()
        assertEquals(count + 1, h.requests.size)
    }
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
    @Test fun `direct next end inherits the latest end even when viewing an earlier provisional end`() = EditorHarness().use { h ->
        h.editing()
        val first = h.ui.selectedEnd!!
        h.c.editNextEnd(h.ui.epoch)
        val token = h.token
        h.c.chooseProfile(token, WorldArcheryIndoorTargetProfiles.compound40.uuid)
        h.c.distance(token, "70")
        h.c.shortcut(token, EndArrowCountShortcut.FREE)
        h.c.indoor(token, false)
        h.c.scoring(token, ScoringMode.COMPETITION)
        val displayed = (h.ui.form as ConfiguredForm.NextEnd).configuration.configuration()
        h.c.confirmForm(token)
        val explicit = h.ui.selectedEnd!!
        assertEquals(EndConfigurationOrigin.Explicit, explicit.origin)
        assertEquals(displayed, explicit.configuration)

        h.c.selectEnd(first.uuid)
        h.c.editNextEnd(h.ui.epoch)
        assertEquals(displayed, (h.ui.form as ConfiguredForm.NextEnd).configuration.configuration())
        h.c.cancelForm(h.token)
        val before = h.requests.size
        val click = h.ui.epoch
        h.c.nextEnd(click)
        h.c.nextEnd(click)
        assertEquals(before + 1, h.requests.size)
        assertNull(h.ui.form)
        assertEquals(EndConfigurationOrigin.Inherited(explicit.uuid), h.ui.selectedEnd!!.origin)
        assertEquals(displayed, h.ui.selectedEnd!!.configuration)
        assertTrue(h.ui.selectedEnd!!.end.impacts.isEmpty())
        assertEquals(listOf(first, explicit), h.ui.saved!!.ends.take(2))
    }

    @Test fun `open an older empty active session without a write then recover a lost first end acknowledgement`() {
        val fixture = EditorHarness().use { h ->
            h.draft()
            h.ui.saved!!.start(h.now)
        }
        EditorHarness(fixture).use { h ->
            h.open()
            assertEquals(fixture, h.ui.saved)
            assertNull(h.ui.selectedEnd)
            assertTrue(h.requests.isEmpty())
            h.write = {
                h.stored = it.proposed
                ConfiguredWriteResult.CommitUnconfirmed(it, IOException("Synthetic lost acknowledgement"))
            }
            val click = h.ui.epoch
            h.c.nextEnd(click)
            h.c.nextEnd(click)
            assertEquals(fixture, h.ui.saved)
            val request = assertIs<ConfiguredWriteRequest.Update>(h.requests.single())
            assertEquals(fixture, request.expected)
            assertEquals(EndConfigurationOrigin.Initial, request.proposed.ends.single().origin)
            h.c.reconcile()
            assertEquals(request.proposed.ends.single(), h.ui.selectedEnd)
            h.c.nextEnd(click)
            assertEquals(1, h.requests.size)
        }
    }

    @Test fun `restored historical provenance permits active editing but completed roots remain read only`() {
        val converted = assertIs<com.lmarchery.core.scoring.archive.v3.ArchiveV3Reconstruction.Accepted>(
            com.lmarchery.core.scoring.archive.v3.convertArchiveV2ToV3(com.lmarchery.backup.V2TestData.archive())).archive.sessions.single()
        assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, converted.initialConfigurationOrigin)
        for (completed in listOf(false, true)) {
            val restored = if (completed) converted.complete(f.now) else converted
            EditorHarness(restored).use { h ->
                h.open(); assertEquals(!completed, h.ui.canForm); h.c.editMetadata()
                if (completed) { assertNull(h.ui.form); assertTrue(h.requests.isEmpty()); assertEquals(restored, h.ui.saved) }
                else {
                    h.c.title(h.token, "Restored configurable title"); h.c.confirmForm(h.token)
                    assertEquals("Restored configurable title", h.ui.saved!!.metadata.title)
                    assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, h.ui.saved!!.initialConfigurationOrigin)
                    assertEquals(restored.ends, h.ui.saved!!.ends); assertEquals(restored.metadata.date, h.ui.saved!!.metadata.date)
                    assertIs<ConfiguredWriteRequest.Update>(h.requests.single())
                }
            }
        }
    }

}
