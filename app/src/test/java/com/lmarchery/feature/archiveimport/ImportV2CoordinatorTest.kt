package com.lmarchery.feature.archiveimport

import com.lmarchery.backup.V2TestData.archive
import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.ImportDocumentReader
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

class ImportV2CoordinatorTest {
    private inner class Harness(val source: DomainArchiveV2 = archive()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var bytes = ArchiveJsonCodec().encode(source)
        var reads = 0; var previews = 0; var commits = 0; var legacyCalls = 0; var refreshes = 0
        var locked = false; var refreshError = false
        var known = archive(false, false, false)
        var received: DomainArchiveV2? = null
        var readGate: CompletableDeferred<Unit>? = null
        var previewGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        var result: ArchiveV2ImportResult = ArchiveV2ImportResult.Imported(ArchiveV2Counts(1, 1, 1, 1), ArchiveV2Counts(0, 0, 0, 0))
        var preparation: PreparedV2Result? = null
        val coordinator = ImportCoordinator(ImportDocumentReader { reads++; readGate?.await(); bytes },
            object : ArchiveImporter {
                override suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult { legacyCalls++; error("V2 cannot enter V1") }
                override suspend fun importArchive(preview: ArchiveImportPreview): ArchiveImportResult { legacyCalls++; error("V2 cannot enter V1") }
            }, scope, acquire = { if (locked) false else { locked = true; true } }, release = { locked = false },
            refresh = { refreshes++; if (refreshError) throw IOException("Synthetic refresh") }, dispatcher = Dispatchers.Unconfined,
            v2Repository = ArchiveV2Importer { input ->
                previews++; received = input; previewGate?.await()
                preparation ?: PreparedV2Result.Ready(object : PreparedV2Import {
                    override val plan = planArchiveImportV2(input, known)
                    override suspend fun commit(): ArchiveV2ImportResult { commits++; commitGate?.await(); return result }
                })
            })
        val state get() = coordinator.state.value
        fun select() { coordinator.begin(); assertTrue(coordinator.claimSelection()); coordinator.selected("content://synthetic/v2") }
        override fun close() = scope.cancel()
    }

