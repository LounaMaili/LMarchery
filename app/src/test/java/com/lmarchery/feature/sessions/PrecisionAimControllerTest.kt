package com.lmarchery.feature.sessions

import com.lmarchery.backup.destinationChosenForTest

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.data.*
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.export.*
import com.lmarchery.feature.equipment.EquipmentController
import com.lmarchery.feature.export.*
import kotlinx.coroutines.*
import java.io.IOException
import java.time.*
import java.util.UUID
import kotlin.test.*

class PrecisionAimControllerTest {
    private val created = Instant.parse("2026-10-16T10:00:00Z")
    private val now = created.plusSeconds(30)
    private val nine = NormalizedPoint(.105f, 0f)
    private val eight = NormalizedPoint(.25f, 0f)
    private fun id() = UUID.randomUUID().toString()
    private val bow = Bow("80000000-0000-4000-8000-000000000001", "Synthetic integration bow", createdAt = created, updatedAt = created)
    private val arrows = ArrowSet("80000000-0000-4000-8000-000000000002", "Synthetic integration arrows", spine = "500 ?", createdAt = created, updatedAt = created)
    private val equipment = object : EquipmentRepository {
        override suspend fun discover(kind: EquipmentKind) = EquipmentDiscoveryResult.Found(listOf(if (kind == EquipmentKind.BOW) bow.uuid else arrows.uuid))
        override suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment> =
            EquipmentReadResult.Found(if (kind == EquipmentKind.BOW) bow else arrows)
        override suspend fun create(value: Equipment): EquipmentCreateResult = error("No catalog mutation expected")
        override suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult = error("No catalog mutation expected")
    }
    private fun seed(count: Int = 0, profile: IndoorProfile = IndoorProfile.RECURVE_40): Session {
        var end = End(id(), 1, EndStatus.EDITING, createdAt = created, updatedAt = created)
        repeat(count) { i -> end = end.withImpact(scoreArrowImpact(id(), i + 1, profile.target, nine,
            ScoringMode.TRAINING, createdAt = created)) }
        return Session(id(), SessionDateTime.fromInstant(created, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic precise aim", targetType = profile.target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true,
            scoringMode = ScoringMode.TRAINING, createdAt = created, updatedAt = created).start(created).withEnd(end)
    }
    private fun archive(s: Session): DomainArchive {
        val p = BuiltInTargetRegistry.find(s.targetType.uuid)!!
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(p.definition, p.regulatoryEdition)), listOf(s)))).archive
    }
    private class CountingClock(val time: Instant) : Clock() {
        var calls = 0
        override fun instant(): Instant { calls++; return time }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
    }
    private class Repository(var stored: DomainArchive) : SessionRepository {
        val calls = mutableListOf<Pair<DomainArchive, DomainArchive>>()
        var gate: CompletableDeferred<Unit>? = null
        var next: SessionUpdateResult? = null
        var readFailed = false
        override suspend fun discover() = SessionDiscoveryResult.Found(listOf(stored.sessions.single().uuid))
        override suspend fun read(uuid: String): SessionReadResult = if (readFailed)
            SessionReadResult.TechnicalFailure(IOException("Synthetic read failure"))
        else SessionReadResult.Found(stored.sessions.single(), stored.profiles.single())
        override suspend fun create(archive: DomainArchive): SessionCreateResult = error("No creation expected")
        override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult {
            calls += expected to proposed; gate?.await()
            next?.let { next = null; return it }
            assertEquals(stored.toSnapshot(), expected.toSnapshot())
            validateSessionUpdate(expected.sessions.single(), proposed.sessions.single())
            stored = proposed
            return SessionUpdateResult.Updated(proposed.sessions.single().uuid)
        }
    }
    private inner class Harness(session: Session = seed()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = Repository(archive(session))
        val clock = CountingClock(now)
        var allocations = 0
        val controller = SessionController(repository, scope, clock, newUuid = { allocations++; id() }, equipmentRepository = equipment)
            .also { it.openSession(session.uuid) }
        val ui get() = controller.state.value
        fun aim(point: NormalizedPoint = nine): Long {
            controller.setPrecisionAim(true)
            return assertNotNull(controller.beginPreciseAim(ui.aimContext!!, point))
        }
        fun release(point: NormalizedPoint = nine) { val token = aim(point); controller.finishPreciseAim(token, point) }
        override fun close() { scope.cancel() }
    }

    @Test fun `quick tap remains default and precise entry is inactive`() {
        Harness().use { h ->
            assertFalse(h.ui.precisionAimEnabled); assertNull(h.controller.beginPreciseAim(h.ui.aimContext!!, nine))
            h.controller.targetTapped(nine)
            assertEquals(1, h.repository.calls.size); assertEquals(nine, h.ui.editingEnd!!.impacts.single().point)
            assertNull(h.ui.newImpactCandidate)
        }
    }
    @Test fun `press move and release create no UUID timestamp SQL row or durable score`() {
        Harness().use { h ->
            val before = h.ui.saved!!; val token = h.aim()
            h.controller.movePreciseAim(token, eight)
            assertEquals(eight, h.ui.aimedPoint); assertEquals(8, h.ui.activeAim!!.candidate.preview.scoreFinal)
            assertFalse(h.ui.canConfirmNewImpact)
            h.controller.finishPreciseAim(token, eight)
            val c = h.ui.newImpactCandidate!!
            assertEquals(AimContext(h.ui.session!!.uuid, h.ui.editingEnd!!.uuid), c.context)
            assertEquals(eight, c.point); assertEquals(eight, h.ui.aimedPoint)
            assertEquals("8", c.scoreLabel()); assertTrue(h.ui.canConfirmNewImpact)
            assertEquals("Nouvelle flèche · non enregistrée", presentNewImpactNotice(h.ui).heading)
            assertSame(before, h.ui.saved); assertEquals(0, h.ui.validatedTotal); assertEquals(0, h.ui.provisionalTotal)
            assertTrue(h.repository.calls.isEmpty()); assertEquals(0, h.allocations); assertEquals(0, h.clock.calls)
        }
    }
    @Test fun `cancelling active gesture keeps previously released candidate for rotation or system cancellation`() {
        Harness().use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            val token = h.aim(eight); h.controller.cancelPreciseAim(token)
            h.controller.finishPreciseAim(token, eight)
            assertSame(candidate, h.ui.newImpactCandidate); assertEquals(nine, h.ui.aimedPoint)
            assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `cancelled initial gesture cannot create a released candidate`() {
        Harness().use { h ->
            val token = h.aim(); h.controller.cancelPreciseAim(token); h.controller.finishPreciseAim(token, nine)
            assertFalse(h.ui.hasAimProposal); assertNull(h.ui.aimedPoint); assertTrue(h.ui.canNavigate)
            assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `mode switch cancels active gesture while a released addition requires explicit resolution`() {
        Harness().use { h ->
            val token = h.aim(); h.controller.setPrecisionAim(false); h.controller.finishPreciseAim(token, nine)
            assertFalse(h.ui.precisionAimEnabled); assertFalse(h.ui.hasAimProposal)
            h.release(); val candidate = h.ui.newImpactCandidate
            h.controller.setPrecisionAim(false)
            assertTrue(h.ui.precisionAimEnabled); assertSame(candidate, h.ui.newImpactCandidate)
            h.controller.cancelNewImpactCandidate(); h.controller.setPrecisionAim(false)
            assertFalse(h.ui.precisionAimEnabled); assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `stale callbacks cannot clear or finish a newer gesture`() {
        Harness().use { h ->
            val old = h.aim(); h.controller.cancelPreciseAim(old)
            val current = h.aim(eight)
            h.controller.cancelPreciseAim(old); h.controller.movePreciseAim(old, nine); h.controller.finishPreciseAim(old, nine)
            assertEquals(current, h.ui.activeAim!!.token); assertEquals(eight, h.ui.aimedPoint)
            h.controller.finishPreciseAim(current, eight)
            assertEquals(eight, h.ui.newImpactCandidate!!.point)
        }
    }
    @Test fun `proposal blocks navigation editing transitions backup and conflicting correction`() {
        Harness(seed(1)).use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            val end = h.ui.editingEnd!!
            h.controller.returnToList(); h.controller.selectEnd("other"); h.controller.openSession(h.ui.session!!.uuid)
            h.controller.configure(); h.controller.selectImpact(end.uuid, end.impacts.single().uuid)
            h.controller.validateEnd(); h.controller.newEnd(); h.controller.completeSession(); h.controller.targetTapped(eight)
            assertFalse(h.controller.acquireExport()); assertFalse(h.controller.acquireImport())
            assertSame(candidate, h.ui.newImpactCandidate); assertNull(h.ui.impactEdit)
            assertEquals(SessionScreen.SESSION, h.ui.screen); assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `explicit cancellation restores navigation without any write`() {
        Harness().use { h ->
            h.release(); h.controller.cancelNewImpactCandidate(); h.controller.returnToList()
            assertEquals(SessionScreen.LIST, h.ui.screen); assertFalse(h.ui.hasAimProposal)
            assertFalse(h.ui.precisionAimEnabled); assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `confirmation allocates once blocks double commands and waits for acknowledgement`() = runBlocking<Unit> {
        Harness().use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            val before = h.ui.saved; val gate = CompletableDeferred<Unit>(); h.repository.gate = gate
            h.controller.confirmNewImpactCandidate(); val pending = h.ui.pending!!
            h.controller.confirmNewImpactCandidate(); h.controller.cancelNewImpactCandidate(); h.controller.addImpact(eight)
            assertSame(before, h.ui.saved); assertSame(candidate, h.ui.newImpactCandidate)
            assertEquals(1, h.repository.calls.size); assertEquals(1, h.allocations); assertEquals(1, h.clock.calls)
            assertEquals(nine, pending.proposed.sessions.single().ends.single().impacts.single().point)
            gate.complete(Unit); yield()
            assertEquals(9, h.ui.provisionalTotal); assertNull(h.ui.newImpactCandidate); assertNull(h.ui.pending)
            assertEquals(1, h.ui.editingEnd!!.impacts.size)
        }
    }
    @Test fun `SQL failure retains candidate and retries exactly the same archive UUID and timestamp`() {
        Harness().use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            h.repository.next = SessionUpdateResult.TechnicalFailure(IOException("Synthetic SQL failure"))
            h.controller.confirmNewImpactCandidate(); val pending = h.ui.pending!!
            h.controller.cancelNewImpactCandidate(); h.controller.confirmNewImpactCandidate(); h.controller.returnToList()
            assertSame(candidate, h.ui.newImpactCandidate); assertTrue(h.ui.editingEnd!!.impacts.isEmpty())
            assertEquals("Nouvelle flèche · enregistrement non confirmé", presentNewImpactNotice(h.ui).heading)
            h.controller.retrySave()
            assertSame(pending.expected, h.repository.calls.last().first)
            assertSame(pending.proposed, h.repository.calls.last().second)
            assertEquals(1, h.allocations); assertEquals(1, h.clock.calls); assertNull(h.ui.newImpactCandidate)
        }
    }
    @Test fun `conflict cannot retry rebase or silently discard candidate`() {
        Harness().use { h ->
            h.release(); h.repository.next = SessionUpdateResult.Conflict(StorageDiagnostic("session", "Synthetic conflict"))
            h.controller.confirmNewImpactCandidate(); val pending = h.ui.pending; val candidate = h.ui.newImpactCandidate
            h.controller.retrySave(); h.controller.cancelNewImpactCandidate(); h.controller.previewStored()
            assertSame(pending, h.ui.pending); assertSame(candidate, h.ui.newImpactCandidate)
            assertNotNull(h.ui.storedPreview); assertEquals(1, h.repository.calls.size)
            h.repository.readFailed = true; h.controller.reloadAndDiscard()
            assertSame(candidate, h.ui.newImpactCandidate); assertSame(pending, h.ui.pending)
            h.repository.readFailed = false; h.controller.reloadAndDiscard()
            assertNull(h.ui.newImpactCandidate); assertNull(h.ui.pending)
        }
    }
    @Test fun `a full end validated end or completed session cannot start an addition`() {
        val full = seed(3); val validated = full.withEnd(full.ends.single().validate(created))
        listOf(full, validated, validated.complete(created)).forEach { s -> Harness(s).use { h ->
            h.controller.setPrecisionAim(true); assertNull(h.controller.beginPreciseAim(h.ui.aimContext!!, nine))
            h.controller.confirmNewImpactCandidate(); assertTrue(h.repository.calls.isEmpty())
        } }
    }
    @Test fun `last remaining capacity accepts only one candidate and no fourth arrow`() {
        Harness(seed(2)).use { h ->
            h.release(); h.controller.confirmNewImpactCandidate()
            assertEquals(3, h.ui.editingEnd!!.impacts.size)
            assertNull(h.controller.beginPreciseAim(h.ui.aimContext!!, eight)); h.controller.confirmNewImpactCandidate()
            assertEquals(1, h.repository.calls.size)
        }
    }
    @Test fun `new controller restores only confirmed archive and starts in quick mode`() {
        Harness().use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            // Activity recreation keeps this controller in the ViewModel; cancellation of a pointer keeps its candidate.
            assertSame(candidate, h.controller.state.value.newImpactCandidate)
            val restored = SessionController(h.repository, h.scope, h.clock)
            restored.openSession(h.ui.session!!.uuid)
            assertNull(restored.state.value.newImpactCandidate); assertFalse(restored.state.value.precisionAimEnabled)
            assertEquals(h.ui.saved!!.toSnapshot(), restored.state.value.saved!!.toSnapshot())
            assertTrue(h.repository.calls.isEmpty())
        }
    }
    @Test fun `profile previews and final geometric scores are identical without cordon promotion`() {
        listOf(IndoorProfile.RECURVE_40 to 10, IndoorProfile.COMPOUND_40 to 9).forEach { (p, expected) ->
            Harness(seed(profile = p)).use { h ->
                h.release(NormalizedPoint(.075f, 0f)); assertEquals(expected, h.ui.newImpactCandidate!!.preview.scoreFinal)
                h.controller.confirmNewImpactCandidate(); assertEquals(expected, h.ui.editingEnd!!.impacts.single().scoreFinal)
            }
        }
        Harness().use { h -> h.release(); h.controller.confirmNewImpactCandidate()
            assertEquals(9, h.ui.editingEnd!!.impacts.single().scoreFinal) }
    }
    @Test fun `precise reposition reuses manual invalidation path motif UUID input and history`() {
        val initial = seed(1); val end = initial.ends.single(); val original = end.impacts.single()
            .withFinalScore(initial.targetType, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Synthetic decision", created), created)
        val s = initial.withEnd(end.withImpact(original))
        Harness(s).use { h ->
            h.controller.selectImpact(end.uuid, original.uuid); h.controller.beginReposition()
            h.release(eight)
            assertNull(h.ui.newImpactCandidate); assertEquals(eight, h.ui.impactEdit!!.candidate)
            assertFalse(h.ui.canConfirmReposition); h.controller.confirmReposition(); assertTrue(h.repository.calls.isEmpty())
            h.controller.setRepositionReason("Synthetic precise move"); h.controller.confirmReposition()
            val moved = h.ui.editingEnd!!.impacts.single()
            assertEquals(original.uuid, moved.uuid); assertEquals(original.createdAt, moved.createdAt)
            assertEquals(original.inputMode, moved.inputMode); assertEquals(8, moved.scoreFinal)
            assertEquals(original.finalScoreDecision, moved.repositions.single().previousDecision)
            assertEquals(0, h.allocations); assertEquals(1, h.repository.calls.size)
        }
    }
    @Test fun `interrupted reposition preserves old candidate and prevents confirmation during a new gesture`() {
        Harness(seed(1)).use { h ->
            val end = h.ui.editingEnd!!; h.controller.selectImpact(end.uuid, end.impacts.single().uuid)
            h.controller.beginReposition(); h.release(eight)
            val candidate = h.ui.impactEdit!!.candidate; val token = h.aim(NormalizedPoint(.35f, 0f))
            assertFalse(h.ui.canConfirmReposition); h.controller.confirmReposition()
            h.controller.cancelPreciseAim(token); assertEquals(candidate, h.ui.impactEdit!!.candidate)
            assertEquals(candidate, h.ui.aimedPoint); assertTrue(h.repository.calls.isEmpty())
            h.controller.setPrecisionAim(false); assertEquals(candidate, h.ui.impactEdit!!.candidate)
        }
    }
    @Test fun `old canvas context cannot begin aiming on another session end or impact`() {
        Harness().use { h ->
            h.controller.setPrecisionAim(true)
            val context = h.ui.aimContext!!
            listOf(context.copy(sessionUuid = id()), context.copy(endUuid = id()), context.copy(impactUuid = id())).forEach {
                assertNull(h.controller.beginPreciseAim(it, nine))
            }
            assertFalse(h.ui.hasAimProposal); assertEquals(0, h.allocations); assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `a submitted arrow remains unconfirmed if commit acknowledgement is lost`() {
        Harness().use { h ->
            h.release()
            h.repository.next = SessionUpdateResult.TechnicalFailure(IOException("Synthetic lost acknowledgement"))
            h.controller.confirmNewImpactCandidate()
            // Model a commit that happened before its acknowledgement was lost.
            h.repository.stored = h.ui.pending!!.proposed
            assertTrue(presentNewImpactNotice(h.ui).heading.contains("non confirmé"))
            assertTrue(h.ui.editingEnd!!.impacts.isEmpty())
            h.controller.previewStored()
            assertEquals(1, h.ui.storedPreview!!.sessions.single().ends.single().impacts.size)
            assertNotNull(h.ui.newImpactCandidate); assertNotNull(h.ui.pending)
        }
    }


    // Crossed regressions for the integration of #22 and #23–#30 (synthetic data only).
    @Test fun `active aim and released candidate both exclude equipment editing without dropping the proposal`() {
        Harness().use { h ->
            val before = h.ui.saved; val token = h.aim()
            h.controller.beginEquipmentEdit()
            assertNull(h.ui.equipmentEdit); assertEquals(token, h.ui.activeAim!!.token)
            h.controller.finishPreciseAim(token, eight); val candidate = h.ui.newImpactCandidate
            h.controller.beginEquipmentEdit(); h.controller.confirmEquipment()
            assertNull(h.ui.equipmentEdit); assertSame(candidate, h.ui.newImpactCandidate)
            assertSame(before, h.ui.saved); assertTrue(h.repository.calls.isEmpty())
            assertEquals(0, h.allocations); assertEquals(0, h.clock.calls)
            h.controller.cancelNewImpactCandidate(); h.controller.beginEquipmentEdit()
            assertNotNull(h.ui.equipmentEdit)
        }
    }

    @Test fun `equipment candidate blocks quick precise and navigation then cancellation restores aiming`() {
        Harness().use { h ->
            h.controller.setPrecisionAim(true); val context = h.ui.aimContext!!
            h.controller.beginEquipmentEdit(); val selection = h.ui.equipmentEdit
            h.controller.setPrecisionAim(false); h.controller.targetTapped(eight); h.controller.addImpact(eight)
            assertNull(h.controller.beginPreciseAim(context, eight))
            h.controller.returnToList(); h.controller.validateEnd(); h.controller.completeSession()
            assertSame(selection, h.ui.equipmentEdit); assertEquals(SessionScreen.SESSION, h.ui.screen)
            assertTrue(h.ui.precisionAimEnabled); assertTrue(h.repository.calls.isEmpty())
            h.controller.cancelEquipmentEdit(); h.release(eight); h.controller.confirmNewImpactCandidate()
            assertEquals(8, h.ui.provisionalTotal); assertEquals(1, h.ui.editingEnd!!.impacts.size)
        }
    }

    @Test fun `failed precise save excludes metadata and keeps exact proposal through retry`() {
        Harness().use { h ->
            h.release(); val candidate = h.ui.newImpactCandidate
            h.repository.next = SessionUpdateResult.TechnicalFailure(IOException("Synthetic integration write failure"))
            h.controller.confirmNewImpactCandidate(); val pending = h.ui.pending!!
            h.controller.beginEquipmentEdit(); h.controller.confirmEquipment()
            assertNull(h.ui.equipmentEdit); assertSame(candidate, h.ui.newImpactCandidate)
            assertSame(pending, h.ui.pending); assertTrue(h.ui.editingEnd!!.impacts.isEmpty())
            h.controller.retrySave()
            assertSame(pending.expected, h.repository.calls.last().first)
            assertSame(pending.proposed, h.repository.calls.last().second)
            assertNull(h.ui.pending); assertNull(h.ui.newImpactCandidate)
            h.controller.beginEquipmentEdit(); assertNotNull(h.ui.equipmentEdit)
            assertEquals(1, h.allocations); assertEquals(1, h.clock.calls)
        }
    }

    @Test fun `V1 and V2 export exclude aim and metadata then serialize only confirmed state`() {
        for (version in listOf(1, 2)) Harness().use { h ->
            var reads = 0; var captured: ByteArray? = null
            val coordinator = ExportCoordinator(
                ArchiveSource { assertEquals(1, version); reads++; ArchiveReadResult.Found(h.repository.stored) },
                ExportDocumentWriter { _, bytes -> captured = bytes; ExportReceipt("synthetic", bytes.size, "synthetic") },
                h.scope, h.controller::acquireExport, h.controller::releaseExport, h.clock, Dispatchers.Unconfined,
                v2Source = ArchiveV2Source {
                    assertEquals(2, version); reads++
                    ArchiveV2ReadResult.Found(assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(
                        h.repository.stored.profiles, h.repository.stored.sessions, listOf(bow), listOf(arrows)))).archive)
                })
            fun begin() { if (version == 1) coordinator.begin() else coordinator.beginV2() }
            val token = h.aim(); begin(); assertIs<ExportState.Failed>(coordinator.state.value)
            assertEquals(token, h.ui.activeAim!!.token)
            h.controller.finishPreciseAim(token, nine); val candidate = h.ui.newImpactCandidate
            begin(); assertIs<ExportState.Failed>(coordinator.state.value); assertSame(candidate, h.ui.newImpactCandidate)
            h.controller.cancelNewImpactCandidate(); h.controller.beginEquipmentEdit()
            begin(); assertIs<ExportState.Failed>(coordinator.state.value); assertNotNull(h.ui.equipmentEdit)
            assertEquals(0, reads); assertTrue(h.repository.calls.isEmpty())
            h.controller.chooseEquipment(EquipmentKind.BOW, bow.uuid); h.controller.confirmEquipment()
            h.release(eight); h.controller.confirmNewImpactCandidate()
            val confirmed = h.repository.stored
            begin(); assertIs<ExportState.Choosing>(coordinator.state.value)
            assertEquals(1, reads); assertTrue(h.ui.archiveOperationLocked)
            assertNull(h.controller.beginPreciseAim(h.ui.aimContext!!, nine))
            h.controller.beginEquipmentEdit(); assertNull(h.ui.equipmentEdit)
            assertNotNull(coordinator.claimDestinationRequest()); coordinator.destinationChosenForTest("synthetic://document")
            assertIs<ExportState.Succeeded>(coordinator.state.value); assertFalse(h.ui.archiveOperationLocked)
            when (val decoded = ArchiveJsonCodec().decodeVersioned(assertNotNull(captured))) {
                is VersionedArchiveJsonRead.V1 -> { assertEquals(1, version); assertEquals(confirmed.toSnapshot(), decoded.archive.toSnapshot()) }
                is VersionedArchiveJsonRead.V2 -> {
                    assertEquals(2, version); assertEquals(confirmed.sessions, decoded.archive.sessions)
                    assertEquals(listOf(bow), decoded.archive.bows); assertEquals(listOf(arrows), decoded.archive.arrowSets)
                }
                else -> fail("Confirmed export must reconstruct entirely")
            }
            assertEquals(bow.uuid, h.ui.session!!.bowUuid); assertEquals(8, h.ui.provisionalTotal)
        }
    }

    @Test fun `stale pointer callbacks cannot escape metadata or the catalog route`() {
        Harness().use { h ->
            val oldContext = h.ui.aimContext!!; val oldToken = h.aim()
            h.controller.cancelPreciseAim(oldToken); h.controller.beginEquipmentEdit()
            h.controller.movePreciseAim(oldToken, eight); h.controller.finishPreciseAim(oldToken, eight)
            h.controller.cancelPreciseAim(oldToken)
            assertNotNull(h.ui.equipmentEdit); assertFalse(h.ui.hasAimProposal)
            h.controller.cancelEquipmentEdit(); h.controller.returnToList()
            val catalog = EquipmentController(equipment, h.scope, h.controller::acquireImport,
                h.controller::releaseArchiveOperation, h.clock)
            catalog.open(); assertTrue(catalog.state.value.open); assertTrue(h.ui.archiveOperationLocked)
            h.controller.openSession(oldContext.sessionUuid); h.controller.configure()
            assertNull(h.controller.beginPreciseAim(oldContext, eight))
            h.controller.finishPreciseAim(oldToken, eight); assertEquals(SessionScreen.LIST, h.ui.screen)
            catalog.close(); h.controller.openSession(oldContext.sessionUuid)
            val current = h.aim(eight)
            h.controller.cancelPreciseAim(oldToken); h.controller.finishPreciseAim(oldToken, nine)
            assertEquals(current, h.ui.activeAim!!.token)
            h.controller.finishPreciseAim(current, eight); h.controller.confirmNewImpactCandidate()
            assertEquals(1, h.repository.calls.size); assertEquals(8, h.ui.provisionalTotal)
        }
    }

    @Test fun `conflicted precise addition stays separate from concurrent metadata until explicit reload`() {
        Harness().use { h ->
            h.release(); val before = h.ui.saved!!
            val concurrent = archive(before.sessions.single().withEquipmentReferences(bow.uuid, arrows.uuid, now))
            h.repository.stored = concurrent
            h.repository.next = SessionUpdateResult.Conflict(StorageDiagnostic("session", "Synthetic concurrent association"))
            h.controller.confirmNewImpactCandidate(); val pending = h.ui.pending!!; val candidate = h.ui.newImpactCandidate
            h.controller.beginEquipmentEdit(); h.controller.retrySave(); h.controller.previewStored()
            assertNull(h.ui.equipmentEdit); assertSame(pending, h.ui.pending); assertSame(candidate, h.ui.newImpactCandidate)
            assertEquals(before, h.ui.saved); assertEquals(concurrent.toSnapshot(), h.ui.storedPreview!!.toSnapshot())
            assertEquals(1, h.repository.calls.size); assertEquals(concurrent, h.repository.stored)
            h.controller.reloadAndDiscard()
            assertNull(h.ui.pending); assertFalse(h.ui.hasAimProposal); assertEquals(concurrent.toSnapshot(), h.ui.saved!!.toSnapshot())
            assertEquals(bow.uuid, h.ui.session!!.bowUuid); assertTrue(h.ui.editingEnd!!.impacts.isEmpty())
        }
    }

    @Test fun `interrupted precise write retains candidate while a fresh controller reads only committed metadata`() = runBlocking<Unit> {
        val h = Harness(seed().withEquipmentReferences(bow.uuid, arrows.uuid, created))
        h.use {
            val before = h.repository.stored; h.release(eight); val candidate = h.ui.newImpactCandidate
            h.repository.gate = CompletableDeferred(); h.controller.confirmNewImpactCandidate()
            val pending = h.ui.pending!!; h.scope.cancel(); yield()
            assertSame(pending, h.ui.pending); assertSame(candidate, h.ui.newImpactCandidate)
            assertEquals(before, h.repository.stored); assertEquals(ProblemKind.TECHNICAL, h.ui.problem!!.kind)
            h.controller.beginEquipmentEdit(); assertNull(h.ui.equipmentEdit)
            val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val fresh = SessionController(h.repository, freshScope, h.clock, equipmentRepository = equipment)
                fresh.openSession(before.sessions.single().uuid)
                assertEquals(before.toSnapshot(), fresh.state.value.saved!!.toSnapshot()); assertFalse(fresh.state.value.hasAimProposal)
                assertFalse(fresh.state.value.precisionAimEnabled); assertEquals(arrows.uuid, fresh.state.value.session!!.arrowSetUuid)
            } finally { freshScope.cancel() }
        }
    }

    @Test fun `precise judgment reposition and exceptional correction survive an association revision intact`() {
        Harness().use { h ->
            h.release(); h.controller.confirmNewImpactCandidate()
            val end = h.ui.editingEnd!!; val original = end.impacts.single()
            h.controller.selectImpact(end.uuid, original.uuid); h.controller.beginScoreCorrection()
            val boundary = h.ui.availableBoundaries.first { it.inner.score == 10 && it.outer.score == 9 }
            h.controller.selectBoundary(boundary.reference); h.controller.selectScoreValue(boundary.inner)
            h.controller.confirmScoreDecision(); val judged = h.ui.selectedImpact!!
            assertEquals(10, judged.scoreFinal); assertTrue(judged.isCordDecisionManual)
            h.controller.beginReposition(); h.release(eight); h.controller.confirmReposition()
            assertEquals(2, h.repository.calls.size) // Missing reason cannot invalidate the manual decision.
            h.controller.setRepositionReason("Synthetic integration reposition"); h.controller.confirmReposition()
            val moved = h.ui.selectedImpact!!
            assertEquals(8, moved.scoreFinal); assertEquals(judged.finalScoreDecision, moved.repositions.single().previousDecision)
            h.controller.beginScoreCorrection(); h.controller.chooseExceptionalCorrection()
            h.controller.selectScoreValue(BoundaryValue(9)); h.controller.setExceptionalReason("Synthetic observed nine")
            h.controller.confirmScoreDecision(); h.controller.cancelImpactEdit()
            val before = h.ui.session!!; val history = before.ends
            h.controller.beginEquipmentEdit(); h.controller.chooseEquipment(EquipmentKind.BOW, bow.uuid)
            h.controller.chooseEquipment(EquipmentKind.ARROW_SET, arrows.uuid); h.controller.confirmEquipment()
            val after = h.ui.session!!; val impact = after.ends.single().impacts.single()
            assertEquals(history, after.ends); assertEquals(original.uuid, impact.uuid)
            assertEquals(original.createdAt, impact.createdAt); assertEquals(original.inputMode, impact.inputMode)
            assertEquals(8, impact.scoreCalculated); assertEquals(9, impact.scoreFinal); assertFalse(impact.isMiss); assertFalse(impact.isX)
            assertEquals(moved.repositions, impact.repositions); assertEquals(9, h.ui.provisionalTotal)
            assertEquals(bow.uuid, after.bowUuid); assertEquals(arrows.uuid, after.arrowSetUuid)
            assertEquals(1, h.allocations); assertEquals(5, h.repository.calls.size)
        }
    }

    @Test fun `metadata double confirmation and old aim callbacks cannot allocate an arrow before acknowledgement`() = runBlocking<Unit> {
        Harness(seed(1)).use { h ->
            val token = h.aim(); h.controller.cancelPreciseAim(token)
            val before = h.ui.session!!; h.controller.beginEquipmentEdit()
            h.controller.chooseEquipment(EquipmentKind.BOW, bow.uuid); h.repository.gate = CompletableDeferred()
            h.controller.confirmEquipment(); val pending = h.ui.pending!!
            h.controller.confirmEquipment(); h.controller.confirmNewImpactCandidate()
            h.controller.finishPreciseAim(token, eight); h.controller.targetTapped(eight)
            assertEquals(before, h.ui.session); assertSame(pending, h.ui.pending); assertEquals(1, h.repository.calls.size)
            assertEquals(0, h.allocations); h.repository.gate!!.complete(Unit); yield()
            assertEquals(before.ends, h.ui.session!!.ends); assertEquals(bow.uuid, h.ui.session!!.bowUuid)
            assertNull(h.ui.equipmentEdit); assertFalse(h.ui.hasAimProposal)
            h.release(eight); h.controller.confirmNewImpactCandidate()
            assertEquals(2, h.ui.editingEnd!!.impacts.size); assertEquals(17, h.ui.provisionalTotal)
        }
    }

    @Test fun `failed metadata revision preserves exact expected and blocks aiming until explicit retry succeeds`() {
        Harness(seed(1)).use { h ->
            h.controller.setPrecisionAim(true); val before = h.ui.saved!!
            h.controller.beginEquipmentEdit(); h.controller.chooseEquipment(EquipmentKind.BOW, bow.uuid)
            h.repository.next = SessionUpdateResult.TechnicalFailure(IOException("Synthetic metadata failure"))
            h.controller.confirmEquipment(); val pending = h.ui.pending!!
            assertNull(h.controller.beginPreciseAim(h.ui.aimContext!!, eight))
            h.controller.confirmNewImpactCandidate(); h.controller.targetTapped(eight)
            assertEquals(before, h.ui.saved); assertSame(pending, h.ui.pending); assertNotNull(h.ui.equipmentEdit)
            h.controller.retrySave()
            assertSame(pending.expected, h.repository.calls.last().first); assertSame(pending.proposed, h.repository.calls.last().second)
            assertEquals(before.sessions.single().ends, h.ui.session!!.ends); assertNull(h.ui.equipmentEdit)
            assertTrue(h.ui.canAim); assertEquals(0, h.allocations)
        }
    }
}
