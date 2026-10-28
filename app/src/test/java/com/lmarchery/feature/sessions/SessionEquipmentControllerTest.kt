package com.lmarchery.feature.sessions

import com.lmarchery.backup.V2TestData.archive
import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.arrows
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import kotlinx.coroutines.*
import java.io.IOException
import java.time.*
import java.util.UUID
import kotlin.test.*

class SessionEquipmentControllerTest {
    private fun one(session: Session): DomainArchive = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
        archive().profiles, listOf(session)))).archive
    private inner class Harness(completed: Boolean = false, at: Instant = now.plusSeconds(60)) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initial = archive().sessions.single().let { if (completed) it.complete(now.plusSeconds(1)) else it }
        var stored = one(initial); var create: DomainArchive? = null; val writes = mutableListOf<Pair<DomainArchive, DomainArchive>>()
        var gate: CompletableDeferred<Unit>? = null; var choiceGate: CompletableDeferred<Unit>? = null
        var extraBow: Bow? = null
        var forcedUpdate: SessionUpdateResult? = null; var catalogError = false; var readError = false
        val repo = object : SessionRepository {
            override suspend fun discover() = SessionDiscoveryResult.Found(listOf(initial.uuid))
            override suspend fun read(uuid: String) = SessionReadResult.Found(stored.sessions.single(), stored.profiles.single())
            override suspend fun create(archive: DomainArchive): SessionCreateResult { create = archive; stored = archive; return SessionCreateResult.Created(archive.sessions.single().uuid) }
            override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult {
                writes += expected to proposed; gate?.await(); forcedUpdate?.let { return it }
                if (stored.toSnapshot() != expected.toSnapshot()) return SessionUpdateResult.Conflict(StorageDiagnostic("session", "Stale"))
                validateSessionUpdate(expected.sessions.single(), proposed.sessions.single()); stored = proposed
                return SessionUpdateResult.Updated(proposed.sessions.single().uuid)
            }
        }
        val equipment = object : EquipmentRepository {
            override suspend fun discover(kind: EquipmentKind): EquipmentDiscoveryResult {
                choiceGate?.await()
                if (catalogError) return EquipmentDiscoveryResult.TechnicalFailure(IOException("Synthetic catalog"))
                return EquipmentDiscoveryResult.Found(if (kind == EquipmentKind.BOW) listOfNotNull(bow().uuid, extraBow?.uuid) else listOf(arrows().uuid))
            }
            override suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment> = if (readError)
                EquipmentReadResult.NeedsIntervention(StorageDiagnostic("equipment[$uuid]", "Unreadable"))
                else EquipmentReadResult.Found(if (kind == EquipmentKind.BOW) extraBow?.takeIf { it.uuid == uuid } ?: bow() else arrows())
            override suspend fun create(value: Equipment): EquipmentCreateResult = error("No equipment write")
            override suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult = error("No equipment write")
        }
        val controller = SessionController(repo, scope, Clock.fixed(at, ZoneOffset.UTC), equipmentRepository = equipment)
        val state get() = controller.state.value
        fun open() { controller.openSession(initial.uuid); controller.beginEquipmentEdit() }
        override fun close() = scope.cancel()
    }
    @Test fun `creation supports neither bow only arrows only and both without changing scoring configuration`() {
        for (selection in listOf(EquipmentSelection(), EquipmentSelection(bow().uuid), EquipmentSelection(arrowSetUuid = arrows().uuid), EquipmentSelection(bow().uuid, arrows().uuid))) {
            Harness().use { h -> h.controller.configure(); h.controller.chooseCreationEquipment(EquipmentKind.BOW, selection.bowUuid)
                h.controller.chooseCreationEquipment(EquipmentKind.ARROW_SET, selection.arrowSetUuid); h.controller.createSession()
                val s = h.create!!.sessions.single(); assertEquals(selection.bowUuid, s.bowUuid); assertEquals(selection.arrowSetUuid, s.arrowSetUuid)
                assertEquals(3, s.arrowCountPerEnd); assertEquals(ScoringMode.TRAINING, s.scoringMode); assertEquals(IndoorProfile.RECURVE_40.target, s.targetType) }
        }
    }
    @Test fun `slow or broken optional catalog never prevents immediate creation without equipment`() = runBlocking<Unit> {
        Harness().use { h -> h.choiceGate = CompletableDeferred(); h.controller.configure()
            assertTrue(h.state.equipmentChoicesLoading); assertTrue(h.state.ready)
            h.controller.createSession(); assertNotNull(h.create); assertNull(h.state.session!!.bowUuid); assertFalse(h.state.equipmentChoicesLoading)
            h.choiceGate!!.complete(Unit); yield(); assertEquals(SessionScreen.SESSION, h.state.screen) }
        Harness().use { h -> h.catalogError = true; h.controller.configure(); assertTrue(h.state.equipmentChoices.diagnostics.isNotEmpty())
            assertTrue(h.state.ready); h.controller.createSession(); assertNotNull(h.create) }
    }
    @Test fun `new choices reject arbitrary UUIDs and wrong material type`() {
        Harness().use { h -> h.controller.configure(); h.controller.chooseCreationEquipment(EquipmentKind.BOW, arrows().uuid)
            assertEquals(ProblemKind.INVALID_ACTION, h.state.problem!!.kind); assertNull(h.state.creationEquipment.bowUuid)
            h.controller.chooseCreationEquipment(EquipmentKind.BOW, UUID.randomUUID().toString()); assertNull(h.state.creationEquipment.bowUuid)
            h.controller.chooseCreationEquipment(EquipmentKind.BOW, bow().uuid); assertNull(h.state.problem); assertEquals(bow().uuid, h.state.creationEquipment.bowUuid) }
    }
    @Test fun `opaque references survive a failed lookup unchanged or can be cleared only explicitly`() {
        Harness().use { h -> h.stored = one(h.initial.copy(bowUuid = UUID.randomUUID().toString(), arrowSetUuid = null))
            val opaque = h.stored.sessions.single().bowUuid; h.readError = true; h.open()
            assertEquals(opaque, h.state.equipmentEdit!!.bowUuid); assertTrue(h.state.equipmentChoices.label(EquipmentKind.BOW, opaque).contains("non résolue"))
            h.controller.confirmEquipment(); assertTrue(h.writes.isEmpty()); assertEquals(opaque, h.state.session!!.bowUuid)
            h.controller.beginEquipmentEdit(); h.controller.chooseEquipment(EquipmentKind.BOW, null); h.controller.confirmEquipment()
            assertNull(h.state.session!!.bowUuid); assertEquals(1, h.writes.size) }
    }
    @Test fun `completed association changes only references and root revision preserving closure children and decisions`() {
        Harness(completed = true).use { h -> val before = h.stored.sessions.single(); h.open()
            assertTrue(h.state.canEditEquipment.not()); assertFalse(h.state.canEdit); assertFalse(h.state.canAddImpact)
            h.controller.chooseEquipment(EquipmentKind.BOW, null); h.controller.confirmEquipment()
            val after = h.state.session!!; assertEquals(before.withEquipmentReferences(null, before.arrowSetUuid, now.plusSeconds(60)), after)
            assertEquals(before.ends, after.ends); assertEquals(before.endedAt, after.endedAt); assertEquals(SessionStatus.COMPLETED, after.status)
            val writes = h.writes.size
            h.controller.targetTapped(NormalizedPoint(.25f, 0f)); h.controller.newEnd(); h.controller.validateEnd(); h.controller.startSession()
            assertEquals(writes, h.writes.size); assertFalse(h.state.canEdit); assertTrue(h.state.canEditEquipment) }
    }
    @Test fun `association can be added and deliberately replaced after completion`() {
        Harness(completed = true).use { h -> h.stored = one(h.initial.copy(bowUuid = null, arrowSetUuid = null)); h.open()
            h.controller.chooseEquipment(EquipmentKind.BOW, bow().uuid); h.controller.chooseEquipment(EquipmentKind.ARROW_SET, arrows().uuid)
            h.controller.confirmEquipment(); assertEquals(bow().uuid, h.state.session!!.bowUuid); assertEquals(arrows().uuid, h.state.session!!.arrowSetUuid)
            h.controller.beginEquipmentEdit(); h.controller.chooseEquipment(EquipmentKind.ARROW_SET, null); h.controller.confirmEquipment()
            assertEquals(2, h.writes.size); assertNull(h.state.session!!.arrowSetUuid); assertEquals(SessionStatus.COMPLETED, h.state.session!!.status) }
    }
    @Test fun `deliberate replacement selects a different known bow UUID without editing either catalog value`() {
        Harness(completed = true).use { h ->
            val second = Bow(UUID.randomUUID().toString(), "Second arc", createdAt = now, updatedAt = now)
            h.extraBow = second; h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, second.uuid)
            assertEquals(bow().uuid, h.state.session!!.bowUuid)
            h.controller.confirmEquipment(); assertEquals(second.uuid, h.state.session!!.bowUuid)
            assertEquals(h.initial.ends, h.state.session!!.ends); assertEquals(1, h.writes.size)
        }
    }
    @Test fun `historical opaque reference is retained even when UUID belongs to another catalog kind`() {
        Harness().use { h ->
            h.stored = one(h.initial.copy(bowUuid = arrows().uuid)); h.open()
            assertTrue(h.state.equipmentChoices.bows.none { it.uuid == arrows().uuid })
            h.controller.chooseEquipment(EquipmentKind.BOW, arrows().uuid); h.controller.confirmEquipment()
            assertTrue(h.writes.isEmpty()); assertEquals(arrows().uuid, h.state.session!!.bowUuid)
        }
    }
    @Test fun `cancel selection and unchanged confirmation have zero writes`() {
        Harness().use { h -> h.open(); val before = h.stored
            h.controller.chooseEquipment(EquipmentKind.BOW, null); h.controller.cancelEquipmentEdit(); assertEquals(before, h.stored)
            assertNull(h.state.equipmentEdit); assertTrue(h.writes.isEmpty())
            h.controller.beginEquipmentEdit(); h.controller.confirmEquipment(); assertTrue(h.writes.isEmpty()); assertEquals(before, h.stored) }
    }
    @Test fun `metadata edit gates canvas navigation archive operations and other mutations`() {
        Harness().use { h -> h.open(); assertFalse(h.state.canEdit); assertFalse(h.state.canNavigate)
            assertFalse(h.controller.acquireExport()); assertFalse(h.controller.acquireImport())
            h.controller.targetTapped(NormalizedPoint(.25f, 0f)); h.controller.validateEnd(); h.controller.newEnd(); h.controller.completeSession(); h.controller.returnToList()
            assertEquals(SessionScreen.SESSION, h.state.screen); assertTrue(h.writes.isEmpty())
            h.controller.cancelEquipmentEdit(); assertTrue(h.state.canAddImpact) }
    }
    @Test fun `double metadata confirmations wait for one SQL acknowledgement and keep old durable reference`() = runBlocking<Unit> {
        Harness().use { h -> h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, null); h.gate = CompletableDeferred()
            h.controller.confirmEquipment(); h.controller.confirmEquipment(); h.controller.cancelEquipmentEdit()
            assertEquals(1, h.writes.size); assertTrue(h.state.busy); assertEquals(bow().uuid, h.state.session!!.bowUuid)
            h.gate!!.complete(Unit); yield(); assertNull(h.state.session!!.bowUuid); assertNull(h.state.equipmentEdit) }
    }
    @Test fun `concurrent session revision keeps exact expected proposal and never rebases a conflict`() {
        Harness().use { h -> h.open(); val expected = h.state.saved!!; h.controller.chooseEquipment(EquipmentKind.BOW, null)
            h.stored = one(h.initial.copy(notes = "Concurrent", updatedAt = now.plusSeconds(2)))
            h.controller.confirmEquipment(); assertEquals(ProblemKind.CONFLICT, h.state.problem!!.kind)
            val pending = h.state.pending!!; assertSame(expected, pending.expected); assertEquals(bow().uuid, h.state.session!!.bowUuid)
            h.controller.retrySave(); assertEquals(1, h.writes.size); h.controller.previewStored(); assertSame(pending, h.state.pending)
            assertEquals("Concurrent", h.state.storedPreview!!.sessions.single().notes) }
    }
    @Test fun `technical metadata failure and coroutine cancellation retain exact proposal without false confirmation`() = runBlocking<Unit> {
        Harness().use { h -> h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, null)
            h.forcedUpdate = SessionUpdateResult.TechnicalFailure(IOException("Synthetic")); h.controller.confirmEquipment()
            val pending = h.state.pending!!; h.forcedUpdate = null; h.controller.retrySave()
            assertSame(pending.expected, h.writes.last().first); assertSame(pending.proposed, h.writes.last().second); assertNull(h.state.pending) }
        val h = Harness(); h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, null); h.gate = CompletableDeferred(); h.controller.confirmEquipment()
        h.close(); yield(); assertNotNull(h.state.pending); assertNotNull(h.state.equipmentEdit); assertEquals(bow().uuid, h.state.session!!.bowUuid)
    }
    @Test fun `backdated metadata revision is rejected without a storage call`() {
        Harness(at = now.minusNanos(1)).use { h -> h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, null); h.controller.confirmEquipment()
            assertEquals(ProblemKind.INVALID_ACTION, h.state.problem!!.kind); assertTrue(h.writes.isEmpty()); assertNotNull(h.state.equipmentEdit) }
    }
    @Test fun `stored association presentation retains exact unknown reference and distinguishes no association`() {
        val unknown = UUID.randomUUID().toString()
        val lines = equipmentAssociationLines(EquipmentChoices(bows = listOf(bow()), loaded = true), EquipmentSelection(bow().uuid, unknown))
        assertTrue(lines[0].contains(bow().name)); assertTrue(lines[0].contains(bow().uuid))
        assertTrue(lines[1].contains(unknown)); assertTrue(lines[1].contains("non résolue"))
        assertTrue(equipmentAssociationLines(EquipmentChoices(), EquipmentSelection()).all { it.contains("Aucun matériel associé") })
    }
    @Test fun `selection survives ViewModel reattachment and does not overwrite confirmed references before saving`() {
        Harness().use { h -> h.open(); h.controller.chooseEquipment(EquipmentKind.BOW, null); val attached = h.controller
            assertNull(attached.state.value.equipmentEdit!!.bowUuid); assertEquals(bow().uuid, attached.state.value.session!!.bowUuid)
            attached.confirmEquipment(); assertNull(h.state.session!!.bowUuid); assertEquals(1, h.writes.size) }
    }
}
