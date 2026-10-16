package com.lmarchery.feature.sessions

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.data.SessionRepository
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
        val controller = SessionController(repository, scope, clock, newUuid = { allocations++; id() })
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

}
