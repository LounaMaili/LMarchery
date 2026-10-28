package com.lmarchery.feature.export

import com.lmarchery.backup.destinationChosenForTest
import com.lmarchery.backup.pickerUnavailableForTest

import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import com.lmarchery.data.export.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import java.io.*
import java.time.*
import kotlin.test.*

class ExportCoordinatorTest {
    private val now = Instant.parse("2026-10-16T12:34:56.123456789Z")
    private fun id(n: Int) = "30000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private fun source(): DomainArchive {
        val target = IndoorProfile.RECURVE_40.target
        val arrows = (1..2).map { ArrowImpact(id(10+it),it,NormalizedPoint(.105f,0f),inputMode=InputMode.MANUAL,
            createdAt=now,updatedAt=now,targetType=target) }
        val end = End(id(2),1,EndStatus.EDITING,arrows,createdAt=now,updatedAt=now)
        val session = Session(id(1),SessionDateTime.fromInstant(now,ZoneId.of("UTC")),SessionStatus.DRAFT,
            title="Synthetic export",targetType=target,distanceM=18,arrowCountMode=ArrowCountMode.FIXED,
            arrowCountPerEnd=3,isIndoor=true,scoringMode=ScoringMode.TRAINING,createdAt=now,updatedAt=now).start(now).withEnd(end)
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(target,"2026-01-27")),listOf(session)))).archive
    }
    private class Access : ExportDocumentAccess {
        val output = ByteArrayOutputStream()
        var opens = 0
        var openError = false
        var closeError = false
        var flushError = false
        var readError = false
        var inputCloseError = false
        var partial = false
        var truncate = false
        var extra = false
        var corrupt = false
        override fun openOutput(destination: String): OutputStream {
            assertEquals("content://synthetic/document", destination)
            opens++
            if (openError) throw IOException("Synthetic inaccessible destination")
            return object : OutputStream() {
                override fun write(b: Int) { output.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (partial) { output.write(b,off,minOf(10,len)); throw IOException("Synthetic partial write") }
                    output.write(b,off,len)
                }
                override fun close() { if (closeError) throw IOException("Synthetic close failure") }
                override fun flush() { if (flushError) throw IOException("Synthetic flush failure") }
            }
        }
        override fun openInput(destination: String): InputStream {
            if (readError) throw IOException("Readback unavailable")
            var bytes = output.toByteArray()
            if (truncate) bytes = bytes.copyOf(bytes.size-1)
            if (extra) bytes += byteArrayOf(0)
            if (corrupt) bytes[0] = 0
            return object : ByteArrayInputStream(bytes) {
                override fun close() { if (inputCloseError) throw IOException("Input close failure") }
            }
        }
        override fun displayName(destination: String) = "synthetic.lmarchery.json"
    }
    private inner class Harness(val archive: DomainArchive = source(), dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        codec: ArchiveJsonCodec = ArchiveJsonCodec()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val access = Access()
        var blocked = false
        var locked = false
        var reads = 0
        var writes = 0
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var readResult: ArchiveReadResult = ArchiveReadResult.Found(archive)
        val coordinator = ExportCoordinator(ArchiveSource {
            reads++; readGate?.await(); readResult
        }, ExportDocumentWriter { destination, bytes ->
            writes++; writeGate?.await(); VerifyingDocumentWriter(access).writeAndVerify(destination,bytes)
        },scope,acquire={ if (blocked||locked) false else {locked=true;true} },release={locked=false},
            clock=Clock.fixed(now,ZoneOffset.UTC),dispatcher=dispatcher,codec=codec)
        val state get() = coordinator.state.value
        fun prepare() { coordinator.begin(); assertIs<ExportState.Choosing>(state) }
        fun choose() { assertNotNull(coordinator.claimDestinationRequest()); coordinator.destinationChosenForTest("content://synthetic/document") }
        override fun close() { scope.cancel() }
    }

    @Test fun `complete export is validated before destination and success waits for exact readback and close`() {
        val queue = object : CoroutineDispatcher() {
            val tasks = java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
            fun runNext() { tasks.removeFirst().run() }
        }
        Harness(dispatcher = queue).use { h ->
            h.coordinator.begin(); assertIs<ExportState.Preparing>(h.state)
            queue.runNext(); assertIs<ExportState.Validating>(h.state)
            queue.runNext(); assertIs<ExportState.Choosing>(h.state)
            assertTrue(h.locked); assertEquals(0,h.access.opens)
            val choosing = assertIs<ExportState.Choosing>(h.state)
            assertEquals("lmarchery-20261016-123456.lmarchery.json",choosing.fileName)
            h.choose(); assertIs<ExportState.Writing>(h.state)
            queue.runNext()
            val result = assertIs<ExportState.Succeeded>(h.state)
            assertEquals(1,result.sessionCount); assertEquals(h.access.output.size(),result.receipt.byteCount)
            assertEquals(64,result.receipt.sha256.length); assertFalse(h.locked)
            val archive = assertIs<ArchiveJsonRead.Accepted>(ArchiveJsonCodec().decode(h.access.output.toByteArray())).archive
            assertEquals(h.archive.toSnapshot(),archive.toSnapshot())
        }
    }

    @Test fun `picker cancellation does not open or write a document and releases the action lock`() {
        Harness().use { h ->
            h.prepare(); assertNotNull(h.coordinator.claimDestinationRequest())
            h.coordinator.destinationChosenForTest(null)
            assertIs<ExportState.Cancelled>(h.state); assertEquals(0,h.writes); assertFalse(h.locked)
        }
    }

    @Test fun `unavailable picker does not claim success or attempt a write`() {
        Harness().use { h -> h.prepare(); h.coordinator.pickerUnavailableForTest()
            assertIs<ExportState.Failed>(h.state); assertEquals(0,h.writes); assertFalse(h.locked) }
    }

    @Test fun `inaccessible partial close and unavailable readback failures never report success`() {
        listOf<(Access)->Unit>({it.openError=true},{it.partial=true},{it.closeError=true},{it.flushError=true},
            {it.readError=true},{it.inputCloseError=true},{it.truncate=true},{it.extra=true},{it.corrupt=true}).forEach { inject ->
            Harness().use { h ->
                inject(h.access); h.prepare(); h.choose()
                assertTrue(assertIs<ExportState.Failed>(h.state).potentiallyIncomplete)
                assertFalse(h.locked); assertEquals(1,h.writes)
            }
        }
    }

    @Test fun `double taps during suspended preparation choose only one destination request`() = runBlocking<Unit> {
        Harness().use { h ->
            val gate=CompletableDeferred<Unit>();h.readGate=gate
            h.coordinator.begin();h.coordinator.begin();assertEquals(1,h.reads)
            assertIs<ExportState.Preparing>(h.state)
            gate.complete(Unit);yield()
            val request=assertIs<ExportState.Choosing>(h.state)
            h.coordinator.begin();assertEquals(request,h.state)
            assertNotNull(h.coordinator.claimDestinationRequest());assertNull(h.coordinator.claimDestinationRequest())
            assertEquals(0,h.writes)
        }
    }

    @Test fun `double destination callbacks and taps during suspended write issue exactly one write`() = runBlocking<Unit> {
        Harness().use { h ->
            h.writeGate=CompletableDeferred();h.prepare();h.choose()
            assertIs<ExportState.Writing>(h.state);assertTrue(h.locked)
            h.coordinator.destinationChosenForTest("content://synthetic/document");h.coordinator.begin()
            assertEquals(1,h.writes);assertEquals(0,h.access.opens)
            h.writeGate!!.complete(Unit);yield()
            assertIs<ExportState.Succeeded>(h.state);assertEquals(1,h.access.opens)
        }
    }

    @Test fun `empty corrupt and unavailable global archives fail before opening the picker`() {
        Harness().use { h ->
            val empty=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(emptyList(),emptyList()))).archive
            listOf(ArchiveReadResult.Found(empty),
                ArchiveReadResult.NeedsIntervention(StorageDiagnostic("sessions[synthetic]","Corrupt session")),
                ArchiveReadResult.TechnicalFailure(IOException("Synthetic unavailable store"))).forEach { result ->
                h.readResult=result;h.coordinator.begin()
                assertIs<ExportState.Failed>(h.state);assertEquals(0,h.access.opens);assertFalse(h.locked)
            }
        }
    }

    @Test fun `validation failure blocks destination and writes rather than exporting a truncated subset`() {
        Harness(codec=ArchiveJsonCodec(JsonLimits(maxBytes=32))).use { h ->
            h.coordinator.begin()
            val failed=assertIs<ExportState.Failed>(h.state)
            assertTrue(failed.diagnostic.contains("LIMIT"))
            assertFalse(failed.potentiallyIncomplete);assertEquals(0,h.writes)
            assertNull(h.coordinator.claimDestinationRequest());assertFalse(h.locked)
        }
    }

    @Test fun `rotation reattachment retains prepared request without relaunch and process restart never invents success`() {
        Harness().use { h ->
            h.prepare();val request=h.state;h.coordinator.claimDestinationRequest()
            assertEquals((request as ExportState.Choosing).requestId,(h.coordinator.state.value as ExportState.Choosing).requestId)
            assertNull(h.coordinator.claimDestinationRequest())
            h.coordinator.destinationChosenForTest(null)
        }
        Harness().use { fresh ->
            fresh.coordinator.destinationChosenForTest("content://synthetic/document")
            assertTrue(assertIs<ExportState.Failed>(fresh.state).potentiallyIncomplete)
            assertEquals(0,fresh.writes)
        }
    }

    @Test fun `interrupted write retains no false success and marks possibly incomplete document`() = runBlocking<Unit> {
        val h=Harness();h.writeGate=CompletableDeferred();h.prepare();h.choose();h.close();yield()
        assertTrue(assertIs<ExportState.Failed>(h.state).potentiallyIncomplete)
        assertFalse(h.locked)
    }

    @Test fun `session controller lock rejects export during pending save busy read and unsaved correction`() {
        val archive=source(); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var readGate:CompletableDeferred<Unit>?=null
        val repository=object:SessionRepository {
            override suspend fun discover()=SessionDiscoveryResult.Found(listOf(id(1)))
            override suspend fun read(uuid:String):SessionReadResult {
                readGate?.await();return SessionReadResult.Found(archive.sessions.single(),archive.profiles.single())
            }
            override suspend fun create(archive:DomainArchive):SessionCreateResult=error("No create expected")
            override suspend fun update(expected:DomainArchive,proposed:DomainArchive)=SessionUpdateResult.TechnicalFailure(IOException("Synthetic disk error"))
        }
        val controller=SessionController(repository,scope,Clock.fixed(now,ZoneOffset.UTC))
        var exports=0
        val export=ExportCoordinator(ArchiveSource { exports++;ArchiveReadResult.Found(archive) },
            ExportDocumentWriter { _,_->error("No write expected") },scope,controller::acquireExport,controller::releaseExport,
            dispatcher=Dispatchers.Unconfined)
        try {
            readGate=CompletableDeferred();controller.openSession(id(1));assertTrue(controller.state.value.busy)
            export.begin();assertIs<ExportState.Failed>(export.state.value);assertEquals(0,exports)
            readGate!!.complete(Unit);readGate=null
            val end=controller.state.value.editingEnd!!
            controller.selectImpact(end.uuid,end.impacts.first().uuid);controller.beginScoreCorrection()
            export.begin();assertIs<ExportState.Failed>(export.state.value);assertEquals(0,exports)
            controller.cancelImpactEdit();controller.addImpact(NormalizedPoint(0f,0f))
            assertNotNull(controller.state.value.pending)
            export.begin();assertIs<ExportState.Failed>(export.state.value);assertEquals(0,exports)
            controller.discardAndReturnToList()
            export.begin();assertIs<ExportState.Choosing>(export.state.value)
            assertTrue(controller.state.value.exportLocked)
            controller.configure();controller.openSession(id(1));controller.discardAndReturnToList()
            assertEquals(SessionScreen.LIST,controller.state.value.screen)
            export.claimDestinationRequest();export.destinationChosenForTest(null)
            assertFalse(controller.state.value.exportLocked)
        } finally { scope.cancel() }
    }
}
