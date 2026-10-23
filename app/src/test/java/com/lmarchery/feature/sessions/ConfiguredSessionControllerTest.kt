package com.lmarchery.feature.sessions

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.backup.V2TestData as old
import com.lmarchery.core.database.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.data.*
import kotlinx.coroutines.*
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.*

/** Presentation/controller doubles. No SQL or physical test is inferred from these checks. */
class ConfiguredSessionControllerTest {
    private class Harness(completed: Boolean = false) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var writes = 0; var v3Reads = 0; var gate: CompletableDeferred<Unit>? = null
        val legacy = old.session()
        val mixed = f.archive(completed)
        var legacyResult: SessionReadResult = SessionReadResult.Found(legacy, TargetProfileSnapshot(legacy.targetType, "2026-01-27"))
        var sourceResult: ArchiveV3ReadResult = ArchiveV3ReadResult.Found(mixed)
        val extra = mutableMapOf<String, SessionReadResult>()
        val repo = object : SessionRepository {
            override suspend fun discover() = SessionDiscoveryResult.Found(listOf(legacy.uuid, f.id(1)) + extra.keys)
            override suspend fun read(uuid: String): SessionReadResult = extra[uuid] ?: if (uuid == legacy.uuid) legacyResult
                else SessionReadResult.NeedsIntervention(StorageDiagnostic("session_configurations[$uuid]", "CONFIGURED requires V3"))
            override suspend fun create(archive: DomainArchive): SessionCreateResult { writes++; error("No creation expected") }
            override suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult { writes++; error("No revision expected") }
        }
        val controller = SessionController(repo, scope, Clock.fixed(f.now.plusSeconds(30), ZoneOffset.UTC),
            v3Source = ArchiveV3Source { v3Reads++; gate?.await(); sourceResult })
        val ui get() = controller.state.value
        override fun close() = scope.cancel()
    }
    @Test fun `discovery keeps legacy editable and configured readable rather than reporting it corrupt`() {
        Harness().use { h -> h.controller.refresh()
            val history = presentSessionHistory(h.ui.entries); assertEquals(2, history.resumable.size); assertTrue(history.intervention.isEmpty())
            assertEquals(h.mixed.sessions.single(), history.resumable.single { it.configured != null }.configured)
            assertEquals(1, h.v3Reads); assertEquals(0, h.writes)
        }
    }
    @Test fun `mixed and completed configured roots stay read only regardless of visual homogeneity`() {
        for (complete in listOf(false, true)) Harness(complete).use { h ->
            h.controller.openSession(f.id(1)); assertEquals(h.mixed.sessions.single(), h.ui.configuredSaved)
            assertNull(h.ui.saved); assertNull(h.ui.session); assertFalse(h.ui.canEdit); assertFalse(h.ui.canEditEquipment)
            assertFalse(h.ui.canAddImpact); assertFalse(h.ui.canAim); assertFalse(h.ui.canValidate); assertFalse(h.ui.canComplete)
            assertFalse(h.ui.canNewEnd); assertFalse(h.ui.supportedConfiguration); assertEquals(0, h.writes)
        }
    }
    @Test fun `all historical mutation and correction commands are inert for configured view`() {
        Harness().use { h -> val c = h.controller; c.openSession(f.id(1)); val exact = h.ui.configuredSaved
            c.addImpact(NormalizedPoint(.1f, 0f)); c.targetTapped(NormalizedPoint(.2f, 0f)); c.startSession(); c.newEnd(); c.validateEnd(); c.completeSession()
            c.selectImpact(f.id(2), f.id(10)); c.beginReposition(); c.proposeReposition(NormalizedPoint(.4f, 0f)); c.confirmReposition()
            c.beginScoreCorrection(); c.confirmScoreDecision(); c.beginEquipmentEdit(); c.confirmEquipment(); c.setPrecisionAim(true)
            assertNull(c.beginPreciseAim(AimContext(f.id(1), f.id(2)), NormalizedPoint(.3f, 0f)))
            assertSame(exact, h.ui.configuredSaved); assertEquals(0, h.writes); assertNull(h.ui.pending); assertNull(h.ui.impactEdit)
        }
    }
    @Test fun `navigation never leaves legacy saved data active behind a configured screen`() {
        Harness().use { h -> val c = h.controller; c.openSession(h.legacy.uuid); assertNotNull(h.ui.saved)
            c.openSession(f.id(1)); assertNull(h.ui.saved); assertNotNull(h.ui.configuredSaved)
            c.openSession(h.legacy.uuid); assertNull(h.ui.configuredSaved); assertEquals(h.legacy, h.ui.session)
            c.openSession(f.id(1)); c.returnToList(); assertNull(h.ui.configuredSaved); assertNull(h.ui.saved); assertEquals(0, h.writes)
        }
    }
    @Test fun `corrupt V3 complete source cannot enable partial configured display`() {
        Harness().use { h -> h.sourceResult = ArchiveV3ReadResult.NeedsIntervention(ArchiveV3StorageDiagnostic(
            ArchiveV3StorageIssueKind.DOMAIN, "sessions[id].ends[0].configuration", "Invalid capture"))
            h.controller.refresh(); assertEquals(1, presentSessionHistory(h.ui.entries).intervention.size)
            h.controller.openSession(f.id(1)); assertNull(h.ui.configuredSaved); assertEquals(ProblemKind.INTERVENTION, h.ui.problem!!.kind)
            assertTrue(h.ui.problem!!.message.contains("ends[0].configuration")); assertEquals(0, h.writes)
        }
    }
    @Test fun `technical failed and missing complete source remain visible and cannot fall back to initial profile`() {
        for (result in listOf<ArchiveV3ReadResult>(ArchiveV3ReadResult.TechnicalFailure(IOException("Synthetic IO")), ArchiveV3ReadResult.Found(f.empty()))) {
            Harness().use { h -> h.sourceResult = result; h.controller.openSession(f.id(1)); assertNull(h.ui.configuredSaved)
                assertNotNull(h.ui.problem); assertNull(h.ui.saved); assertEquals(0, h.writes) }
        }
    }
    @Test fun `legacy technical failure is not hidden behind a V3 fallback`() {
        Harness().use { h -> h.legacyResult = SessionReadResult.TechnicalFailure(IOException("Legacy read failed"))
            h.controller.openSession(h.legacy.uuid); assertEquals(0, h.v3Reads); assertEquals(ProblemKind.TECHNICAL, h.ui.problem!!.kind) }
    }
    @Test fun `read has no durable display before complete V3 acknowledgement and blocks navigation`() = runBlocking<Unit> {
        Harness().use { h -> h.gate = CompletableDeferred(); h.controller.openSession(f.id(1)); assertTrue(h.ui.busy)
            assertNull(h.ui.configuredSaved); h.controller.returnToList(); h.controller.configure(); assertEquals(SessionScreen.LIST, h.ui.screen)
            h.gate!!.complete(Unit); yield(); assertNotNull(h.ui.configuredSaved); assertFalse(h.ui.busy); assertEquals(0, h.writes) }
    }
    @Test fun `cancelled read never leaves a partly built configured view`() = runBlocking<Unit> {
        val h = Harness(); h.gate = CompletableDeferred(); h.controller.openSession(f.id(1)); h.close(); yield()
        assertNull(h.ui.configuredSaved); assertFalse(h.ui.busy); assertEquals(ProblemKind.TECHNICAL, h.ui.problem!!.kind); assertEquals(0, h.writes)
    }
    @Test fun `restoration lock prevents navigation and configured reads without interfering with legacy state`() {
        Harness().use { h -> assertTrue(h.controller.acquireImport()); h.controller.openSession(f.id(1)); h.controller.refresh()
            assertEquals(0, h.v3Reads); assertNull(h.ui.configuredSaved); h.controller.releaseArchiveOperation()
            h.controller.openSession(f.id(1)); assertNotNull(h.ui.configuredSaved); assertTrue(h.controller.acquireExport())
            h.controller.returnToList(); assertNotNull(h.ui.configuredSaved); h.controller.releaseExport(); h.controller.returnToList(); assertNull(h.ui.configuredSaved) }
    }
    @Test fun `mixed presentation uses effective profiles exact editions and stored final decisions`() {
        val session = f.mixed(); val before = session.copy(); val view = presentConfiguredScore(session)
        val summary = assertNotNull(view.summary); assertNull(view.diagnostic)
        assertEquals(19L, summary.totals.validated.score); assertEquals(9L, summary.totals.provisional.score)
        assertEquals(1L, summary.totals.recorded.xCount); assertEquals(1L, summary.totals.recorded.missCount)
        assertEquals(2L, summary.totals.recorded.nineCount); assertEquals(1L, summary.totals.recorded.maxScoreCount)
        val compound = session.ends[1]; assertEquals(9, compound.end.impacts.single().scoreFinal)
        assertTrue(configurationLines(compound.configuration).joinToString().contains("70 m"))
        assertTrue(configurationLines(compound.configuration).joinToString().contains("2026-01-27"))
        assertTrue(session.ends.last().origin.label().contains(compound.uuid)); assertEquals(before, session)
        val impact = session.ends.first().end.impacts.first(); assertEquals(8, impact.scoreCalculated); assertEquals(9, impact.scoreFinal)
        assertEquals(1, impact.repositions.size); assertNotNull(impact.repositions.single().invalidatedManualDecision)
    }
    @Test fun `empty FREE zero maximum is undefined and both origins remain distinguishable`() {
        val initial = f.classic.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null)
        val s = ConfiguredSession.create(f.id(50), f.mixed().metadata, initial, f.now).start(f.now)
            .createNextEnd(f.id(51), f.now).createNextEnd(f.id(52), f.now, NextEndConfigurationChoice.Replace(initial))
        val summary = presentConfiguredScore(s).summary!!; assertEquals(0L, summary.totals.recorded.recordedCapacityMaximum)
        assertNull(summary.totals.recorded.percentageOfRecordedCapacity)
        assertTrue(s.ends.first().origin.label().contains("INITIAL")); assertTrue(s.ends.last().origin.label().contains("EXPLICIT"))
        assertTrue(configurationLines(initial).joinToString().contains("FREE")); assertEquals(0, s.ends.first().end.impacts.size)
    }
    @Test fun `contradictory presentation entries remain incidents including wrong owner and both representations`() {
        val a = f.mixed(); val b = old.session()
        val entries = listOf(SessionEntry("wrong", configured = a), SessionEntry(a.uuid, b, configured = a), SessionEntry(a.uuid))
        val history = presentSessionHistory(entries); assertEquals(entries.sortedBy { it.uuid }, history.intervention); assertTrue(history.resumable.isEmpty())
    }
    @Test fun `completed and active configured history uses civil instants and never revision times`() {
        val active = f.mixed(); val completed = f.mixed(true)
        val view = presentSessionHistory(listOf(SessionEntry(active.uuid, configured = active), SessionEntry(completed.uuid, configured = completed)))
        assertEquals(active, view.resumable.single().configured); assertEquals(completed, view.completed.single().configured)
        assertTrue(active.civilDateLabel().contains("Europe/Paris (+02:00)"))
    }
    @Test fun `extreme valid capacities show a typed unavailable total instead of crashing or zero`() {
        val huge = f.classic.targetType.copy(uuid = f.id(990), isBuiltIn = false, maxScore = Int.MAX_VALUE,
            minScore = Int.MAX_VALUE, xRingEnabled = false, zones = listOf(
                com.lmarchery.core.model.target.TargetZone(Int.MAX_VALUE, 0f, 1f, "all", "#FFFFFF")))
        val initial = f.classic.copy(targetType = huge, arrowCountPerEnd = Int.MAX_VALUE)
        var s = ConfiguredSession.create(f.id(991), f.mixed().metadata, initial, f.now).start(f.now)
        for (n in 1..3) s = s.createNextEnd(f.id(991 + n), f.now)
        val view = presentConfiguredScore(s); assertNull(view.summary)
        assertTrue(assertNotNull(view.diagnostic).contains("NUMERIC_OVERFLOW")); assertEquals(3, s.ends.size)
    }

}
