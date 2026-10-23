package com.lmarchery.feature.export

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.*
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.data.*
import com.lmarchery.data.export.*
import kotlinx.coroutines.*
import java.io.*
import java.time.*
import kotlin.test.*

/** Stream/IO doubles; no Android document provider or device validation claimed. */
class ExportV3CoordinatorTest {
    private class Access : ExportDocumentAccess {
        var bytes = ByteArrayOutputStream(); var opens = 0
        var fail: String? = null
        override fun openOutput(destination: String): OutputStream {
            opens++; if (fail == "open") throw IOException("Synthetic output")
            return object : OutputStream() {
                override fun write(b: Int) { bytes.write(b); if (fail == "partial") throw IOException("Partial") }
                override fun close() { if (fail == "close") throw IOException("Close") }
                override fun flush() { if (fail == "flush") throw IOException("Flush") }
            }
        }
        override fun openInput(destination: String): InputStream {
            if (fail == "read") throw IOException("Read")
            val data = bytes.toByteArray().let { when(fail) { "truncated" -> it.copyOf(it.size - 1); "extra" -> it + byteArrayOf(0); "different" -> it.copyOf().also { b -> b[0] = 0 }; else -> it } }
            return object : ByteArrayInputStream(data) { override fun close() { if (fail == "inputClose") throw IOException("Input close") } }
        }
        override fun displayName(destination: String) = "synthetic-v3.lmarchery.json"
    }
    private class Harness(val archive: DomainArchiveV3 = f.archive(), capability: Boolean = true,
        v3Codec: ArchiveV3JsonCodec = ArchiveV3JsonCodec()) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val access = Access(); var reads = 0; var writes = 0; var releases = 0; var locked = false
        var readResult: ArchiveV3ReadResult = ArchiveV3ReadResult.Found(archive)
        var readGate: CompletableDeferred<Unit>? = null; var writeGate: CompletableDeferred<Unit>? = null
        val coordinator = ExportCoordinator(ArchiveSource { error("No V3 fallback to V1") }, ExportDocumentWriter { uri, bytes ->
            writes++; writeGate?.await(); VerifyingDocumentWriter(access).writeAndVerify(uri, bytes)
        }, scope, { if(locked) false else { locked = true; true } }, { releases++; locked = false },
            Clock.fixed(f.now, ZoneId.of("Asia/Tokyo")), Dispatchers.Unconfined,
            v3Source = if (!capability) null else ArchiveV3Source { reads++; readGate?.await(); readResult }, v3Codec = v3Codec)
        val state get() = coordinator.state.value
        fun prepare(): String { coordinator.beginV3(); return assertIs<ExportState.Choosing>(state).requestId }
        fun write() { val ticket = assertIs<ExportState.Choosing>(state).requestId; assertNotNull(coordinator.claimDestinationRequest()); coordinator.destinationChosen(ticket, "content://synthetic/v3") }
        override fun close() = scope.cancel()
    }
    @Test fun `V3 complete snapshot precedes picker and success requires stream close and identical readback`() {
        Harness().use { h -> h.prepare(); assertEquals(0, h.access.opens); assertTrue(h.locked)
            assertEquals("lmarchery-v3-20261023-010203.lmarchery.json", assertIs<ExportState.Choosing>(h.state).fileName)
            h.write(); val result = assertIs<ExportState.Succeeded>(h.state)
            assertEquals(3, result.schemaVersion); assertEquals(1, result.sessionCount); assertEquals(3, result.profileCount)
            assertEquals(3, result.endCount); assertEquals(4, result.impactCount); assertEquals(1, result.bowCount); assertEquals(1, result.arrowSetCount)
            assertEquals(h.archive, assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(h.access.bytes.toByteArray())).archive)
            assertEquals(1, h.reads); assertEquals(1, h.writes); assertEquals(1, h.releases); assertFalse(h.locked)
        }
    }
    @Test fun `explicitly empty V3 and material only and profile only are complete exports`() {
        val full = f.archive(); val choices = listOf(f.empty(), f.accepted(full.toSnapshot().copy(sessions = emptyList(), profiles = emptyList())),
            f.accepted(full.toSnapshot().copy(sessions = emptyList(), bows = emptyList(), arrowSets = emptyList())))
        choices.forEach { a -> Harness(a).use { h -> h.prepare(); h.write(); assertIs<ExportState.Succeeded>(h.state)
            assertEquals(a, assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(h.access.bytes.toByteArray())).archive) } }
    }
    @Test fun `no V3 capability cannot silently export V2 or V1`() {
        Harness(capability = false).use { h -> h.coordinator.beginV3()
            assertTrue(assertIs<ExportState.Failed>(h.state).diagnostic.contains("aucune rétrogradation")); assertEquals(0, h.writes); assertFalse(h.locked) }
    }
    @Test fun `corrupt or technically unreadable complete database never opens destination`() {
        listOf<ArchiveV3ReadResult>(ArchiveV3ReadResult.NeedsIntervention(ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE, "end_configurations[id]", "Missing")),
            ArchiveV3ReadResult.TechnicalFailure(IOException("Closed"))).forEach { result -> Harness().use { h ->
                h.readResult = result; h.coordinator.beginV3(); assertIs<ExportState.Failed>(h.state); assertEquals(0, h.access.opens); assertEquals(0, h.writes)
            } }
    }
    @Test fun `encoding budget failure never offers partial archive or an older version`() {
        Harness(v3Codec = ArchiveV3JsonCodec(JsonLimits(maxBytes = 100))).use { h -> h.coordinator.beginV3()
            assertTrue(assertIs<ExportState.Failed>(h.state).diagnostic.contains("LIMIT")); assertEquals(0, h.writes) }
    }
    @Test fun `all document failure phases remain unconfirmed and leave partial output intact`() {
        listOf("open", "partial", "flush", "close", "read", "inputClose", "truncated", "extra", "different").forEach { phase ->
            Harness().use { h -> h.prepare(); h.access.fail = phase; h.write()
                assertTrue(assertIs<ExportState.Failed>(h.state).potentiallyIncomplete, phase); assertFalse(h.locked); assertEquals(1, h.writes)
                if (phase != "open") assertTrue(h.access.bytes.size() > 0)
            }
        }
    }
    @Test fun `stale destination and unavailable callback cannot consume newer export ticket`() {
        Harness().use { h ->
            val old = h.prepare(); h.coordinator.claimDestinationRequest(); h.coordinator.destinationChosen(old, null)
            val next = h.prepare(); h.coordinator.claimDestinationRequest()
            h.coordinator.destinationChosen(old, "content://old"); h.coordinator.destinationChosen(old, null); h.coordinator.pickerUnavailable(old)
            assertEquals(next, assertIs<ExportState.Choosing>(h.state).requestId); assertEquals(0, h.writes); assertTrue(h.locked)
            h.coordinator.destinationChosen(next, "content://new"); assertIs<ExportState.Succeeded>(h.state)
        }
    }
    @Test fun `duplicate callbacks and new begins while writing do not duplicate export`() = runBlocking<Unit> {
        Harness().use { h -> val ticket = h.prepare(); h.writeGate = CompletableDeferred(); h.write()
            h.coordinator.destinationChosen(ticket, "content://duplicate"); h.coordinator.beginV3(); assertEquals(1, h.writes)
            assertIs<ExportState.Writing>(h.state); h.writeGate!!.complete(Unit); yield(); assertIs<ExportState.Succeeded>(h.state) }
    }
    @Test fun `scope cancellation never announces success before provider acknowledgement`() = runBlocking<Unit> {
        for (write in listOf(false, true)) {
            val h = Harness(); if (write) { h.prepare(); h.writeGate = CompletableDeferred(); h.write() }
            else { h.readGate = CompletableDeferred(); h.coordinator.beginV3() }
            h.close(); yield(); assertIs<ExportState.Failed>(h.state); assertFalse(h.locked)
        }
    }
    @Test fun `snapshot does not change while destination chooser is open`() {
        Harness().use { h -> h.prepare(); h.readResult = ArchiveV3ReadResult.Found(f.empty()); h.write()
            assertEquals(h.archive, assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(h.access.bytes.toByteArray())).archive); assertEquals(1, h.reads) }
    }
    @Test fun `picker cancellation and missing picker write nothing`() {
        Harness().use { h -> val ticket = h.prepare(); h.coordinator.claimDestinationRequest(); h.coordinator.destinationChosen(ticket, null)
            assertIs<ExportState.Cancelled>(h.state); assertEquals(0, h.writes)
            val next = h.prepare(); h.coordinator.pickerUnavailable(next); assertIs<ExportState.Failed>(h.state); assertEquals(0, h.writes) }
    }
    @Test fun `orphan destination callback cannot release another controller operation`() {
        Harness().use { h -> h.locked = true; h.coordinator.destinationChosen("lost", "content://old")
            assertIs<ExportState.Failed>(h.state); assertTrue(h.locked); assertEquals(0, h.releases); assertEquals(0, h.writes) }
    }
}
