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

class ManualScoreDecisionControllerTest {
    private val created = Instant.parse("2026-10-16T08:00:00.123456789Z")
    private val now = created.plusSeconds(10)
    private val target = IndoorProfile.RECURVE_40.target
    private val center = NormalizedPoint(0f, 0f)
    private val seven = NormalizedPoint(0.35f, 0f)
    private val miss = NormalizedPoint(1.1f, 0f)
    private fun id() = UUID.randomUUID().toString()
    private fun seed(point: NormalizedPoint = NormalizedPoint(0.105f, -0.0f),
        profile: IndoorProfile = IndoorProfile.RECURVE_40): Session {
        val target = profile.target
        val impacts = listOf(center, point, miss).mapIndexed { i, p -> ArrowImpact(id(), i + 1, p,
            inputMode = InputMode.MANUAL, createdAt = created.plusSeconds(1), updatedAt = created.plusSeconds(1), targetType = target) }
        val end = End(id(), 1, EndStatus.EDITING, impacts, createdAt = created, updatedAt = impacts[0].updatedAt)
        return Session(id(), SessionDateTime.fromInstant(created, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic score decision", targetType = target, distanceM = target.defaultDistanceM,
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
        override suspend fun create(archive: DomainArchive): SessionCreateResult = error("No creation during scoring")
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
        val controller = SessionController(repository, scope, clock, newUuid = { error("Score decisions must not allocate UUIDs") })
            .also { it.openSession(session.uuid) }
        val ui get() = controller.state.value
        fun select(index: Int = 2) {
            val end = ui.displayedEnd!!
            controller.selectImpact(end.uuid, end.impacts.single { it.index == index }.uuid)
        }
        fun begin() { select(); controller.beginScoreCorrection() }
        fun judge(value: Int = 10, radius: Float = 0.1f, other: Boolean = false) {
            begin()
            if (other) controller.showOtherBoundaries()
            val boundary = ui.availableBoundaries.single { it.radiusNormalized == radius }
            controller.selectBoundary(boundary.reference)
            controller.selectScoreValue(BoundaryValue(value))
        }
        fun exceptional(value: Int = 5, reason: String = "Observed score") {
            begin(); controller.chooseExceptionalCorrection()
            controller.selectScoreValue(BoundaryValue(value)); controller.setExceptionalReason(reason)
        }
        override fun close() { scope.cancel() }
    }

    @Test fun `proximity alone and opening judgment never promotes score or chooses a boundary`() {
        Harness().use { h ->
            val saved = h.ui.saved
            h.begin()
            assertIs<ImpactProposal.BoundaryJudgment>(h.ui.impactEdit!!.proposal)
            assertEquals(9, h.ui.selectedImpact!!.scoreFinal)
            assertEquals(listOf(0.1f), h.ui.availableBoundaries.map { it.radiusNormalized })
            assertNull(h.ui.selectedBoundary); assertNull(h.ui.proposedScore)
            assertFalse(h.ui.canConfirmScoreDecision); assertFalse(h.ui.canProposeReposition)
            h.controller.targetTapped(seven); h.controller.beginReposition()
            assertSame(saved, h.ui.saved); assertNull(h.ui.impactEdit!!.candidate)
            assertTrue(h.repository.calls.isEmpty()); assertEquals(0, h.clock.calls)
            h.controller.confirmScoreDecision()
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertNull(h.ui.pending)
        }
    }

    @Test fun `both sides of a nearby limit allow explicit inner or outer judgments`() {
        listOf(0.095f, 0.105f).forEach { radius -> listOf(10, 9).forEach { value ->
            Harness(seed(NormalizedPoint(radius, 0f))).use { h ->
                val old = h.ui.saved!!; val original = old.sessions.single().ends[0].impacts[1]
                h.judge(value); assertEquals(value, h.ui.proposedScore!!.score)
                assertEquals(original.scoreFinal, h.ui.selectedImpact!!.scoreFinal)
                h.controller.confirmScoreDecision()
                val revised = h.ui.selectedImpact!!
                assertEquals(value, revised.scoreFinal)
                assertEquals(original.scoreCalculated, revised.scoreCalculated)
                assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT, revised.finalScoreDecision.provenance.kind)
                assertEquals(BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f), revised.finalScoreDecision.provenance.boundary)
                assertEquals(now, revised.finalScoreDecision.provenance.decidedAt)
                assertEquals(original.point, revised.point); assertEquals(original.uuid, revised.uuid)
                assertEquals(original.createdAt, revised.createdAt); assertEquals(original.inputMode, revised.inputMode)
                assertEquals(original.repositions, revised.repositions); assertEquals(original.index, revised.index)
                assertEquals(original.targetType, revised.targetType)
                assertEquals(now, revised.updatedAt); assertEquals(now, h.ui.editingEnd!!.updatedAt)
                assertEquals(now, h.ui.session!!.updatedAt)
                assertEquals(old.sessions.single().ends[0].impacts.filter { it.uuid != revised.uuid },
                    h.ui.editingEnd!!.impacts.filter { it.uuid != revised.uuid })
            }
        } }
    }

