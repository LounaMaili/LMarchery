package com.lmarchery.feature.sessions

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.summarizeSessionScore
import com.lmarchery.data.SessionRepository
import kotlinx.coroutines.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

class SessionControllerTest {
    private val now = Instant.parse("2026-10-25T01:30:00.123456789Z")
    private val paris = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(now, ZoneId.of("Asia/Tokyo"))
    private class Harness(val repository: FakeRepository = FakeRepository()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        override fun close() { scope.cancel() }
    }
    private fun controller(h: Harness, next: () -> String = { UUID.randomUUID().toString() }) =
        SessionController(h.repository, h.scope, clock, { paris }, next)
    private fun SessionController.create(profile: IndoorProfile = IndoorProfile.RECURVE_40) {
        configure(); selectProfile(profile); createSession()
        assertNotNull(state.value.session)
    }
    private fun SessionController.three() {
        addImpact(NormalizedPoint(0.075f, 0f))
        addImpact(NormalizedPoint(0.15f, 0f))
        addImpact(NormalizedPoint(1.1f, 0f))
    }
    private fun FakeRepository.only() = stored.values.single()

    @Test fun `creation is not presented as saved before repository confirmation`() = runBlocking<Unit> {
        Harness().use { h ->
            val c = controller(h)
            val gate = CompletableDeferred<Unit>()
            h.repository.createGate = gate
            c.configure(); c.createSession()
            assertTrue(c.state.value.busy)
            assertNull(c.state.value.saved)
            assertNotNull(c.state.value.pending)
            assertEquals(SessionScreen.CREATE, c.state.value.screen)
            gate.complete(Unit); yield()
            val session = c.state.value.session!!
            assertEquals(SessionStatus.IN_PROGRESS, session.status)
            assertEquals(3, session.arrowCountPerEnd)
            assertEquals(ScoringMode.TRAINING, session.scoringMode)
            assertEquals(c.state.value.saved!!.toSnapshot(), h.repository.only().toSnapshot())
            assertNull(c.state.value.pending)
        }
    }

    @Test fun `all four explicitly selected profiles retain their identity distance and edition`() {
        IndoorProfile.entries.forEach { profile -> Harness().use { h ->
            val c = controller(h); c.create(profile)
            val session = c.state.value.session!!
            assertEquals(profile.target, session.targetType)
            assertEquals(profile.target.defaultDistanceM, session.distanceM)
            assertEquals("2026-01-27", c.state.value.saved!!.profiles.single().regulatoryEdition)
            assertFalse(session.targetType.xRingEnabled)
        } }
    }

    @Test fun `three numbered geometric impacts include M without artificial indoor X`() {
        Harness().use { h ->
            val c = controller(h); c.create(IndoorProfile.COMPOUND_40); c.three()
            val arrows = c.state.value.editingEnd!!.impacts
            assertEquals(listOf(1, 2, 3), arrows.map { it.index })
            assertEquals(listOf(9, 9, 0), arrows.map { it.scoreFinal })
            assertEquals(arrows.map { it.scoreCalculated }, arrows.map { it.scoreFinal })
            assertTrue(arrows.last().isMiss)
            assertTrue(arrows.none { it.isX })
            assertTrue(arrows.all { it.inputMode == InputMode.MANUAL })
            assertFalse(c.state.value.canAddImpact)
            c.addImpact(NormalizedPoint(0f, 0f))
            assertEquals(3, h.repository.updateCalls.size)
        }
    }

    @Test fun `recurve and compound use the actual distinct ten radii`() {
        val scores = listOf(IndoorProfile.RECURVE_40, IndoorProfile.COMPOUND_40).map { profile ->
            Harness().use { h -> val c = controller(h); c.create(profile); c.addImpact(NormalizedPoint(0.075f, 0f))
                c.state.value.editingEnd!!.impacts.single().scoreFinal }
        }
        assertEquals(listOf(10, 9), scores)
    }

