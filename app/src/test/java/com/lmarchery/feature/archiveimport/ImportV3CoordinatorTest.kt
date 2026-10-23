package com.lmarchery.feature.archiveimport

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.ImportDocumentReader
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

/** Coordinator doubles only. Native disk/commit proofs live in core:database:jvmTest. */
class ImportV3CoordinatorTest {
    private val zero = ArchiveV3Counts(0, 0, 0, 0, 0, 0)
    private inner class Harness(val source: DomainArchiveV3 = f.archive(), capability: Boolean = true) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var bytes = ArchiveV3JsonCodec().encode(source)
        var reads = 0; var previews = 0; var commits = 0; var refreshes = 0; var releases = 0
        var locked = false; var refreshError = false
        var known = f.empty()
        val inputs = mutableListOf<DomainArchiveV3>()
        var readGate: CompletableDeferred<Unit>? = null
        var previewGate: CompletableDeferred<Unit>? = null
        var commitGate: CompletableDeferred<Unit>? = null
        var preparedResult: PreparedV3Result? = null
        var commitResult: RestoreV3Result = RestoreV3Result.Imported(ArchiveV3Counts(1, 3, 3, 4, 1, 1), zero)
        var commitError: Exception? = null
        val coordinator = ImportCoordinator(ImportDocumentReader { reads++; readGate?.await(); bytes }, object : ArchiveImporter {
            override suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult = error("No V3 projection to V1")
            override suspend fun importArchive(preview: ArchiveImportPreview): ArchiveImportResult = error("No V3 projection to V1")
        }, scope, { if (locked) false else { locked = true; true } }, { releases++; locked = false },
            { refreshes++; if (refreshError) throw IOException("Synthetic refresh") }, Dispatchers.Unconfined,
            v3Repository = if (!capability) null else ArchiveV3Importer { input ->
                previews++; inputs += input; previewGate?.await()
                preparedResult ?: PreparedV3Result.Ready(object : PreparedV3Import {
                    override val archive = input
                    override val plan = planArchiveImportV3(input, known)
                    override suspend fun commit(): RestoreV3Result {
                        commits++; commitGate?.await(); commitError?.let { throw it }; return commitResult
                    }
                })
            })
        val state get() = coordinator.state.value
        fun choose(): String {
            coordinator.begin(); val ticket = assertIs<ImportState.Choosing>(state).requestId
            assertTrue(coordinator.claimSelection()); return ticket
        }
        fun select() { val ticket = choose(); coordinator.selected(ticket, "content://synthetic/v3") }
        fun confirm() { coordinator.confirmV3(assertIs<ImportState.PreviewV3>(state).confirmationId) }
        override fun close() = scope.cancel()
    }
    @Test fun `V3 selection retains exact validated bytes and requires explicit confirmation with no legacy API`() {
        Harness().use { h ->
            h.select(); assertIs<ImportState.PreviewV3>(h.state); assertEquals(0, h.commits); assertTrue(h.locked)
            h.bytes = "provider changed".toByteArray(); h.confirm()
            val result = assertIs<ImportState.SucceededV3>(h.state)
            assertEquals(ArchiveV3Counts(1, 3, 3, 4, 1, 1), result.added)
            assertEquals(h.source, h.inputs.single()); assertEquals(1, h.reads); assertEquals(1, h.commits)
            assertEquals(1, h.refreshes); assertFalse(h.locked)
        }
    }
    @Test fun `empty catalog only profile only and completed sources retain V3 semantics`() {
        val original = f.archive(true)
        val sources = listOf(original, f.empty(), f.accepted(original.toSnapshot().copy(sessions = emptyList(), profiles = emptyList())),
            f.accepted(original.toSnapshot().copy(sessions = emptyList(), bows = emptyList(), arrowSets = emptyList())))
        sources.forEach { source -> Harness(source).use { h ->
            h.select(); val view = assertIs<ImportState.PreviewV3>(h.state)
            assertEquals(source.sessions.size, view.plan.sessions.newUuids.size); assertEquals(source.bows.size, view.plan.bows.newUuids.size)
            assertEquals(6, v3PlanLines(view.plan).size); h.coordinator.cancel(); assertEquals(0, h.commits)
        } }
    }
    @Test fun `missing V3 capability fails without downgrade`() {
        Harness(capability = false).use { h -> h.select(); assertTrue(assertIs<ImportState.Failed>(h.state).diagnostic.contains("aucune conversion"))
            assertEquals(0, h.previews); assertEquals(0, h.commits); assertFalse(h.locked) }
    }
    @Test fun `conflicting entire root blocks independent additions and exposes six categories`() {
        Harness().use { h ->
            h.known = f.accepted(h.source.toSnapshot().copy(sessions = h.source.toSnapshot().sessions!!.map {
                it.copy(metadata = it.metadata!!.copy(title = "Conflicting title")) }))
            h.select(); val conflict = assertIs<ImportState.ConflictV3>(h.state)
            assertEquals(1, conflict.plan!!.sessions.conflictingUuids.size)
            assertEquals(6, v3PlanLines(conflict.plan).size); h.coordinator.confirm(); assertEquals(0, h.commits); assertFalse(h.locked)
        }
    }
    @Test fun `malformed unsupported missing and duplicate V3 fields never reach Room preview`() {
        Harness().use { h ->
            val valid = h.bytes.toString(Charsets.UTF_8)
            val cases = listOf(valid.dropLast(1), valid.replace("\"schemaVersion\": 3", "\"schemaVersion\": 4"),
                valid.replace("\"schemaVersion\": 3", "\"schemaVersion\": 3, \"schemaVersion\": 3"),
                valid.replace("\"scoringContractVersion\": 1", "\"scoringContractVersion\": 99"),
                valid.replaceFirst("\"bows\":", "\"unexpected\":"))
            cases.forEach { h.bytes = it.toByteArray(); h.select(); assertIs<ImportState.Failed>(h.state) }
            assertEquals(0, h.previews); assertEquals(0, h.commits)
        }
    }
    @Test fun `stale picker reply cancel and picker failure cannot consume a newer request`() {
        Harness().use { h ->
            val old = h.choose(); h.coordinator.selected(old, null); val fresh = h.choose()
            h.coordinator.selected(old, "content://old"); h.coordinator.selected(old, null); h.coordinator.pickerUnavailable(old)
            assertEquals(fresh, assertIs<ImportState.Choosing>(h.state).requestId); assertTrue(h.locked); assertEquals(0, h.reads)
            h.coordinator.selected(fresh, "content://new"); assertIs<ImportState.PreviewV3>(h.state); assertEquals(1, h.reads)
        }
    }
    @Test fun `stale confirm callback cannot confirm another preview even for identical source`() {
        Harness().use { h ->
            h.select(); val old = assertIs<ImportState.PreviewV3>(h.state).confirmationId
            h.coordinator.cancel(); h.select(); h.coordinator.confirmV3(old); assertEquals(0, h.commits)
            assertIs<ImportState.PreviewV3>(h.state); h.confirm(); assertEquals(1, h.commits)
        }
    }
    @Test fun `double selection and confirmation issue one request while commit acknowledgement is pending`() = runBlocking<Unit> {
        Harness().use { h ->
            h.readGate = CompletableDeferred(); val ticket = h.choose(); h.coordinator.selected(ticket, "content://one")
            h.coordinator.selected(ticket, "content://duplicate"); assertEquals(1, h.reads)
            h.readGate!!.complete(Unit); yield(); val confirm = assertIs<ImportState.PreviewV3>(h.state).confirmationId
            h.commitGate = CompletableDeferred(); h.coordinator.confirmV3(confirm); h.coordinator.confirmV3(confirm); h.coordinator.cancel()
            assertIs<ImportState.Importing>(h.state); assertTrue(h.locked); assertEquals(1, h.commits)
            h.commitGate!!.complete(Unit); yield(); assertIs<ImportState.SucceededV3>(h.state); assertEquals(1, h.releases)
        }
    }
    @Test fun `uncertain commit retains candidate lock and never retries before deliberate repreview then confirmation`() {
        Harness().use { h ->
            h.commitResult = RestoreV3Result.CommitUnconfirmed(IOException("Lost ACK")); h.select(); h.confirm()
            assertIs<ImportState.UnconfirmedV3>(h.state); assertTrue(h.locked); assertTrue(h.state.active)
            h.coordinator.begin(); h.coordinator.confirm(); h.coordinator.cancel(); assertEquals(1, h.commits)
            h.known = h.source; h.bytes = byteArrayOf(); h.coordinator.repreviewV3()
            assertEquals(2, h.previews); assertEquals(1, h.reads); assertSame(h.inputs[0], h.inputs[1]); assertEquals(1, h.commits)
            val new = assertIs<ImportState.PreviewV3>(h.state); assertFalse(new.plan.hasAdditions)
            h.commitResult = RestoreV3Result.AlreadyPresent(ArchiveV3Counts(1, 3, 3, 4, 1, 1), false)
            h.confirm(); assertTrue(assertIs<ImportState.SucceededV3>(h.state).alreadyPresent); assertFalse(h.locked)
        }
    }
    @Test fun `failed reconciliation preserves uncertainty and candidate until explicit warning dismissal`() {
        Harness().use { h ->
            h.commitError = IOException("Unknown transaction outcome"); h.select(); h.confirm(); assertIs<ImportState.UnconfirmedV3>(h.state)
            h.preparedResult = PreparedV3Result.TechnicalFailure(IOException("Offline store")); h.coordinator.repreviewV3()
            assertIs<ImportState.UnconfirmedV3>(h.state); assertTrue(h.locked); assertEquals(1, h.commits)
            h.coordinator.dismissUnconfirmedV3(); assertEquals(ImportState.Unconfirmed(true), h.state)
            assertFalse(h.locked); assertEquals(1, h.refreshes)
        }
    }
    @Test fun `known prewrite failures and uncertain intervention are distinct outcomes`() {
        listOf<RestoreV3Result>(RestoreV3Result.TechnicalFailure(IOException("Closed")),
            RestoreV3Result.Conflict(StorageDiagnostic("importV3.preview", "Stale")),
            RestoreV3Result.NeedsIntervention(ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE, "identities", "Corrupt"), ArchiveV3CommitOutcome.NOT_ATTEMPTED),
            RestoreV3Result.NeedsIntervention(ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE, "verification", "Corrupt"), ArchiveV3CommitOutcome.UNCONFIRMED)).forEach { result ->
            Harness().use { h -> h.commitResult = result; h.select(); h.confirm()
                assertEquals(result is RestoreV3Result.NeedsIntervention && result.outcome == ArchiveV3CommitOutcome.UNCONFIRMED, h.state is ImportState.UnconfirmedV3)
                assertFalse(h.state is ImportState.SucceededV3); assertEquals(0, h.refreshes); assertEquals(1, h.commits)
            }
        }
    }
    @Test fun `preparation failures preserve precise path before any write`() {
        Harness().use { h ->
            h.preparedResult = PreparedV3Result.NeedsIntervention(ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.DOMAIN, "sessions[id].ends[0].configuration", "Missing capture"))
            h.select(); assertTrue(assertIs<ImportState.Failed>(h.state).diagnostic.contains("ends[0].configuration")); assertEquals(0, h.commits)
        }
    }
    @Test fun `cancelled reading and preview release lock with no commit`() = runBlocking<Unit> {
        for (phase in 0..1) {
            val h = Harness(); if (phase == 0) h.readGate = CompletableDeferred() else h.previewGate = CompletableDeferred()
            h.select(); h.close(); yield(); assertEquals(ImportState.Unconfirmed(false), h.state); assertEquals(0, h.commits); assertFalse(h.locked)
        }
    }
    @Test fun `cancelled commit never claims rollback and keeps exact recovery candidate`() = runBlocking<Unit> {
        val h = Harness(); h.commitGate = CompletableDeferred(); h.select(); h.confirm(); h.close(); yield()
        assertIs<ImportState.UnconfirmedV3>(h.state); assertTrue(h.locked); assertEquals(1, h.commits); assertEquals(0, h.refreshes)
    }
    @Test fun `successful commit is not downgraded when refresh fails`() {
        Harness().use { h -> h.refreshError = true; h.select(); h.confirm()
            assertTrue(assertIs<ImportState.SucceededV3>(h.state).refreshFailed); assertFalse(h.locked) }
    }
    @Test fun `orphaned callbacks do not unlock somebody else's equipment operation`() {
        Harness().use { h -> h.locked = true; h.coordinator.selected("lost-request", "content://lost")
            assertEquals(ImportState.Unconfirmed(true), h.state); assertTrue(h.locked); assertEquals(0, h.releases); assertEquals(0, h.reads) }
    }
    @Test fun `identical and empty confirmation messages explicitly say no writes`() {
        for (empty in listOf(false, true)) Harness(if (empty) f.empty() else f.archive()).use { h ->
            h.known = h.source; h.commitResult = RestoreV3Result.AlreadyPresent(zero, empty)
            h.select(); h.confirm(); val state = assertIs<ImportState.SucceededV3>(h.state)
            assertTrue(state.alreadyPresent); assertEquals(empty, state.emptyArchive); assertTrue(v3SuccessText(state).contains("aucune écriture"))
        }
    }
}
