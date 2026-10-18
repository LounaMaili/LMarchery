package com.lmarchery.feature.equipment

import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.arrows
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.*
import com.lmarchery.data.*
import com.lmarchery.feature.sessions.*
import com.lmarchery.core.scoring.archive.DomainArchive
import kotlinx.coroutines.*
import java.io.IOException
import java.time.*
import kotlin.test.*

class EquipmentControllerTest {
    private inner class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val values = linkedMapOf<String, Equipment>(bow().uuid to bow(), arrows().uuid to arrows())
        var gate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        var createResult: EquipmentCreateResult? = null; var updateResult: EquipmentUpdateResult? = null
        var readResult: EquipmentReadResult<Equipment>? = null; var discoveryFailure = false
        var locked = false; var allowed = true; var releases = 0; var sequence = 20
        val writes = mutableListOf<EquipmentRevision>(); var reads = 0
        val repository = object : EquipmentRepository {
            override suspend fun discover(kind: EquipmentKind) = if (discoveryFailure) EquipmentDiscoveryResult.TechnicalFailure(IOException("Synthetic"))
                else EquipmentDiscoveryResult.Found(values.values.filter { it.kind() == kind }.map { it.uuid })
            override suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment> {
                reads++; readGate?.await(); return readResult ?: values[uuid]?.let { EquipmentReadResult.Found(it) } ?: EquipmentReadResult.NotFound
            }
            override suspend fun create(value: Equipment): EquipmentCreateResult {
                writes += EquipmentRevision(null, value); gate?.await(); createResult?.let { return it }
                if (values[value.uuid] == value) return EquipmentCreateResult.AlreadyExists(value.uuid)
                if (value.uuid in values) return EquipmentCreateResult.Conflict(StorageDiagnostic("equipment", "Collision"))
                values[value.uuid] = value; return EquipmentCreateResult.Created(value.uuid)
            }
            override suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult {
                writes += EquipmentRevision(expected, proposed); gate?.await(); updateResult?.let { return it }
                if (values[expected.uuid] != expected) return EquipmentUpdateResult.Conflict(StorageDiagnostic("equipment", "Stale"))
                values[expected.uuid] = proposed; return EquipmentUpdateResult.Updated(proposed.uuid)
            }
        }
        val controller = EquipmentController(repository, scope, { if (!allowed || locked) false else { locked = true; true } },
            { locked = false; releases++ }, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC),
            { "61000000-0000-4000-8000-${(sequence++).toString().padStart(12, '0')}" })
        val state get() = controller.state.value
        fun newBow() { controller.open(); controller.add(EquipmentKind.BOW); controller.setField(EquipmentField.NAME, "Arc synthétique") }
        fun editBow() { controller.open(); controller.show(EquipmentKind.BOW, bow().uuid); controller.edit(); controller.setField(EquipmentField.NAME, "Arc révisé") }
        override fun close() = scope.cancel()
    }
    @Test fun `discovery and detail read both categories with no writes and close releases gate`() {
        Harness().use { h -> h.controller.open(); assertTrue(h.locked); assertEquals(2, h.state.entries.size)
            h.controller.show(EquipmentKind.BOW, bow().uuid); assertEquals(bow(), h.state.original); assertEquals(EquipmentPage.DETAIL, h.state.page)
            h.controller.list(); h.controller.close(); assertFalse(h.state.open); assertFalse(h.locked); assertEquals(1, h.releases); assertTrue(h.writes.isEmpty()) }
    }
    @Test fun `new bow remains a draft until one explicit confirmation and SQL acknowledgement`() = runBlocking<Unit> {
        Harness().use { h -> h.newBow(); assertNull(h.state.original); assertEquals(0, h.writes.size)
            h.gate = CompletableDeferred(); h.controller.save(); assertTrue(h.state.busy); assertNotNull(h.state.pending); assertNull(h.state.original)
            h.controller.save(); h.controller.add(EquipmentKind.ARROW_SET); h.controller.close(); assertTrue(h.state.open); assertEquals(1, h.writes.size)
            h.gate!!.complete(Unit); yield(); assertEquals("Arc synthétique", h.state.original!!.name)
            assertNull(h.state.pending); assertEquals(EquipmentPage.DETAIL, h.state.page); assertNotNull(h.state.confirmation) }
    }
    @Test fun `arrow set create edit optional fields and repeated name retain separate UUIDs`() {
        Harness().use { h -> h.controller.open(); h.controller.add(EquipmentKind.ARROW_SET)
            h.controller.setField(EquipmentField.NAME, arrows().name); h.controller.setField(EquipmentField.SPINE, "")
            h.controller.setField(EquipmentField.LENGTH, " 28 pouces "); h.controller.save()
            val created = assertIs<ArrowSet>(h.state.original); assertNotEquals(arrows().uuid, created.uuid); assertEquals("", created.spine)
            h.controller.edit(); h.controller.setField(EquipmentField.POINT, "100 gr ?"); h.controller.save()
            val edited = assertIs<ArrowSet>(h.state.original); assertEquals(created.uuid, edited.uuid); assertEquals(created.createdAt, edited.createdAt)
            assertEquals(" 28 pouces ", edited.length); assertEquals("100 gr ?", edited.pointWeight)
            h.controller.list(); assertEquals(2, h.state.entries.count { it.kind == EquipmentKind.ARROW_SET }) }
    }
    @Test fun `invalid form keeps exact draft without issuing a write`() {
        Harness().use { h -> h.controller.open(); h.controller.add(EquipmentKind.BOW); h.controller.save()
            assertEquals(EquipmentProblem.VALIDATION, h.state.problem); assertEquals("", h.state.draft!!.name); assertTrue(h.writes.isEmpty())
            h.controller.setField(EquipmentField.NAME, "A"); h.controller.setField(EquipmentField.POWER, "NaN"); h.controller.save()
            assertEquals("NaN", h.state.draft!!.power); assertTrue(h.writes.isEmpty()) }
    }
    @Test fun `technical create error preserves UUID timestamp and exact retry proposal`() {
        Harness().use { h -> h.newBow(); h.createResult = EquipmentCreateResult.TechnicalFailure(IOException("Synthetic")); h.controller.save()
            val pending = h.state.pending!!; h.controller.setField(EquipmentField.NAME, "Cannot replace pending"); assertEquals(pending, h.state.pending)
            h.createResult = null; h.controller.retry(); assertSame(h.writes[0].proposed, h.writes[1].proposed)
            assertEquals(pending.proposed, h.state.original); assertNull(h.state.pending) }
    }
    @Test fun `technical update retries original complete expected and proposed without rebasing`() {
        Harness().use { h -> h.editBow(); h.updateResult = EquipmentUpdateResult.TechnicalFailure(IOException("Synthetic")); h.controller.save()
            val pending = h.state.pending!!; h.updateResult = null; h.controller.retry()
            assertSame(pending.expected, h.writes[1].expected); assertSame(pending.proposed, h.writes[1].proposed); assertEquals(2, h.writes.size) }
    }
    @Test fun `concurrent change produces visible conflict and inspecting stored does not overwrite proposal`() {
        Harness().use { h -> h.editBow(); val changed = bow().copy(notes = "Concurrent", updatedAt = now.plusSeconds(1)); h.values[bow().uuid] = changed
            h.controller.save(); assertEquals(EquipmentProblem.CONFLICT, h.state.problem); val pending = h.state.pending!!
            h.controller.retry(); assertEquals(1, h.writes.size); h.controller.inspectStored()
            assertEquals(changed, h.state.stored); assertSame(pending, h.state.pending); assertEquals(bow(), h.state.original)
            h.controller.discardAndReload(); assertEquals(changed, h.state.original); assertNull(h.state.pending) }
    }
    @Test fun `unreadable discovered records remain visible and are never silently repaired`() {
        Harness().use { h -> h.readResult = EquipmentReadResult.NeedsIntervention(StorageDiagnostic("bows[id]", "Missing registry"))
            h.controller.open(); assertEquals(2, h.state.entries.size); assertTrue(h.state.entries.all { it.value == null && it.diagnostic!!.contains("registry") })
            h.controller.show(EquipmentKind.BOW, bow().uuid); assertEquals(EquipmentProblem.INTERVENTION, h.state.problem); assertTrue(h.writes.isEmpty()) }
    }
    @Test fun `failed discovery preserves prior list while failed discard preserves entered draft`() {
        Harness().use { h -> h.controller.open(); val entries = h.state.entries; h.discoveryFailure = true; h.controller.refresh()
            assertEquals(entries, h.state.entries); assertEquals(EquipmentProblem.TECHNICAL, h.state.problem)
            h.discoveryFailure = false; h.controller.show(EquipmentKind.BOW, bow().uuid); h.controller.edit(); h.controller.setField(EquipmentField.NAME, "Keep")
            h.readResult = EquipmentReadResult.NotFound; h.controller.discardAndReload(); assertEquals("Keep", h.state.draft!!.name)
            assertEquals(EquipmentPage.FORM, h.state.page); assertTrue(h.writes.isEmpty()) }
    }
    @Test fun `back navigation cannot silently abandon a form or pending write`() {
        Harness().use { h -> h.newBow(); h.controller.close(); h.controller.list(); assertEquals(EquipmentPage.FORM, h.state.page)
            assertTrue(h.locked); h.controller.discardAndReload(); assertEquals(EquipmentPage.LIST, h.state.page); assertTrue(h.writes.isEmpty())
            h.controller.close(); assertFalse(h.locked) }
    }
    @Test fun `ViewModel reattachment preserves form and failed proposal while process restart uses only durable values`() {
        Harness().use { h -> h.newBow(); val attached = h.controller; assertEquals("Arc synthétique", attached.state.value.draft!!.name)
            h.createResult = EquipmentCreateResult.TechnicalFailure(IOException("Lost acknowledgement")); attached.save()
            assertSame(h.state.pending, attached.state.value.pending); assertEquals(1, h.writes.size)
            val fresh = EquipmentController(h.repository, h.scope, { true }, {}, Clock.fixed(now, ZoneOffset.UTC)); fresh.open()
            assertNull(fresh.state.value.pending); assertEquals(2, fresh.state.value.entries.size) }
    }
    @Test fun `cancellation while saving retains proposal and never acknowledges it`() = runBlocking<Unit> {
        val h = Harness(); h.newBow(); h.gate = CompletableDeferred(); h.controller.save(); val proposed = h.state.pending!!
        h.close(); yield(); assertEquals(EquipmentProblem.TECHNICAL, h.state.problem); assertSame(proposed, h.state.pending)
        assertNull(h.state.original); assertFalse(h.state.busy)
    }
    @Test fun `gate denial and suspended reads reject duplicate navigation or creation`() = runBlocking<Unit> {
        Harness().use { h -> h.allowed = false; h.controller.open(); assertFalse(h.state.open); assertEquals(0, h.reads)
            h.allowed = true; h.readGate = CompletableDeferred(); h.controller.open(); h.controller.open(); h.controller.add(EquipmentKind.BOW)
            assertTrue(h.state.busy); assertEquals(EquipmentPage.LIST, h.state.page); assertEquals(1, h.reads)
            h.readGate!!.complete(Unit); yield(); assertEquals(2, h.reads) }
    }
    @Test fun `storage intervention not found and conflict preserve revision without a retry`() {
        listOf<EquipmentUpdateResult>(EquipmentUpdateResult.NotFound,
            EquipmentUpdateResult.NeedsIntervention(StorageDiagnostic("bows", "Corrupt")),
            EquipmentUpdateResult.Conflict(StorageDiagnostic("bows", "Changed"))).forEach { result ->
            Harness().use { h -> h.editBow(); h.updateResult = result; h.controller.save(); assertNotNull(h.state.pending)
                assertEquals(bow(), h.state.original); h.controller.retry(); assertEquals(1, h.writes.size) }
        }
    }
    @Test fun `catalog gate excludes SAF session commands and entry from any other screen`() {
        Harness().use { h ->
            val sessions = SessionController(object : SessionRepository {
                override suspend fun discover() = SessionDiscoveryResult.Found(emptyList())
                override suspend fun read(uuid: String): SessionReadResult = error("No session read")
                override suspend fun create(archive: DomainArchive): SessionCreateResult = error("No session create")
                override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult = error("No session update")
            }, h.scope)
            val catalog = EquipmentController(h.repository, h.scope, sessions::acquireImport, sessions::releaseArchiveOperation)
            catalog.open(); assertTrue(sessions.state.value.archiveOperationLocked)
            assertFalse(sessions.acquireExport()); assertFalse(sessions.acquireImport()); sessions.configure()
            assertEquals(SessionScreen.LIST, sessions.state.value.screen)
            catalog.close(); assertFalse(sessions.state.value.archiveOperationLocked)
            sessions.configure(); catalog.open(); assertFalse(catalog.state.value.open)
        }
    }

}