    @Test fun `manual confirmation of geometric value persists as judgment and updates validated totals`() {
        Harness().use { h ->
            h.judge(9); h.controller.confirmScoreDecision()
            assertTrue(h.ui.selectedImpact!!.isCordDecisionManual)
            assertEquals(19, h.ui.provisionalTotal); assertEquals(0, h.ui.validatedTotal)
            h.controller.cancelImpactEdit(); h.controller.validateEnd()
            assertEquals(19, h.ui.validatedTotal)
            assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT,
                h.repository.stored.sessions.single().ends[0].impacts[1].finalScoreDecision.provenance.kind)
        }
    }

    @Test fun `no nearby suggestion requires secondary access before judging a genuine distant limit`() {
        Harness(seed(NormalizedPoint(0.25f, 0f))).use { h ->
            h.begin(); assertTrue(h.ui.availableBoundaries.isEmpty())
            val boundary = BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f)
            h.controller.selectBoundary(boundary)
            assertNull(h.ui.selectedBoundary); assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            h.controller.showOtherBoundaries(); h.controller.selectBoundary(boundary)
            h.controller.selectScoreValue(BoundaryValue(10)); h.controller.confirmScoreDecision()
            assertEquals(8, h.ui.selectedImpact!!.scoreCalculated)
            assertEquals(10, h.ui.selectedImpact!!.scoreFinal)
        }
    }

    @Test fun `multiple available limits require explicit choice and changing boundary clears selected side`() {
        Harness().use { h ->
            h.begin(); h.controller.showOtherBoundaries()
            assertEquals(10, h.ui.availableBoundaries.size); assertNull(h.ui.selectedBoundary)
            h.controller.selectBoundary(BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f))
            h.controller.selectScoreValue(BoundaryValue(10))
            h.controller.selectBoundary(BoundaryReference(BoundaryType.SCORE_ZONES, 0.2f))
            assertNull(h.ui.proposedScore); assertFalse(h.ui.canConfirmScoreDecision)
            h.controller.selectScoreValue(BoundaryValue(10))
            assertNull(h.ui.proposedScore)
            h.controller.selectScoreValue(BoundaryValue(8)); h.controller.confirmScoreDecision()
            assertEquals(8, h.ui.selectedImpact!!.scoreFinal)
            assertEquals(0.2f, h.ui.selectedImpact!!.finalScoreDecision.provenance.boundary!!.radiusNormalized)
        }
    }

    @Test fun `nonexistent boundary and unrelated value cannot replace a valid proposal`() {
        Harness().use { h ->
            h.judge(); val selected = h.ui.impactEdit!!.proposal
            h.controller.selectBoundary(BoundaryReference(BoundaryType.SCORE_ZONES, 0.123f))
            assertEquals(selected, h.ui.impactEdit!!.proposal)
            h.controller.selectScoreValue(BoundaryValue(5))
            assertEquals(selected, h.ui.impactEdit!!.proposal)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `target edge permits M or one without moving the impact and miss follows final value`() {
        listOf(0, 1).forEach { score -> Harness(seed(NormalizedPoint(1.005f, 0f))).use { h ->
            h.judge(score, 1f); h.controller.confirmScoreDecision()
            assertEquals(0, h.ui.selectedImpact!!.scoreCalculated)
            assertEquals(score == 0, h.ui.selectedImpact!!.isMiss)
            assertFalse(h.ui.selectedImpact!!.isX)
            assertEquals(BoundaryType.TARGET_EDGE, h.ui.selectedImpact!!.finalScoreDecision.provenance.boundary!!.type)
        } }
    }

    @Test fun `exceptional correction requires a nonblank reason and can confirm the same numeric score`() {
        Harness().use { h ->
            h.exceptional(9, "   "); assertFalse(h.ui.canConfirmScoreDecision)
            h.controller.confirmScoreDecision(); assertTrue(h.repository.calls.isEmpty())
            h.controller.setExceptionalReason(" Confirmed observation ")
            assertTrue(h.ui.canConfirmScoreDecision); h.controller.confirmScoreDecision()
            val decision = h.ui.selectedImpact!!.finalScoreDecision
            assertEquals(9, decision.scoreFinal); assertEquals(9, decision.scoreCalculated)
            assertEquals(ScoreDecisionKind.EXCEPTIONAL_CORRECTION, decision.provenance.kind)
            assertEquals("Confirmed observation", decision.provenance.reason)
            assertEquals(now, decision.provenance.decidedAt); assertNull(decision.provenance.boundary)
            assertFalse(decision.isCordDecisionManual)
        }
    }

    @Test fun `all indoor profiles reject illegal scores and X and offer exact legal values`() {
        IndoorProfile.entries.forEach { profile -> Harness(seed(profile = profile)).use { h ->
            h.begin(); h.controller.chooseExceptionalCorrection()
            assertEquals((10 downTo 0).map { BoundaryValue(it) }, h.ui.scoreOptions!!.legalValues)
            assertTrue((h.ui.scoreOptions!!.nearby + h.ui.scoreOptions!!.other).none { it.type == BoundaryType.X_RING || it.inner.isX || it.outer.isX })
            listOf(BoundaryValue(11), BoundaryValue(99), BoundaryValue(10, true)).forEach { invalid ->
                h.controller.selectScoreValue(invalid)
                assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind); assertNull(h.ui.proposedScore)
            }
            assertFailsWith<IllegalArgumentException> { BoundaryValue(-1) }
            assertTrue(h.repository.calls.isEmpty())
        } }
    }

    @Test fun `score modes are exclusive with reposition and switching resets incomplete choices`() {
        Harness().use { h ->
            h.judge(); h.controller.chooseExceptionalCorrection()
            assertIs<ImpactProposal.ExceptionalCorrection>(h.ui.impactEdit!!.proposal)
            assertNull(h.ui.selectedBoundary); assertNull(h.ui.proposedScore)
            h.controller.selectScoreValue(BoundaryValue(5)); h.controller.setExceptionalReason("Reason")
            h.controller.chooseBoundaryJudgment()
            assertIs<ImpactProposal.BoundaryJudgment>(h.ui.impactEdit!!.proposal)
            assertNull(h.ui.proposedScore); assertEquals("", h.ui.impactEdit!!.reason)
            h.controller.cancelImpactEdit(); h.select(); h.controller.beginReposition()
            h.controller.beginScoreCorrection(); h.controller.chooseExceptionalCorrection()
            assertIs<ImpactProposal.Reposition>(h.ui.impactEdit!!.proposal)
        }
    }

    @Test fun `cancel either score proposal writes nothing and does not navigate or validate implicitly`() {
        Harness().use { h ->
            val saved = h.ui.saved
            h.judge(); val proposal = h.ui.impactEdit
            h.controller.validateEnd(); h.controller.completeSession(); h.controller.newEnd()
            h.controller.returnToList(); h.controller.selectEnd(id()); h.controller.configure()
            assertSame(proposal, h.ui.impactEdit); assertSame(saved, h.ui.saved)
            h.controller.cancelImpactEdit(); assertTrue(h.ui.canValidate)
            h.exceptional(); h.controller.cancelImpactEdit()
            assertNull(h.ui.impactEdit); assertSame(saved, h.ui.saved); assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `suspended score save guards double confirmation and shows only SQL acknowledged totals`() = runBlocking<Unit> {
        Harness().use { h ->
            val saved = h.ui.saved; val gate = CompletableDeferred<Unit>(); h.repository.gate = gate
            h.judge(); h.controller.confirmScoreDecision(); h.controller.confirmScoreDecision()
            assertTrue(h.ui.busy); assertEquals(ImpactEditPhase.SAVING, h.ui.impactEdit!!.phase)
            assertSame(saved, h.ui.saved); assertEquals(19, h.ui.provisionalTotal)
            h.controller.chooseExceptionalCorrection(); h.controller.selectScoreValue(BoundaryValue(5))
            h.controller.setExceptionalReason("Other"); h.controller.cancelImpactEdit(); h.controller.beginReposition()
            h.controller.targetTapped(seven); h.controller.validateEnd(); h.controller.retrySave()
            assertEquals(1, h.repository.calls.size); assertEquals(1, h.clock.calls)
            gate.complete(Unit); yield()
            assertEquals(20, h.ui.provisionalTotal); assertEquals(0, h.ui.validatedTotal)
            assertEquals(10, h.ui.selectedImpact!!.scoreFinal); assertNull(h.ui.pending)
            assertEquals(ImpactEditPhase.CONFIRMED, h.ui.impactEdit!!.phase)
        }
    }

    @Test fun `technical retry preserves the exact expected proposed choice reason and timestamp`() {
        Harness().use { h ->
            h.repository.result = SessionUpdateResult.TechnicalFailure(IOException("Synthetic disk error"))
            h.exceptional(5, "Exact reason"); h.controller.confirmScoreDecision()
            val pending = h.ui.pending!!; val edit = h.ui.impactEdit!!
            assertEquals(ImpactEditPhase.FAILED, edit.phase); assertEquals(9, h.ui.selectedImpact!!.scoreFinal)
            h.controller.selectScoreValue(BoundaryValue(8)); h.controller.setExceptionalReason("Changed")
            h.controller.cancelImpactEdit(); h.controller.confirmScoreDecision()
            assertSame(edit, h.ui.impactEdit)
            h.controller.retrySave()
            assertSame(pending.expected, h.repository.calls[1].first); assertSame(pending.proposed, h.repository.calls[1].second)
            assertEquals(1, h.clock.calls)
            assertEquals("Exact reason", h.ui.selectedImpact!!.finalScoreDecision.provenance.reason)
            assertEquals(now, h.ui.selectedImpact!!.finalScoreDecision.provenance.decidedAt)
        }
    }

    @Test fun `conflict keeps score candidate while consulting stored revision until explicit abandonment`() {
        Harness().use { h ->
            val saved = h.ui.saved
            h.repository.stored = archive(h.ui.session!!.copy(title = "Concurrent revision"))
            h.judge(); h.controller.confirmScoreDecision()
            val pending = h.ui.pending; val edit = h.ui.impactEdit
            assertEquals(ProblemKind.CONFLICT, h.ui.problem!!.kind)
            h.controller.retrySave(); assertEquals(1, h.repository.calls.size)
            h.controller.previewStored()
            assertEquals("Concurrent revision", h.ui.storedPreview!!.sessions.single().title)
            assertSame(saved, h.ui.saved); assertSame(pending, h.ui.pending); assertSame(edit, h.ui.impactEdit)
            h.repository.readFailure = true; h.controller.reloadAndDiscard()
            assertSame(pending, h.ui.pending); assertSame(edit, h.ui.impactEdit)
            h.repository.readFailure = false; h.controller.reloadAndDiscard()
            assertNull(h.ui.impactEdit); assertNull(h.ui.pending)
            assertEquals("Concurrent revision", h.ui.session!!.title)
        }
    }

    @Test fun `backwards clock is rejected without inventing a manual timestamp`() {
        Harness(time = created).use { h ->
            val saved = h.ui.saved; h.judge(); h.controller.confirmScoreDecision()
            assertEquals(ProblemKind.INVALID_ACTION, h.ui.problem!!.kind)
            assertTrue(h.ui.problem!!.message.contains("horloge"))
            assertSame(saved, h.ui.saved); assertNull(h.ui.pending)
            assertTrue(h.ui.canConfirmScoreDecision); assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `validated completed incompatible missing and wrong end selections cannot issue decisions`() {
        val initial = seed(); val validated = initial.withEnd(initial.ends.single().validate(now))
        listOf(validated, validated.complete(now), initial.copy(arrowCountPerEnd = 6),
            initial.copy(scoringMode = ScoringMode.COMPETITION)).forEach { session -> Harness(session).use { h ->
            h.begin(); h.controller.chooseExceptionalCorrection(); h.controller.confirmScoreDecision()
            assertNull(h.ui.impactEdit); assertTrue(h.repository.calls.isEmpty())
        } }
        Harness().use { h ->
            h.controller.selectImpact(id(), h.ui.editingEnd!!.impacts[0].uuid)
            h.controller.beginScoreCorrection(); assertNull(h.ui.impactEdit)
            h.controller.selectImpact(h.ui.editingEnd!!.uuid, id())
            h.controller.beginScoreCorrection(); assertNull(h.ui.impactEdit)
            assertTrue(h.repository.calls.isEmpty())
        }
    }

    @Test fun `retained controller keeps proposal across observer reattachment and a new controller sees SQL only`() {
        Harness().use { h ->
            h.exceptional(); val edit = h.ui.impactEdit
            assertSame(edit, h.controller.state.value.impactEdit)
            val reopened = SessionController(h.repository, h.scope, Clock.fixed(now, ZoneOffset.UTC))
            reopened.openSession(h.ui.session!!.uuid)
            assertNull(reopened.state.value.impactEdit)
            assertEquals(9, reopened.state.value.displayedEnd!!.impacts[1].scoreFinal)
            assertSame(edit, h.ui.impactEdit)
        }
    }

    @Test fun `later reposition requires reason and retains the new manual decision without changing older traces`() {
        Harness().use { h ->
            h.select(); h.controller.beginReposition(); h.controller.targetTapped(seven); h.controller.confirmReposition()
            val trace = h.ui.selectedImpact!!.repositions.single()
            h.controller.beginScoreCorrection(); h.controller.chooseExceptionalCorrection()
            h.controller.selectScoreValue(BoundaryValue(5)); h.controller.setExceptionalReason("Observed five")
            h.controller.confirmScoreDecision()
            val manual = h.ui.selectedImpact!!
            assertEquals(seven, manual.point); assertEquals(listOf(trace), manual.repositions)
            h.controller.beginReposition(); h.controller.targetTapped(miss)
            h.controller.confirmReposition(); assertFalse(h.ui.canConfirmReposition)
            assertEquals(2, h.repository.calls.size)
            h.controller.setRepositionReason("Position incorrecte"); h.controller.confirmReposition()
            val moved = h.ui.selectedImpact!!
            assertEquals(trace, moved.repositions[0])
            assertEquals(manual.finalScoreDecision, moved.repositions[1].invalidatedManualDecision)
            assertTrue(moved.isMiss); assertEquals(0, moved.scoreFinal)
        }
    }
}