    @Test fun `empty or partial ends cannot validate create another end or complete`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.validateEnd(); c.newEnd()
            assertEquals(0, h.repository.updateCalls.size)
            c.addImpact(NormalizedPoint(0f, 0f)); c.validateEnd(); c.completeSession(); c.newEnd()
            assertEquals(1, h.repository.updateCalls.size)
            assertEquals(SessionStatus.IN_PROGRESS, c.state.value.session!!.status)
            assertEquals(EndStatus.EDITING, c.state.value.editingEnd!!.status)
        }
    }

    @Test fun `validation persists status and separates provisional from validated total`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.three()
            assertEquals(19, c.state.value.provisionalTotal)
            assertEquals(0, c.state.value.validatedTotal)
            c.validateEnd()
            assertEquals(EndStatus.VALIDATED, h.repository.only().sessions.single().ends.single().status)
            assertEquals(0, c.state.value.provisionalTotal)
            assertEquals(19, c.state.value.validatedTotal)
            c.addImpact(NormalizedPoint(0f, 0f))
            assertEquals(3, c.state.value.displayedEnd!!.impacts.size)
        }
    }

    @Test fun `two successive ends preserve all six UUIDs indices and validated statistics`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.three(); c.validateEnd()
            val first = c.state.value.session!!.ends.single()
            c.newEnd(); c.three()
            assertEquals(19, c.state.value.validatedTotal)
            assertEquals(19, c.state.value.provisionalTotal)
            c.validateEnd(); c.completeSession()
            val s = h.repository.only().sessions.single()
            assertEquals(SessionStatus.COMPLETED, s.status)
            assertEquals(listOf(1, 2), s.ends.map { it.index })
            assertEquals(first, s.ends[0])
            assertEquals(6, s.ends.flatMap { it.impacts }.map { it.uuid }.distinct().size)
            assertTrue(s.ends.all { it.impacts.map { a -> a.index } == listOf(1, 2, 3) })
            assertEquals(38, summarizeSessionScore(s).totalScore)
            assertEquals(2, summarizeSessionScore(s).missCount)
            assertEquals(0, summarizeSessionScore(s).xCount)
        }
    }

    @Test fun `new controller discovers and resumes exact partial session without rewriting`() {
        val repository = FakeRepository()
        lateinit var expected: DomainArchive
        Harness(repository).use { h -> val c = controller(h); c.create();
            c.addImpact(NormalizedPoint(0.075f, -0.0f)); c.addImpact(NormalizedPoint(0.25f, 0f))
            expected = c.state.value.saved!! }
        val writes = repository.updateCalls.size
        Harness(repository).use { h ->
            val resumed = controller(h)
            resumed.refresh()
            val uuid = resumed.state.value.entries.single().uuid
            resumed.openSession(uuid)
            assertEquals(expected.toSnapshot(), resumed.state.value.saved!!.toSnapshot())
            assertEquals(writes, repository.updateCalls.size)
            assertEquals(1, repository.createCalls.size)
            resumed.addImpact(NormalizedPoint(1.1f, 0f)); resumed.validateEnd()
            assertEquals(3, resumed.state.value.displayedEnd!!.impacts.size)
        }
    }

    @Test fun `completed sessions remain discoverable read only and never reopen`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.three(); c.validateEnd(); c.completeSession()
            val before = h.repository.only().toSnapshot()
            val writes = h.repository.updateCalls.size
            c.returnToList(); c.openSession(before.sessions.single().uuid)
            assertEquals(SessionStatus.COMPLETED, c.state.value.session!!.status)
            c.addImpact(NormalizedPoint(0f, 0f)); c.newEnd(); c.startSession(); c.validateEnd(); c.completeSession()
            assertEquals(writes, h.repository.updateCalls.size)
            assertEquals(before, h.repository.only().toSnapshot())
        }
    }

    @Test fun `empty next end permits completion without deleting that end`() {
        Harness().use { h -> val c = controller(h); c.create(); c.three(); c.validateEnd(); c.newEnd(); c.completeSession()
            assertEquals(SessionStatus.COMPLETED, c.state.value.session!!.status)
            assertEquals(2, c.state.value.session!!.ends.size)
            assertTrue(c.state.value.session!!.ends.last().impacts.isEmpty())
            assertEquals(19, c.state.value.validatedTotal)
        }
    }

    @Test fun `double tap while write suspended produces only one saved arrow`() = runBlocking<Unit> {
        Harness().use { h ->
            val c = controller(h); c.create()
            val gate = CompletableDeferred<Unit>(); h.repository.updateGate = gate
            c.addImpact(NormalizedPoint(0.075f, 0f)); c.addImpact(NormalizedPoint(0.15f, 0f))
            assertTrue(c.state.value.busy)
            assertTrue(c.state.value.session!!.ends.single().impacts.isEmpty())
            assertEquals(1, h.repository.updateCalls.size)
            gate.complete(Unit); yield()
            assertEquals(1, c.state.value.editingEnd!!.impacts.size)
            assertEquals(10, c.state.value.editingEnd!!.impacts.single().scoreFinal)
        }
    }

    @Test fun `double creation tap generates exactly one operation and two entity UUIDs`() = runBlocking<Unit> {
        Harness().use { h ->
            var generated = 0
            val c = controller(h) { generated++; UUID.randomUUID().toString() }
            val gate = CompletableDeferred<Unit>(); h.repository.createGate = gate
            c.configure(); c.createSession(); c.createSession()
            assertEquals(1, h.repository.createCalls.size)
            assertEquals(2, generated)
            gate.complete(Unit); yield()
            assertEquals(1, h.repository.stored.size)
        }
    }

    @Test fun `failed write retains exact pending action UUIDs expected and confirmed scores for explicit retry`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            val expected = c.state.value.saved!!
            h.repository.nextUpdate = SessionUpdateResult.TechnicalFailure(IOException("Synthetic disk failure"))
            c.addImpact(NormalizedPoint(0.075f, 0f))
            val pending = c.state.value.pending!!
            assertSame(expected, c.state.value.saved)
            assertTrue(c.state.value.editingEnd!!.impacts.isEmpty())
            assertEquals(ProblemKind.TECHNICAL, c.state.value.problem!!.kind)
            c.addImpact(NormalizedPoint(0.5f, 0f)); c.returnToList()
            assertSame(pending, c.state.value.pending)
            c.retrySave()
            assertSame(pending.expected, h.repository.updateCalls[1].first)
            assertSame(pending.proposed, h.repository.updateCalls[1].second)
            assertEquals(pending.proposed.toSnapshot(), c.state.value.saved!!.toSnapshot())
            assertNull(c.state.value.pending)
        }
    }

    @Test fun `uncertain creation can retry identically and confirm AlreadyExists explicitly`() {
        Harness().use { h ->
            val c = controller(h)
            h.repository.createThenFail = true
            c.configure(); c.createSession()
            val pending = c.state.value.pending!!
            assertNull(c.state.value.saved)
            c.retrySave()
            assertSame(pending.proposed, h.repository.createCalls.last())
            assertEquals(1, h.repository.stored.size)
            assertTrue(c.state.value.confirmation!!.contains("déjà enregistré"))
        }
    }

    @Test fun `conflict never replaces expected or retries and preview does not abandon local action`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            val expected = c.state.value.saved!!
            val current = rearchive(expected.sessions.single().copy(title = "External revision"))
            h.repository.stored[expected.sessions.single().uuid] = current
            c.addImpact(NormalizedPoint(0.15f, 0f))
            val pending = c.state.value.pending!!
            assertEquals(ProblemKind.CONFLICT, c.state.value.problem!!.kind)
            assertSame(expected, pending.expected)
            c.retrySave()
            assertEquals(1, h.repository.updateCalls.size)
            c.previewStored()
            assertEquals(current.toSnapshot(), c.state.value.storedPreview!!.toSnapshot())
            assertSame(pending, c.state.value.pending)
            assertSame(expected, c.state.value.saved)
            c.closePreview()
            assertEquals(ProblemKind.CONFLICT, c.state.value.problem!!.kind)
            c.reloadAndDiscard() // Explicit user confirmation, never called by preview itself.
            assertNull(c.state.value.pending)
            assertEquals(current.toSnapshot(), c.state.value.saved!!.toSnapshot())
            assertEquals(1, h.repository.updateCalls.size)
        }
    }

    @Test fun `intervention keeps attempted action blocks changes and does not retry automatically`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            h.repository.nextUpdate = SessionUpdateResult.NeedsIntervention(StorageDiagnostic("trace", "Synthetic corruption"))
            c.addImpact(NormalizedPoint(0f, 0f))
            assertNotNull(c.state.value.pending)
            assertEquals(ProblemKind.INTERVENTION, c.state.value.problem!!.kind)
            c.retrySave(); c.newEnd(); c.completeSession()
            assertEquals(1, h.repository.updateCalls.size)
            assertTrue(c.state.value.editingEnd!!.impacts.isEmpty())
        }
    }

    @Test fun `missing session never triggers recreation and failed reload preserves pending`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            h.repository.stored.clear()
            c.addImpact(NormalizedPoint(0f, 0f))
            val pending = c.state.value.pending
            assertEquals(ProblemKind.NOT_FOUND, c.state.value.problem!!.kind)
            c.reloadAndDiscard()
            assertSame(pending, c.state.value.pending)
            assertEquals(1, h.repository.createCalls.size)
        }
    }

    @Test fun `listing reports every unreadable entry beside readable sessions without writes`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            h.repository.unreadable["broken"] = SessionReadResult.NeedsIntervention(StorageDiagnostic("sessions", "Corrupt root"))
            h.repository.unreadable["missing"] = SessionReadResult.NotFound
            h.repository.unreadable["failure"] = SessionReadResult.TechnicalFailure(IOException("Synthetic IO"))
            val before = h.repository.createCalls.size to h.repository.updateCalls.size
            c.returnToList()
            val entries = c.state.value.entries
            assertEquals(4, entries.size)
            assertEquals(1, entries.count { it.session != null })
            assertEquals(setOf(ProblemKind.INTERVENTION, ProblemKind.NOT_FOUND, ProblemKind.TECHNICAL), entries.mapNotNull { it.problem?.kind }.toSet())
            assertEquals(before, h.repository.createCalls.size to h.repository.updateCalls.size)
        }
    }

    @Test fun `two sessions are isolated while navigating and adding impacts`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.addImpact(NormalizedPoint(0.075f, 0f))
            val first = c.state.value.saved!!
            c.returnToList(); c.create(IndoorProfile.COMPOUND_60); c.addImpact(NormalizedPoint(0.075f, 0f))
            assertEquals(9, c.state.value.editingEnd!!.impacts.single().scoreFinal)
            c.returnToList(); c.openSession(first.sessions.single().uuid)
            assertEquals(first.toSnapshot(), c.state.value.saved!!.toSnapshot())
            assertEquals(2, h.repository.stored.size)
        }
    }

    @Test fun `UUIDs are v4 and immutable dates retain nanoseconds explicit IANA and overlap offset`() {
        Harness().use { h ->
            val c = controller(h); c.create(); c.addImpact(NormalizedPoint(0f, 0f))
            val s = c.state.value.session!!
            (listOf(s.uuid) + s.ends.map { it.uuid } + s.ends.flatMap { it.impacts }.map { it.uuid }).forEach { id ->
                assertEquals(4, UUID.fromString(id).version())
                assertEquals(2, UUID.fromString(id).variant())
            }
            assertEquals(now, s.createdAt); assertEquals(now, s.startedAt); assertEquals(now, s.updatedAt)
            assertEquals(paris, s.date.zoneId)
            assertEquals("+01:00", s.date.offset.id)
            assertEquals("2026-10-25T02:30:00.123456789", s.date.localDateTime.toString())
            assertEquals(now, s.ends.single().impacts.single().createdAt)
        }
    }

    @Test fun `technical discovery failure is visible and explicit refresh can recover`() {
        Harness().use { h ->
            val c = controller(h); h.repository.discoveryFailure = IOException("Synthetic open error")
            c.refresh()
            assertEquals(ProblemKind.TECHNICAL, c.state.value.problem!!.kind)
            assertFalse(c.state.value.busy)
            h.repository.discoveryFailure = null; c.refresh()
            assertNull(c.state.value.problem)
            assertTrue(c.state.value.entries.isEmpty())
        }
    }

    @Test fun `unexpected repository exception keeps action and confirmed state`() {
        Harness().use { h ->
            val c = controller(h); c.create(); h.repository.throwOnUpdate = IOException("Synthetic exception")
            c.addImpact(NormalizedPoint(0f, 0f))
            assertEquals(ProblemKind.TECHNICAL, c.state.value.problem!!.kind)
            assertNotNull(c.state.value.pending)
            assertTrue(c.state.value.editingEnd!!.impacts.isEmpty())
        }
    }

    @Test fun `scope cancellation cannot announce a pending arrow as saved`() = runBlocking<Unit> {
        val h = Harness()
        val c = controller(h); c.create()
        h.repository.updateGate = CompletableDeferred()
        c.addImpact(NormalizedPoint(0f, 0f)); h.close(); yield()
        assertNotNull(c.state.value.pending)
        assertTrue(c.state.value.editingEnd!!.impacts.isEmpty())
        assertNull(c.state.value.confirmation)
        assertFalse(c.state.value.busy)
    }

    @Test fun `unconfirmed action can only be discarded by explicit navigation action`() {
        Harness().use { h ->
            val c = controller(h); c.create()
            h.repository.nextUpdate = SessionUpdateResult.TechnicalFailure(IOException("Synthetic IO"))
            c.addImpact(NormalizedPoint(0f, 0f)); c.returnToList()
            assertEquals(SessionScreen.SESSION, c.state.value.screen)
            assertNotNull(c.state.value.pending)
            c.discardAndReturnToList()
            assertEquals(SessionScreen.LIST, c.state.value.screen)
            assertNull(c.state.value.pending)
            assertTrue(h.repository.only().sessions.single().ends.single().impacts.isEmpty())
        }
    }

    @Test fun `other valid configurations are consultable without implicit conversion`() {
        Harness().use { h ->
            val p = IndoorProfile.RECURVE_40.target
            val draft = Session(UUID.randomUUID().toString(), SessionDateTime.fromInstant(now, paris), SessionStatus.DRAFT,
                title = "Six arrows", targetType = p, distanceM = 18, arrowCountMode = ArrowCountMode.FIXED,
                arrowCountPerEnd = 6, isIndoor = true, scoringMode = ScoringMode.COMPETITION, createdAt = now, updatedAt = now)
            val archive = rearchive(draft)
            h.repository.stored[draft.uuid] = archive
            val c = controller(h); c.refresh(); c.openSession(draft.uuid); c.startSession()
            assertFalse(c.state.value.supportedConfiguration)
            assertEquals(archive.toSnapshot(), c.state.value.saved!!.toSnapshot())
            assertTrue(h.repository.updateCalls.isEmpty())
        }
    }

    private class FakeRepository : SessionRepository {
        val stored = linkedMapOf<String, DomainArchive>()
        val unreadable = linkedMapOf<String, SessionReadResult>()
        val createCalls = mutableListOf<DomainArchive>()
        val updateCalls = mutableListOf<Pair<DomainArchive, DomainArchive>>()
        var createGate: CompletableDeferred<Unit>? = null
        var updateGate: CompletableDeferred<Unit>? = null
        var nextUpdate: SessionUpdateResult? = null
        var createThenFail = false
        var throwOnUpdate: Exception? = null
        var discoveryFailure: Exception? = null
        override suspend fun discover(): SessionDiscoveryResult = discoveryFailure?.let { SessionDiscoveryResult.TechnicalFailure(it) }
            ?: SessionDiscoveryResult.Found((stored.keys + unreadable.keys).distinct().sorted())
        override suspend fun read(uuid: String): SessionReadResult = unreadable[uuid] ?: stored[uuid]?.let {
            SessionReadResult.Found(it.sessions.single(), it.profiles.single())
        } ?: SessionReadResult.NotFound
        override suspend fun create(archive: DomainArchive): SessionCreateResult {
            createCalls += archive; createGate?.await()
            val uuid = archive.sessions.single().uuid
            stored[uuid]?.let { return if (it.toSnapshot() == archive.toSnapshot()) SessionCreateResult.AlreadyExists(uuid)
                else SessionCreateResult.Conflict(StorageDiagnostic("session", "Divergence")) }
            stored[uuid] = archive
            if (createThenFail) { createThenFail = false; return SessionCreateResult.TechnicalFailure(IOException("Unconfirmed commit")) }
            return SessionCreateResult.Created(uuid)
        }
        override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult {
            updateCalls += expected to proposed; updateGate?.await()
            throwOnUpdate?.let { throw it }
            nextUpdate?.let { nextUpdate = null; return it }
            val uuid = expected.sessions.single().uuid
            val old = stored[uuid] ?: return SessionUpdateResult.NotFound
            if (old.toSnapshot() != expected.toSnapshot()) return SessionUpdateResult.Conflict(StorageDiagnostic("session", "Divergence"))
            if (old.toSnapshot() == proposed.toSnapshot()) return SessionUpdateResult.Unchanged(uuid)
            validateSessionUpdate(expected.sessions.single(), proposed.sessions.single())
            stored[uuid] = proposed
            return SessionUpdateResult.Updated(uuid)
        }
    }
}

private fun rearchive(s: Session): DomainArchive {
    val profile = requireNotNull(BuiltInTargetRegistry.find(s.targetType.uuid))
    return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
        listOf(TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), listOf(s)))).archive
}
