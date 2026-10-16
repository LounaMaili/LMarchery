package com.lmarchery.feature.sessions

import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.data.SessionRepository
import kotlinx.coroutines.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ImpactRepositionControllerTest {
    private val created = Instant.parse("2026-10-16T08:00:00.123456789Z")
    private val now = created.plusSeconds(10)
    private val target = IndoorProfile.RECURVE_40.target
    private val center = NormalizedPoint(0f, 0f)
    private val seven = NormalizedPoint(0.35f, 0f)
    private val miss = NormalizedPoint(1.1f, 0f)
    private fun id() = UUID.randomUUID().toString()
    private fun seed(manual: ScoreDecisionKind? = null, input: InputMode = InputMode.MANUAL, count: Int = 3): Session {
        val impacts = listOf(center, if (manual == null) center else NormalizedPoint(0.105f, 0f), miss).take(count).mapIndexed { i, p ->
            ArrowImpact(id(), i + 1, p, inputMode = if (i == 1) input else InputMode.MANUAL,
                createdAt = created.plusSeconds(1), updatedAt = created.plusSeconds(1), targetType = target)
        }.toMutableList()
        if (manual != null) impacts[1] = impacts[1].withFinalScore(target, when (manual) {
            ScoreDecisionKind.BOUNDARY_JUDGMENT -> ScoreDecisionRequest.JudgeBoundary(
                BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f), BoundaryValue(10), created.plusSeconds(2))
            ScoreDecisionKind.EXCEPTIONAL_CORRECTION -> ScoreDecisionRequest.ExceptionalCorrection(
                BoundaryValue(5), "Synthetic original decision", created.plusSeconds(2))
            else -> error("Manual fixture required")
        }, created.plusSeconds(2))
        val end = End(id(), 1, EndStatus.EDITING, impacts, createdAt = created,
            updatedAt = impacts.maxOf { it.updatedAt })
        return Session(id(), SessionDateTime.fromInstant(created, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic reposition", targetType = target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true,
            scoringMode = ScoringMode.TRAINING, createdAt = created, updatedAt = created).start(created).withEnd(end)
    }
    private fun archive(session: Session): DomainArchive {
        val profile = BuiltInTargetRegistry.find(session.targetType.uuid)!!
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), listOf(session)))).archive
    }
    private class CountingClock(private val time: Instant) : Clock() {
        var calls = 0
        override fun instant(): Instant { calls++; return time }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }
    private class Repository(var stored: DomainArchive) : SessionRepository {
        val calls = mutableListOf<Pair<DomainArchive, DomainArchive>>()
        var gate: CompletableDeferred<Unit>? = null
        var result: SessionUpdateResult? = null
        var thrown: Exception? = null
        var readFailure = false
        override suspend fun discover() = SessionDiscoveryResult.Found(listOf(stored.sessions.single().uuid))
        override suspend fun read(uuid: String): SessionReadResult = if (readFailure)
            SessionReadResult.TechnicalFailure(IOException("Synthetic read error"))
        else SessionReadResult.Found(stored.sessions.single(), stored.profiles.single())
        override suspend fun create(archive: DomainArchive): SessionCreateResult = error("No creation during reposition")
        override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult {
            calls += expected to proposed
            gate?.await()
            thrown?.let { throw it }
            result?.let { result = null; return it }
            if (expected.toSnapshot() != stored.toSnapshot()) return SessionUpdateResult.Conflict(StorageDiagnostic("session", "Synthetic conflict"))
            validateSessionUpdate(expected.sessions.single(), proposed.sessions.single())
            stored = proposed
            return SessionUpdateResult.Updated(proposed.sessions.single().uuid)
        }
    }
    private inner class Harness(session: Session = seed(), time: Instant = now) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = Repository(archive(session))
        val clock = CountingClock(time)
        val controller = SessionController(repository, scope, clock, newUuid = { error("Reposition must not allocate UUIDs") })
            .also { it.openSession(session.uuid) }
        val ui get() = controller.state.value
        fun select(index: Int = 2) {
            val end = ui.displayedEnd!!
            controller.selectImpact(end.uuid, end.impacts.single { it.index == index }.uuid)
        }
        fun propose(point: NormalizedPoint = seven) { select(); controller.beginReposition(); controller.targetTapped(point) }
        override fun close() { scope.cancel() }
    }

    @Test fun `overlapping impacts are selected unambiguously by UUID and list index`() {
        Harness().use { h ->
            val end = h.ui.editingEnd!!
            assertEquals(end.impacts[0].point, end.impacts[1].point)
            h.select(1); assertEquals(end.impacts[0].uuid, h.ui.selectedImpact!!.uuid)
            h.select(2); assertEquals(end.impacts[1].uuid, h.ui.selectedImpact!!.uuid)
            assertEquals(ImpactEditPhase.SELECTED, h.ui.impactEdit!!.phase)
            assertTrue(h.ui.canBeginReposition)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `missing impact or wrong original end is diagnosed without changing selection`() {
        Harness().use { h ->
            h.select(); val selection = h.ui.impactEdit
            h.controller.selectImpact(h.ui.editingEnd!!.uuid, id())
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertSame(selection, h.ui.impactEdit)
            h.controller.selectImpact(id(), h.ui.selectedImpact!!.uuid)
            assertSame(selection, h.ui.impactEdit)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `full end can reposition and candidate changes neither saved scores nor arrow count`() {
        Harness().use { h ->
            val expected = h.ui.saved!!
            assertFalse(h.ui.canAddImpact)
            h.propose()
            assertTrue(h.ui.canProposeReposition)
            assertTrue(h.ui.canConfirmReposition)
            assertEquals(ImpactEditPhase.CANDIDATE, h.ui.impactEdit!!.phase)
            assertEquals(7, h.ui.impactEdit!!.preview!!.scoreFinal)
            assertEquals(seven, h.ui.impactEdit!!.candidate)
            assertSame(expected, h.ui.saved)
            assertEquals(10, h.ui.selectedImpact!!.scoreFinal)
            assertEquals(20, h.ui.provisionalTotal)
            assertEquals(3, h.ui.displayedEnd!!.impacts.size)
            assertTrue(h.repository.calls.isEmpty())
            assertEquals(0, h.clock.calls)
        }
    }

    @Test fun `selected arrow requires explicit begin and never adds a fourth arrow`() {
        Harness().use { h ->
            h.controller.targetTapped(seven)
            h.select(); h.controller.targetTapped(seven); h.controller.confirmReposition()
            assertEquals(ImpactEditPhase.SELECTED, h.ui.impactEdit!!.phase)
            assertNull(h.ui.impactEdit!!.candidate)
            assertEquals(3, h.ui.displayedEnd!!.impacts.size)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `reposition in a partial end cannot fall through to adding an available third arrow`() {
        Harness(seed(count = 2)).use { h ->
            assertTrue(h.ui.canAddImpact)
            h.propose()
            assertFalse(h.ui.canAddImpact)
            h.controller.addImpact(miss)
            h.controller.targetTapped(seven)
            assertEquals(2, h.ui.displayedEnd!!.impacts.size)
            assertTrue(h.repository.calls.isEmpty())
            h.controller.confirmReposition()
            assertEquals(2, h.ui.displayedEnd!!.impacts.size)
            assertEquals(7, h.ui.selectedImpact!!.scoreFinal)
            assertEquals(1, h.repository.calls.size)
            h.controller.cancelImpactEdit()
            assertTrue(h.ui.canAddImpact)
        }
    }

    @Test fun `cancel waiting or candidate is purely local and restores normal commands`() {
        Harness().use { h ->
            val expected = h.ui.saved!!
            h.select(); h.controller.beginReposition(); h.controller.cancelImpactEdit()
            assertNull(h.ui.impactEdit)
            h.propose(); h.controller.cancelImpactEdit()
            assertNull(h.ui.impactEdit)
            assertSame(expected, h.ui.saved)
            assertTrue(h.ui.canValidate)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `identical point is rejected without write trace or stale candidate`() {
        Harness().use { h ->
            val expected = h.ui.saved!!
            h.propose(); h.controller.targetTapped(center); h.controller.confirmReposition()
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertTrue(h.ui.problem!!.message.contains("différente"))
            assertEquals(ImpactEditPhase.AWAITING_POINT, h.ui.impactEdit!!.phase)
            assertNull(h.ui.impactEdit!!.candidate)
            assertFalse(h.ui.canConfirmReposition)
            assertSame(expected, h.ui.saved)
            assertTrue(h.ui.selectedImpact!!.repositions.isEmpty())
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `double confirmation during suspended save issues one update and waits for SQL acknowledgement`() = runBlocking<Unit> {
        Harness().use { h ->
            val expected = h.ui.saved!!
            val old = h.ui.editingEnd!!.impacts[1]
            val gate = CompletableDeferred<Unit>(); h.repository.gate = gate
            h.propose(); h.controller.confirmReposition(); h.controller.confirmReposition()
            assertTrue(h.ui.busy)
            assertEquals(ImpactEditPhase.SAVING, h.ui.impactEdit!!.phase)
            assertSame(expected, h.ui.saved)
            assertEquals(old, h.ui.selectedImpact)
            assertNotNull(h.ui.pending)
            assertEquals(seven, h.ui.impactEdit!!.candidate)
            h.controller.cancelImpactEdit(); h.controller.returnToList(); h.controller.configure()
            h.controller.addImpact(miss); h.controller.targetTapped(miss); h.controller.selectEnd(id())
            h.controller.selectImpact(h.ui.editingEnd!!.uuid, old.uuid)
            h.controller.beginReposition(); h.controller.validateEnd(); h.controller.completeSession(); h.controller.newEnd()
            h.controller.openSession(expected.sessions.single().uuid); h.controller.retrySave()
            assertEquals(1, h.repository.calls.size)
            gate.complete(Unit); yield()
            assertFalse(h.ui.busy)
            assertNull(h.ui.pending)
            assertEquals(ImpactEditPhase.CONFIRMED, h.ui.impactEdit!!.phase)
            val moved = h.ui.selectedImpact!!
            assertEquals(old.uuid, moved.uuid); assertEquals(old.index, moved.index)
            assertEquals(old.createdAt, moved.createdAt); assertEquals(old.inputMode, moved.inputMode)
            assertEquals(now, moved.updatedAt); assertEquals(seven, moved.point)
            assertEquals(7, moved.scoreCalculated); assertEquals(7, moved.scoreFinal)
            assertEquals(old.finalScoreDecision, moved.repositions.single().previousDecision)
            assertEquals(now, moved.repositions.single().changedAt)
            assertEquals(expected.sessions.single().ends[0].impacts.filter { it.uuid != old.uuid },
                h.ui.displayedEnd!!.impacts.filter { it.uuid != old.uuid })
            assertEquals(10, old.scoreFinal); assertEquals(center, old.point); assertTrue(old.repositions.isEmpty())
            assertEquals(17, h.ui.provisionalTotal)
            assertEquals(0, h.ui.validatedTotal)
            assertEquals(now, h.ui.editingEnd!!.updatedAt); assertEquals(now, h.ui.session!!.updatedAt)
            assertFalse(h.ui.canValidate)
            h.controller.cancelImpactEdit(); assertTrue(h.ui.canValidate)
            assertEquals(1, h.clock.calls)
        }
    }

    @Test fun `successive moves to M and back retain every previous decision and original input mode`() {
        val session = seed(input = InputMode.CORRECTED)
        val original = session.ends.single().impacts[1]
        Harness(session).use { h ->
            h.propose(miss); h.controller.confirmReposition()
            assertTrue(h.ui.selectedImpact!!.isMiss)
            assertEquals(0, h.ui.selectedImpact!!.scoreFinal)
            h.controller.beginReposition(); h.controller.targetTapped(seven); h.controller.confirmReposition()
            val moved = h.ui.selectedImpact!!
            assertFalse(moved.isMiss); assertEquals(7, moved.scoreFinal); assertFalse(moved.isX)
            assertEquals(2, moved.repositions.size)
            assertEquals(listOf(center, miss), moved.repositions.map { it.previousPoint })
            assertEquals(listOf(10, 0), moved.repositions.map { it.previousDecision.scoreFinal })
            assertEquals(original.inputMode, moved.inputMode)
            assertEquals(3, h.ui.displayedEnd!!.impacts.size)
            assertEquals(2, h.repository.calls.size)
        }
    }

    @Test fun `manual boundary or exceptional decision requires reason and survives only as invalidated evidence`() {
        listOf(ScoreDecisionKind.BOUNDARY_JUDGMENT, ScoreDecisionKind.EXCEPTIONAL_CORRECTION).forEach { kind ->
            Harness(seed(kind)).use { h ->
                val old = h.ui.editingEnd!!.impacts[1]
                h.propose()
                assertEquals(7, h.ui.impactEdit!!.preview!!.scoreFinal)
                assertFalse(h.ui.canConfirmReposition)
                h.controller.confirmReposition()
                assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
                assertTrue(h.repository.calls.isEmpty())
                h.controller.setRepositionReason("   "); h.controller.confirmReposition()
                assertTrue(h.repository.calls.isEmpty())
                h.controller.setRepositionReason(" Position mal saisie ")
                assertTrue(h.ui.canConfirmReposition)
                h.controller.confirmReposition()
                val moved = h.ui.selectedImpact!!
                assertEquals(ScoreDecisionKind.GEOMETRY, moved.finalScoreDecision.provenance.kind)
                assertEquals(old.finalScoreDecision, moved.repositions.single().invalidatedManualDecision)
                assertEquals("Position mal saisie", moved.repositions.single().reason)
                assertEquals(7, moved.scoreFinal)
            }
        }
    }

    @Test fun `technical failure retains exact pending candidate and retry never reissues time or evidence`() {
        Harness().use { h ->
            val expected = h.ui.saved!!
            h.repository.result = SessionUpdateResult.TechnicalFailure(IOException("Synthetic disk error"))
            h.propose(); h.controller.confirmReposition()
            val pending = h.ui.pending!!
            val edit = h.ui.impactEdit!!
            assertSame(expected, h.ui.saved)
            assertEquals(ImpactEditPhase.FAILED, edit.phase)
            assertEquals(seven, edit.candidate)
            assertEquals(7, edit.preview!!.scoreFinal)
            h.controller.cancelImpactEdit(); h.controller.proposeReposition(miss); h.controller.selectEnd(id())
            h.controller.beginReposition(); h.controller.confirmReposition(); h.controller.setRepositionReason("Changed")
            assertSame(pending, h.ui.pending); assertSame(edit, h.ui.impactEdit)
            h.controller.retrySave()
            assertSame(pending.expected, h.repository.calls[1].first)
            assertSame(pending.proposed, h.repository.calls[1].second)
            assertEquals(1, h.clock.calls)
            assertEquals(1, h.ui.selectedImpact!!.repositions.size)
            assertEquals(ImpactEditPhase.CONFIRMED, h.ui.impactEdit!!.phase)
        }
    }

    @Test fun `conflict and failed reload preserve candidate until explicit successful abandonment`() {
        Harness().use { h ->
            val expected = h.ui.saved!!
            h.repository.stored = archive(expected.sessions.single().copy(title = "External revision"))
            h.propose(); h.controller.confirmReposition()
            val pending = h.ui.pending!!; val edit = h.ui.impactEdit!!
            assertEquals(ProblemKind.CONFLICT, h.ui.problem!!.kind)
            assertSame(expected, pending.expected)
            h.controller.retrySave(); assertEquals(1, h.repository.calls.size)
            h.controller.previewStored()
            assertEquals("External revision", h.ui.storedPreview!!.sessions.single().title)
            assertSame(pending, h.ui.pending); assertSame(edit, h.ui.impactEdit); assertSame(expected, h.ui.saved)
            h.controller.closePreview(); h.repository.readFailure = true; h.controller.reloadAndDiscard()
            assertSame(pending, h.ui.pending); assertSame(edit, h.ui.impactEdit)
            h.repository.readFailure = false; h.controller.reloadAndDiscard()
            assertNull(h.ui.pending); assertNull(h.ui.impactEdit)
            assertEquals("External revision", h.ui.session!!.title)
            assertEquals(center, h.ui.displayedEnd!!.impacts[1].point)
        }
    }

    @Test fun `intervention or missing stored session cannot be retried or silently cancelled`() {
        listOf(SessionUpdateResult.NotFound,
            SessionUpdateResult.NeedsIntervention(StorageDiagnostic("trace", "Synthetic invalid history"))).forEach { result ->
            Harness().use { h ->
                h.repository.result = result
                h.propose(); h.controller.confirmReposition()
                val pending = h.ui.pending!!
                h.controller.retrySave(); h.controller.cancelImpactEdit(); h.controller.returnToList()
                assertSame(pending, h.ui.pending)
                assertEquals(ImpactEditPhase.FAILED, h.ui.impactEdit!!.phase)
                assertEquals(1, h.repository.calls.size)
                h.controller.discardAndReturnToList() // UI warns and requires explicit abandonment.
                assertNull(h.ui.pending); assertNull(h.ui.impactEdit)
                assertEquals(SessionScreen.LIST, h.ui.screen)
                assertEquals(center, h.repository.stored.sessions.single().ends[0].impacts[1].point)
            }
        }
    }

    @Test fun `repository exception preserves submitted revision and selected identity`() {
        Harness().use { h ->
            h.repository.thrown = IOException("Synthetic exception")
            h.propose(); h.controller.confirmReposition()
            assertEquals(ProblemKind.TECHNICAL, h.ui.problem!!.kind)
            assertEquals(ImpactEditPhase.FAILED, h.ui.impactEdit!!.phase)
            assertEquals(seven, h.ui.pending!!.proposed.sessions.single().ends[0].impacts[1].point)
            assertEquals(center, h.ui.selectedImpact!!.point)
        }
    }

    @Test fun `cancellation of suspended reposition never announces success`() = runBlocking<Unit> {
        val h = Harness()
        h.repository.gate = CompletableDeferred()
        h.propose(); h.controller.confirmReposition(); h.close(); yield()
        assertNotNull(h.ui.pending)
        assertEquals(ImpactEditPhase.FAILED, h.ui.impactEdit!!.phase)
        assertEquals(center, h.ui.selectedImpact!!.point)
        assertNull(h.ui.confirmation)
        assertEquals(1, h.repository.calls.size)
    }

    @Test fun `backwards clock is diagnosed without shifting time writing or discarding candidate`() {
        Harness(time = created).use { h ->
            val expected = h.ui.saved
            h.propose(); h.controller.confirmReposition()
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertTrue(h.ui.problem!!.message.contains("horloge"))
            assertNull(h.ui.pending)
            assertSame(expected, h.ui.saved)
            assertEquals(ImpactEditPhase.CANDIDATE, h.ui.impactEdit!!.phase)
            assertEquals(seven, h.ui.impactEdit!!.candidate)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `validated completed and unsupported configurations cannot select or reposition`() {
        val initial = seed()
        val validated = initial.withEnd(initial.ends.single().validate(now))
        listOf(validated, validated.complete(now), initial.copy(arrowCountPerEnd = 6),
            initial.copy(scoringMode = ScoringMode.COMPETITION)).forEach { session ->
            Harness(session).use { h ->
                assertFalse(h.ui.canSelectImpact)
                h.propose(); h.controller.confirmReposition()
                assertNull(h.ui.impactEdit)
                assertTrue(h.repository.calls.isEmpty())
                assertEquals(archive(session).toSnapshot(), h.ui.saved!!.toSnapshot())
            }
        }
    }

    @Test fun `end navigation cannot steal a candidate and reopening a controller restores only persisted values`() {
        val first = seed()
        val validated = first.ends.single().validate(now)
        val second = End(id(), 2, EndStatus.EDITING, listOf(ArrowImpact(id(), 1, center, inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = target)), createdAt = now, updatedAt = now)
        val session = first.withEnd(validated).withEnd(second)
        Harness(session).use { h ->
            h.proposeForFirstArrow()
            val selection = h.ui.impactEdit!!
            h.controller.selectEnd(validated.uuid); h.controller.returnToList(); h.controller.configure()
            assertEquals(second.uuid, h.ui.displayedEnd!!.uuid)
            assertSame(selection, h.ui.impactEdit)
            // Reattaching observers to the retained controller (as the ViewModel does) changes no proposal.
            assertSame(selection, h.controller.state.value.impactEdit)
            val reopened = SessionController(h.repository, h.scope, Clock.fixed(now, ZoneOffset.UTC))
            reopened.openSession(session.uuid)
            assertNull(reopened.state.value.impactEdit)
            assertEquals(center, reopened.state.value.displayedEnd!!.impacts.single().point)
            h.controller.cancelImpactEdit(); h.controller.selectEnd(validated.uuid)
            assertEquals(validated.uuid, h.ui.displayedEnd!!.uuid)
            assertFalse(h.ui.canSelectImpact)
            assertTrue(h.repository.calls.isEmpty())
        }
    }
    private fun Harness.proposeForFirstArrow() { select(1); controller.beginReposition(); controller.targetTapped(seven) }

    @Test fun `unrepresentable finite candidate is diagnosed without producing a business distance`() {
        Harness().use { h ->
            h.select(); h.controller.beginReposition()
            h.controller.targetTapped(NormalizedPoint(Float.MAX_VALUE, Float.MAX_VALUE))
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertNull(h.ui.impactEdit!!.candidate)
            assertFalse(h.ui.canConfirmReposition)
            assertTrue(h.repository.calls.isEmpty())
        }
    }
}