    @Test fun `V2 uses only its own APIs and retains the exact decoded source until explicit confirmation`() {
        Harness().use { h ->
            h.select(); assertIs<ImportState.PreviewV2>(h.state); assertTrue(h.locked); assertEquals(0, h.commits)
            h.bytes = "provider changed after reading".toByteArray()
            h.coordinator.confirm(); assertIs<ImportState.SucceededV2>(h.state)
            assertEquals(h.source, h.received); assertEquals(1, h.reads); assertEquals(1, h.previews)
            assertEquals(1, h.commits); assertEquals(0, h.legacyCalls); assertFalse(h.locked)
        }
    }
    @Test fun `equipment only profiles only and empty archives reach a V2 preview`() {
        listOf(archive(false, false), archive(false, true, false), archive(false, false, false)).forEach { a ->
            Harness(a).use { h -> h.select(); val p = assertIs<ImportState.PreviewV2>(h.state)
                assertEquals(a.bows.size, p.plan.bows.newUuids.size); assertEquals(a.profiles.size, p.plan.profiles.newUuids.size)
                assertEquals(a.sessions.isEmpty() && a.profiles.isEmpty() && a.bows.isEmpty(), p.plan.isEmptyArchive)
                h.coordinator.cancel(); h.coordinator.confirm(); assertEquals(0, h.commits)
            }
        }
    }
    @Test fun `four category presentation distinguishes new identical and conflicting entries`() {
        val incoming = archive()
        val known = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(incoming.toSnapshot().copy(
            sessions = emptyList(), bows = listOf(incoming.toSnapshot().bows!!.single().copy(name = "Other"))))).archive
        val plan = planArchiveImportV2(incoming, known)
        val lines = v2PlanLines(plan)
        assertEquals(4, lines.size); assertTrue(lines[0].contains("1 nouveau(x)")); assertTrue(lines[1].contains("1 identique(s)"))
        assertTrue(lines[2].contains("1 conflictuel(s)")); assertTrue(lines[3].contains("1 identique(s)"))
        Harness().use { h -> h.known = known; h.select(); assertIs<ImportState.ConflictV2>(h.state)
            h.coordinator.confirm(); assertEquals(0, h.commits); assertFalse(h.locked) }
    }
    @Test fun `identical and empty confirmations preserve explicit no write results`() {
        listOf(archive(), archive(false, false, false)).forEach { a ->
            Harness(a).use { h ->
                h.known = a
                val counts = ArchiveV2Counts(a.sessions.size, a.profiles.size, a.bows.size, a.arrowSets.size)
                h.result = ArchiveV2ImportResult.AlreadyPresent(counts, a.profiles.isEmpty())
                h.select(); h.coordinator.confirm(); val s = assertIs<ImportState.SucceededV2>(h.state)
                assertTrue(s.alreadyPresent); assertEquals(counts, s.identical); assertTrue(v2SuccessText(s).contains("aucune écriture"))
            }
        }
    }
    @Test fun `double callbacks and confirmations execute a single read preview and commit`() = runBlocking<Unit> {
        Harness().use { h ->
            h.readGate = CompletableDeferred(); h.select(); h.coordinator.begin(); h.coordinator.selected("content://other")
            assertEquals(1, h.reads); h.readGate!!.complete(Unit); yield()
            h.commitGate = CompletableDeferred(); h.coordinator.confirm(); h.coordinator.confirm(); h.coordinator.cancel()
            assertIs<ImportState.Importing>(h.state); assertEquals(1, h.commits); assertEquals(0, h.refreshes)
            h.commitGate!!.complete(Unit); yield(); assertIs<ImportState.SucceededV2>(h.state); assertEquals(1, h.refreshes)
        }
    }
    @Test fun `rotation reattachment keeps preview while fresh process never resumes a lost candidate`() {
        Harness().use { h -> h.select(); val original = h.state; val attached = h.coordinator
            assertEquals(original, attached.state.value); assertFalse(attached.claimSelection()); attached.confirm()
            assertEquals(1, h.reads); assertEquals(1, h.commits) }
        Harness().use { h -> h.coordinator.selected("content://stale"); assertEquals(ImportState.Unconfirmed(true), h.state)
            h.coordinator.confirm(); assertEquals(0, h.reads); assertEquals(0, h.commits) }
    }
    @Test fun `late stale preview SQL failure and corrupt state never report success or retry`() {
        listOf<ArchiveV2ImportResult>(ArchiveV2ImportResult.Conflict(StorageDiagnostic("archive", "Stale preview")),
            ArchiveV2ImportResult.TechnicalFailure(IOException("Late SQL or lost COMMIT acknowledgement")),
            ArchiveV2ImportResult.NeedsIntervention(StorageDiagnostic("bows[id]", "Corrupt data"))).forEach { result ->
            Harness().use { h -> h.result = result; h.select(); h.coordinator.confirm()
                assertFalse(h.state is ImportState.SucceededV2); assertFalse(h.locked); assertEquals(0, h.refreshes)
                if (result is ArchiveV2ImportResult.TechnicalFailure) assertEquals(ImportState.Unconfirmed(true), h.state)
                h.coordinator.confirm(); assertEquals(1, h.commits) }
        }
    }
    @Test fun `preview storage failures and malformed sources are rejected before any commit`() {
        listOf<PreparedV2Result>(PreparedV2Result.NeedsIntervention(StorageDiagnostic("identities", "Orphan")),
            PreparedV2Result.TechnicalFailure(IOException("Reader failure"))).forEach { r ->
            Harness().use { h -> h.preparation = r; h.select(); assertIs<ImportState.Failed>(h.state); assertEquals(0, h.commits) }
        }
        Harness().use { h -> h.bytes = h.bytes.copyOf(h.bytes.size - 1); h.select()
            assertIs<ImportState.Failed>(h.state); assertEquals(0, h.previews); assertEquals(0, h.commits) }
    }
    @Test fun `selection cancellation and refusing a V2 preview have no commit`() {
        Harness().use { h -> h.coordinator.begin(); h.coordinator.claimSelection(); h.coordinator.selected(null)
            assertIs<ImportState.Cancelled>(h.state); assertEquals(0, h.reads); assertFalse(h.locked)
            h.select(); h.coordinator.cancel(); h.coordinator.confirm(); assertEquals(0, h.commits); assertFalse(h.locked) }
    }
    @Test fun `coroutine cancellation distinguishes preparation from uncertain commit`() = runBlocking<Unit> {
        for (phase in 0..2) {
            val h = Harness()
            when (phase) { 0 -> h.readGate = CompletableDeferred(); 1 -> h.previewGate = CompletableDeferred(); 2 -> h.commitGate = CompletableDeferred() }
            h.select(); if (phase == 2) h.coordinator.confirm(); h.close(); yield()
            assertEquals(ImportState.Unconfirmed(phase == 2), h.state); assertFalse(h.locked)
            assertEquals(if (phase == 2) 1 else 0, h.commits); assertEquals(0, h.refreshes)
        }
    }
    @Test fun `confirmed V2 remains confirmed even when list refresh fails`() {
        Harness().use { h -> h.refreshError = true; h.select(); h.coordinator.confirm()
            assertTrue(assertIs<ImportState.SucceededV2>(h.state).refreshFailed); assertFalse(h.locked) }
    }
    @Test fun `missing V2 capability cannot fall back to V1`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val c = ImportCoordinator(ImportDocumentReader { ArchiveJsonCodec().encode(archive()) }, object : ArchiveImporter {
                override suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult = error("Forbidden fallback")
                override suspend fun importArchive(preview: ArchiveImportPreview): ArchiveImportResult = error("Forbidden fallback")
            }, scope, { true }, {}, {}, Dispatchers.Unconfined)
            c.begin(); c.claimSelection(); c.selected("content://synthetic/v2")
            assertTrue(assertIs<ImportState.Failed>(c.state.value).diagnostic.contains("Aucune conversion"))
        } finally { scope.cancel() }
    }
}
