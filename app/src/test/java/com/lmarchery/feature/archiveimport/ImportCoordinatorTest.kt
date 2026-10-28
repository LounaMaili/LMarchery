package com.lmarchery.feature.archiveimport

import com.lmarchery.backup.selectedForTest
import com.lmarchery.backup.pickerUnavailableForTest

import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.*
import com.lmarchery.feature.export.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import java.io.*
import java.time.*
import kotlin.test.*

class ImportCoordinatorTest {
    private val now=Instant.parse("2026-10-16T12:34:56.123456789Z")
    private fun id(n:Int)="50000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private fun source():DomainArchive {
        val target=IndoorProfile.RECURVE_40.target
        val a=ArrowImpact(id(3),1,NormalizedPoint(.105f,-0.0f),inputMode=InputMode.MANUAL,createdAt=now,updatedAt=now,targetType=target)
        val end=End(id(2),1,EndStatus.EDITING,listOf(a),createdAt=now,updatedAt=now)
        val s=Session(id(1),SessionDateTime.fromInstant(now,ZoneId.of("UTC")),SessionStatus.DRAFT,title="Synthetic PR20",targetType=target,
            distanceM=18,arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=3,isIndoor=true,scoringMode=ScoringMode.TRAINING,createdAt=now,updatedAt=now).start(now).withEnd(end)
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(listOf(TargetProfileSnapshot(target,"2026-01-27")),listOf(s)))).archive
    }
    private fun empty()=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(emptyList(),emptyList()))).archive
    private class Queue:CoroutineDispatcher() {
        val tasks=java.util.ArrayDeque<Runnable>()
        override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) { tasks.add(block) }
        fun next()=tasks.removeFirst().run()
    }
    private inner class Harness(dispatcher:CoroutineDispatcher=Dispatchers.Unconfined):AutoCloseable {
        val archive=source();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var bytes=ArchiveJsonCodec().encode(archive)
        var readError:Exception?=null;var readGate:CompletableDeferred<Unit>?=null
        var writeGate:CompletableDeferred<Unit>?=null
        var reads=0;var previews=0;var writes=0;var refreshes=0;var refreshError=false
        var locked=false;var blocked=false;var known=empty()
        var result:ArchiveImportResult=ArchiveImportResult.Imported(1,0)
        var previewResult:ArchiveImportPreviewResult?=null
        var written:ArchiveImportPreview?=null
        val importer=object:ArchiveImporter {
            override suspend fun previewImport(archive:DomainArchive):ArchiveImportPreviewResult {
                previews++;return previewResult ?: ArchiveImportPreviewResult.Ready(ArchiveImportPreview(archive,planArchiveImport(archive,known)))
            }
            override suspend fun importArchive(preview:ArchiveImportPreview):ArchiveImportResult {
                writes++;written=preview;writeGate?.await();return result
            }
        }
        val coordinator=ImportCoordinator(ImportDocumentReader { uri ->
            assertEquals("content://synthetic/backup",uri);reads++;readGate?.await();readError?.let { throw it };bytes
        },importer,scope,acquire={if(blocked||locked)false else {locked=true;true}},release={locked=false},refresh={refreshes++;if(refreshError)throw IOException("Synthetic discovery failure")},dispatcher=dispatcher)
        val state get()=coordinator.state.value
        fun select() { coordinator.begin();assertTrue(coordinator.claimSelection());coordinator.selectedForTest("content://synthetic/backup") }
        override fun close()=scope.cancel()
    }

    @Test fun `confirmed SQL success remains confirmed when refreshing the list fails`() {
        Harness().use { h -> h.refreshError=true;h.select();h.coordinator.confirm()
            assertEquals(ImportState.Succeeded(1,0,refreshFailed=true),h.state)
            assertEquals(1,h.writes);assertFalse(h.locked)
        }
    }
    @Test fun `default sixteen MiB budget bounds an endless provider before decoding`() = runBlocking<Unit> {
        var consumed=0;var closed=false
        val source=ImportDocumentAccess { object:InputStream() {
            override fun read():Int { consumed++;return 65 }
            override fun read(buffer:ByteArray,off:Int,len:Int):Int { buffer.fill(65,off,off+len);consumed+=len;return len }
            override fun close() { closed=true }
        } }
        assertFailsWith<ImportReadException> { BoundedDocumentReader(source).read("content://synthetic") }
        assertEquals(16*1024*1024+1,consumed);assertTrue(closed)
    }
    @Test fun `selection cancellation and unavailable picker never read or write`() {
        Harness().use { h ->
            h.coordinator.begin();assertTrue(h.locked);assertTrue(h.coordinator.claimSelection());assertFalse(h.coordinator.claimSelection())
            h.coordinator.selectedForTest(null);assertIs<ImportState.Cancelled>(h.state);assertFalse(h.locked)
            h.coordinator.begin();h.coordinator.pickerUnavailableForTest();assertIs<ImportState.Failed>(h.state)
            assertEquals(0,h.reads);assertEquals(0,h.writes)
        }
    }
    @Test fun `inaccessible interrupted empty oversized malformed and truncated sources never reach SQL`() {
        listOf<Exception>(IOException("inaccessible"),IOException("interrupted"),ImportReadException("LIMIT")).forEach { error ->
            Harness().use { h ->h.readError=error;h.select();assertIs<ImportState.Failed>(h.state);assertEquals(0,h.previews);assertFalse(h.locked) }
        }
        listOf(byteArrayOf(),"{".toByteArray(),"{}".toByteArray(),"null".toByteArray()).forEach { bytes ->
            Harness().use { h ->h.bytes=bytes;h.select();val error=assertIs<ImportState.Failed>(h.state)
                assertTrue(error.diagnostic.contains("·"));assertEquals(0,h.previews);assertEquals(0,h.writes) }
        }
    }
    @Test fun `read validation preview and explicit confirmation preserve exact candidate and wait for SQL`() {
        val q=Queue()
        Harness(q).use { h ->
            h.select();assertIs<ImportState.Reading>(h.state);q.next();assertIs<ImportState.Validating>(h.state)
            q.next();q.next();val preview=assertIs<ImportState.Preview>(h.state)
            assertEquals(1,preview.plan.sessionCount);assertEquals(1,preview.plan.newSessionUuids.size);assertTrue(h.locked)
            assertEquals(0,h.writes);assertEquals(0,h.refreshes)
            h.bytes="file changed externally".toByteArray()
            h.coordinator.confirm();assertIs<ImportState.Importing>(h.state);assertEquals(0,h.refreshes)
            q.next();assertEquals(ImportState.Succeeded(1,0),h.state)
            assertEquals(h.archive.toSnapshot(),h.written!!.archive.toSnapshot())
            assertEquals(1,h.reads);assertEquals(1,h.refreshes);assertFalse(h.locked)
        }
    }
    @Test fun `refusing confirmation clears candidate and performs no write`() {
        Harness().use { h ->h.select();assertIs<ImportState.Preview>(h.state);h.coordinator.cancel();h.coordinator.confirm()
            assertIs<ImportState.Cancelled>(h.state);assertEquals(0,h.writes);assertFalse(h.locked) }
    }
    @Test fun `identical archive needs no confirmation and empty archive is not a success`() {
        Harness().use { h ->h.known=h.archive;h.select();val p=assertIs<ImportState.Preview>(h.state)
            assertEquals(1,p.plan.identicalSessionUuids.size);assertFalse(p.plan.canImport)
            h.coordinator.confirm();assertEquals(0,h.writes);h.coordinator.cancel() }
        Harness().use { h ->h.bytes=ArchiveJsonCodec().encode(empty());h.select()
            val conflict=assertIs<ImportState.Conflict>(h.state)
            assertEquals("EMPTY_ARCHIVE",conflict.plan!!.conflicts.single().code);assertEquals(0,h.writes) }
    }
    @Test fun `double selection taps and confirmation issue a single read and write`() = runBlocking<Unit> {
        Harness().use { h ->
            h.readGate=CompletableDeferred();h.select();h.coordinator.begin();h.coordinator.selectedForTest("content://synthetic/backup")
            assertEquals(1,h.reads);h.readGate!!.complete(Unit);yield();assertIs<ImportState.Preview>(h.state)
            h.writeGate=CompletableDeferred();h.coordinator.confirm();h.coordinator.confirm();assertEquals(1,h.writes)
            assertIs<ImportState.Importing>(h.state);assertEquals(0,h.refreshes)
            h.writeGate!!.complete(Unit);yield();assertIs<ImportState.Succeeded>(h.state);assertEquals(1,h.refreshes)
        }
    }
    @Test fun `preview conflicts or corrupt local data never offer an import`() {
        Harness().use { h ->
            val changed=h.archive.toSnapshot().let { s ->s.copy(sessions=listOf(s.sessions.single().copy(title="Divergent"))) }
            h.known=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(changed)).archive
            h.select();assertIs<ImportState.Conflict>(h.state);h.coordinator.confirm();assertEquals(0,h.writes);assertFalse(h.locked)
        }
        listOf<ArchiveImportPreviewResult>(ArchiveImportPreviewResult.NeedsIntervention(StorageDiagnostic("sessions[id]","Unreadable")),
            ArchiveImportPreviewResult.TechnicalFailure(IOException("Synthetic"))).forEach { result ->
            Harness().use { h ->h.previewResult=result;h.select();assertIs<ImportState.Failed>(h.state);assertEquals(0,h.writes) }
        }
    }
    @Test fun `late conflict SQL failure and invalid storage never become success or refresh`() {
        listOf<ArchiveImportResult>(ArchiveImportResult.Conflict(StorageDiagnostic("import","Changed")),
            ArchiveImportResult.TechnicalFailure(IOException("Synthetic SQL error")),
            ArchiveImportResult.NeedsIntervention(StorageDiagnostic("import.verification","Mismatch"))).forEach { result ->
            Harness().use { h ->h.result=result;h.select();h.coordinator.confirm()
                assertFalse(h.state is ImportState.Succeeded);assertFalse(h.locked);assertEquals(0,h.refreshes)
                h.coordinator.confirm();assertEquals(1,h.writes)
            }
        }
    }
    @Test fun `cancellation during read or SQL leaves an explicit interruption without false success`() = runBlocking<Unit> {
        val reading=Harness();reading.readGate=CompletableDeferred();reading.select();reading.close();yield()
        assertEquals(ImportState.Unconfirmed(false),reading.state);assertEquals(0,reading.writes)
        val writing=Harness();writing.writeGate=CompletableDeferred();writing.select();writing.coordinator.confirm();writing.close();yield()
        assertEquals(ImportState.Unconfirmed(true),writing.state);assertFalse(writing.locked);assertEquals(0,writing.refreshes)
    }
    @Test fun `reattachment retains candidate without reopening and fresh process cannot resume stale selection`() {
        Harness().use { h ->h.select();val original=h.state;val reattached=h.coordinator
            assertEquals(original,reattached.state.value);assertFalse(reattached.claimSelection());reattached.confirm()
            assertIs<ImportState.Succeeded>(h.state);assertEquals(1,h.reads) }
        Harness().use { h ->h.coordinator.selectedForTest("content://synthetic/backup");assertEquals(ImportState.Unconfirmed(true),h.state)
            assertEquals(0,h.reads);assertEquals(0,h.writes) }
    }
    @Test fun `shared controller lock excludes import export navigation busy pending and selected impact`() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val a=source()
        var gate:CompletableDeferred<Unit>?=null
        val repo=object:SessionRepository {
            override suspend fun discover():SessionDiscoveryResult { gate?.await();return SessionDiscoveryResult.Found(listOf(id(1))) }
            override suspend fun read(uuid:String)=SessionReadResult.Found(a.sessions.single(),a.profiles.single())
            override suspend fun create(archive:DomainArchive)=error("Unexpected create")
            override suspend fun update(expected:DomainArchive,proposed:DomainArchive)=SessionUpdateResult.TechnicalFailure(IOException("Synthetic"))
        }
        val c=SessionController(repo,scope,Clock.fixed(now,ZoneOffset.UTC))
        try {
            gate=CompletableDeferred();c.refresh();assertFalse(c.acquireImport());gate!!.complete(Unit);gate=null
            assertTrue(c.acquireImport());assertFalse(c.acquireExport());assertFalse(c.acquireImport())
            c.configure();c.openSession(id(1));assertEquals(SessionScreen.LIST,c.state.value.screen)
            c.releaseArchiveOperation();assertTrue(c.acquireExport());assertFalse(c.acquireImport());c.releaseExport()
            c.openSession(id(1));assertFalse(c.acquireImport())
            c.selectImpact(id(2),id(3));assertFalse(c.acquireExport());assertFalse(c.acquireImport())
            c.cancelImpactEdit();c.addImpact(NormalizedPoint(.2f,0f));assertNotNull(c.state.value.pending)
            assertFalse(c.acquireExport());assertFalse(c.acquireImport())
        } finally { scope.cancel() }
    }
    @Test fun `bounded reader checks actual bytes permits exact budget and rejects budget plus one`() = runBlocking<Unit> {
        var consumed=0;var closed=false
        val access=ImportDocumentAccess {
            object:InputStream() {
                override fun read():Int=if(consumed++<33) 65 else -1
                override fun close() { closed=true }
            }
        }
        assertFailsWith<ImportReadException> { BoundedDocumentReader(access,32).read("content://synthetic") }
        assertTrue(closed);assertEquals(33,consumed)
        val exact=BoundedDocumentReader(ImportDocumentAccess { ByteArrayInputStream(ByteArray(32) { 65 }) },32).read("content://synthetic")
        assertEquals(32,exact.size)
        assertFailsWith<IllegalArgumentException> { BoundedDocumentReader(access,JsonLimits().maxBytes+1) }
    }
    @Test fun `reader propagates open partial read and close failures instead of returning a candidate`() = runBlocking<Unit> {
        val sources=listOf<ImportDocumentAccess>(
            ImportDocumentAccess { throw IOException("Null or inaccessible provider stream") },
            ImportDocumentAccess { object:InputStream() { var count=0; override fun read():Int=if(count++ < 3) 65 else throw IOException("Interrupted") } },
            ImportDocumentAccess { object:ByteArrayInputStream(byteArrayOf(1)) { override fun close()=throw IOException("Close") } })
        sources.forEach { assertFailsWith<IOException> { BoundedDocumentReader(it).read("content://synthetic") } }
    }
}
