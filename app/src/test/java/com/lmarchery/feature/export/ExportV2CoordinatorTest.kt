package com.lmarchery.feature.export

import com.lmarchery.backup.destinationChosenForTest
import com.lmarchery.backup.pickerUnavailableForTest

import com.lmarchery.backup.V2TestData.archive
import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.data.*
import com.lmarchery.data.export.*
import kotlinx.coroutines.*
import java.io.*
import java.time.*
import kotlin.test.*

class ExportV2CoordinatorTest {
    private inner class Harness(val archive: DomainArchiveV2 = archive(), codec: ArchiveJsonCodec = ArchiveJsonCodec()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var reads = 0; var writes = 0; var legacyCalls = 0; var locked = false
        var gate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        var result: ArchiveV2ReadResult = ArchiveV2ReadResult.Found(archive)
        var output = ByteArrayOutputStream(); var closeFailure = false; var truncated = false
        var partial = false; var readCloseFailure = false
        val coordinator = ExportCoordinator(ArchiveSource { legacyCalls++; error("No fallback") },
            ExportDocumentWriter { uri, bytes ->
                writes++; gate?.await()
                VerifyingDocumentWriter(object : ExportDocumentAccess {
                    override fun openOutput(destination: String) = object : OutputStream() {
                        override fun write(b: Int) { output.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            if (partial) { output.write(b, off, minOf(len, 10)); throw IOException("Interrupted") }
                            output.write(b, off, len)
                        }
                        override fun close() { if (closeFailure) throw IOException("Close") }
                    }
                    override fun openInput(destination: String) = object : ByteArrayInputStream(
                        output.toByteArray().let { if (truncated) it.copyOf(it.size - 1) else it }) {
                        override fun close() { if (readCloseFailure) throw IOException("Read close") }
                    }
                    override fun displayName(destination: String) = "synthetic-v2.lmarchery.json"
                }).writeAndVerify(uri, bytes)
            }, scope, { if (locked) false else { locked = true; true } }, { locked = false },
            Clock.fixed(now, ZoneOffset.UTC), Dispatchers.Unconfined, codec,
            ArchiveV2Source { reads++; readGate?.await(); result })
        val state get() = coordinator.state.value
        fun prepare() { coordinator.beginV2(); assertIs<ExportState.Choosing>(state) }
        fun write() { assertNotNull(coordinator.claimDestinationRequest()); coordinator.destinationChosenForTest("content://synthetic/v2") }
        override fun close() = scope.cancel()
    }
    @Test fun `complete V2 export uses one global snapshot and preserves every category nanosecond and Float bit`() {
        Harness().use { h -> h.prepare(); assertEquals(1, h.reads); assertEquals(0, h.writes)
            assertEquals("lmarchery-v2-20261018-020304.lmarchery.json", assertIs<ExportState.Choosing>(h.state).fileName)
            h.write(); val s = assertIs<ExportState.Succeeded>(h.state)
            assertEquals(2, s.schemaVersion); assertEquals(listOf(1, 1, 1, 1), listOf(s.sessionCount, s.profileCount, s.bowCount, s.arrowSetCount))
            val restored = assertIs<VersionedArchiveJsonRead.V2>(ArchiveJsonCodec().decodeVersioned(h.output.toByteArray())).archive
            assertEquals(h.archive, restored); assertEquals(bow().drawWeightLbs!!.toBits(), restored.bows.single().drawWeightLbs!!.toBits())
            assertEquals(now, restored.bows.single().createdAt); assertEquals(0, h.legacyCalls); assertFalse(h.locked)
        }
    }
    @Test fun `equipment only profiles only and entirely empty V2 exports are explicit valid documents`() {
        listOf(archive(false, false), archive(false, true, false), archive(false, false, false)).forEach { a ->
            Harness(a).use { h -> h.prepare(); h.write(); assertIs<ExportState.Succeeded>(h.state)
                assertEquals(a, assertIs<VersionedArchiveJsonRead.V2>(ArchiveJsonCodec().decodeVersioned(h.output.toByteArray())).archive) }
        }
    }
    @Test fun `V2 invalid storage and read failure never open picker or fall back`() {
        listOf<ArchiveV2ReadResult>(ArchiveV2ReadResult.NeedsIntervention(StorageDiagnostic("bows[id]", "Corruption")),
            ArchiveV2ReadResult.TechnicalFailure(IOException("Synthetic failure"))).forEach { r ->
            Harness().use { h -> h.result = r; h.coordinator.beginV2(); assertIs<ExportState.Failed>(h.state)
                assertNull(h.coordinator.claimDestinationRequest()); assertEquals(0, h.writes); assertEquals(0, h.legacyCalls); assertFalse(h.locked) }
        }
    }
    @Test fun `V2 budget failure blocks destination instead of truncating catalog`() {
        Harness(codec = ArchiveJsonCodec(JsonLimits(maxBytes = 64))).use { h -> h.coordinator.beginV2()
            assertTrue(assertIs<ExportState.Failed>(h.state).diagnostic.contains("LIMIT")); assertEquals(0, h.writes); assertFalse(h.locked) }
    }
    @Test fun `rotation and double callbacks keep a single validated V2 write`() = runBlocking<Unit> {
        Harness().use { h -> h.readGate = CompletableDeferred(); h.coordinator.beginV2(); h.coordinator.begin()
            assertEquals(1, h.reads); h.readGate!!.complete(Unit); yield(); h.gate = CompletableDeferred()
            val reattached = h.coordinator; h.write(); assertNull(reattached.claimDestinationRequest())
            h.coordinator.destinationChosenForTest("content://duplicate"); h.coordinator.beginV2()
            assertIs<ExportState.Writing>(h.state); assertEquals(1, h.writes); assertTrue(h.locked)
            h.gate!!.complete(Unit); yield(); assertIs<ExportState.Succeeded>(h.state)
        }
    }
    @Test fun `V2 cancelled picker and unavailable picker never write`() {
        Harness().use { h -> h.prepare(); h.coordinator.claimDestinationRequest(); h.coordinator.destinationChosenForTest(null)
            assertIs<ExportState.Cancelled>(h.state); assertEquals(0, h.writes); assertFalse(h.locked)
            h.prepare(); h.coordinator.pickerUnavailableForTest(); assertIs<ExportState.Failed>(h.state); assertEquals(0, h.writes) }
    }
    @Test fun `V2 interrupted write close and readback failures never claim confirmed backup`() {
        listOf<(Harness) -> Unit>({ it.closeFailure = true }, { it.truncated = true }, { it.partial = true }, { it.readCloseFailure = true }).forEach { inject ->
            Harness().use { h -> inject(h); h.prepare(); h.write()
                assertTrue(assertIs<ExportState.Failed>(h.state).potentiallyIncomplete); assertFalse(h.locked) }
        }
    }
    @Test fun `V2 cancellation during read or write and process loss do not invent success`() = runBlocking<Unit> {
        val reading = Harness(); reading.readGate = CompletableDeferred(); reading.coordinator.beginV2(); reading.close(); yield()
        assertFalse(assertIs<ExportState.Failed>(reading.state).potentiallyIncomplete); assertEquals(0, reading.writes)
        val writing = Harness(); writing.prepare(); writing.gate = CompletableDeferred(); writing.write(); writing.close(); yield()
        assertTrue(assertIs<ExportState.Failed>(writing.state).potentiallyIncomplete); assertFalse(writing.locked)
        Harness().use { h -> h.coordinator.destinationChosenForTest("content://old-process")
            assertTrue(assertIs<ExportState.Failed>(h.state).potentiallyIncomplete); assertEquals(0, h.writes) }
    }
}
