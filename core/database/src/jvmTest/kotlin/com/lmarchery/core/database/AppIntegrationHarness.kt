package com.lmarchery.core.database

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.data.*
import com.lmarchery.data.archiveimport.*
import com.lmarchery.data.export.*
import com.lmarchery.feature.archiveimport.*
import com.lmarchery.feature.configured.*
import com.lmarchery.feature.equipment.*
import com.lmarchery.feature.export.*
import com.lmarchery.feature.sessions.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.*
import java.net.URI
import java.nio.file.*
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual production ports/controllers and Room, one UI actor, real native SQLite and document files.
 * Only the document-provider boundary is implemented for the JVM; it is not Android SAF or Compose. */
internal class AppIntegrationHarness(val path: Path, val store: RoomSessionStore = openSessionStore(path)) : AutoCloseable {
    val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val job = SupervisorJob()
    val scope = CoroutineScope(job + ui)
    private val ticks = AtomicLong()
    private val identities = AtomicLong(10000)
    val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.parse("2026-10-28T08:00:00.123456789Z").plusSeconds(ticks.getAndIncrement())
    }
    fun synchronizeClock(after: Instant) { ticks.updateAndGet { maxOf(it, Duration.between(Instant.parse("2026-10-28T08:00:00.123456789Z"), after).seconds + 1) } }
    private fun uuid() = "44000000-0000-4000-8000-${identities.getAndIncrement().toString().padStart(12, '0')}"
    val repository = RoomSessionRepository(store)
    val legacy = SessionController(repository, scope, clock, { ZoneId.of("Europe/Paris") }, ::uuid,
        equipmentRepository = repository.equipment, v3Source = repository)
    val configured = ConfiguredSessionController(repository, scope, legacy::acquireImport, legacy::releaseArchiveOperation,
        legacy::refresh, repository.equipment, clock, { ZoneId.of("Europe/Paris") }, ::uuid)
    val equipment = EquipmentController(repository.equipment, scope, legacy::acquireImport, legacy::releaseArchiveOperation, clock, ::uuid)
    val documents = DiskIntegrationDocuments(path.resolveSibling(path.fileName.toString()+"-documents"))
    val exporter = ExportCoordinator(repository, VerifyingDocumentWriter(documents), scope,
        legacy::acquireExport, legacy::releaseExport, clock, v2Source = repository, v3Source = repository)
    val importer = ImportCoordinator(BoundedDocumentReader(documents), repository, scope,
        legacy::acquireImport, legacy::releaseArchiveOperation, legacy::refresh, v2Repository = repository, v3Repository = repository)
    val state get() = configured.state.value
    val context get() = requireNotNull(state.targetContext)
    val token get() = requireNotNull(state.form).token
    private val closed = AtomicBoolean(false)
    suspend fun <T> onUi(action: () -> T): T = withContext(ui) { action() }
    suspend fun idle() { withTimeout(15000) { configured.state.first { !it.busy } } }
    suspend fun ready() { withTimeout(15000) { legacy.state.first { !it.busy } } }
    suspend fun change(action: () -> Unit) {
        onUi(action); idle(); assertNull(state.pending, state.problem.toString()); assertNull(state.problem)
    }
    suspend fun refresh() { onUi { legacy.refresh() }; ready() }
    suspend fun archive(): DomainArchiveV3 = assertIs<ArchiveV3ReadResult.Found>(repository.readArchiveV3()).archive
    suspend fun createDraft(title: String = " Synthetic configurable 🏹 ") {
        onUi { configured.create(); configured.title(token, title) }
        change { configured.confirmForm(token) }
    }
    suspend fun editing() { createDraft(); change { configured.start() }; nextEnd() }
    suspend fun nextEnd() { onUi { configured.nextEnd() }; change { configured.confirmForm(token) } }
    suspend fun tap(radius: Float) = change { configured.tapped(context, NormalizedPoint(radius, -0.0f)) }
    suspend fun closeEditor() { onUi { configured.close() }; ready(); assertFalse(state.open) }
    suspend fun createEquipment(kind: EquipmentKind): Equipment {
        onUi { equipment.open() }
        withTimeout(15000) { equipment.state.first { !it.busy } }
        onUi {
            equipment.add(kind); equipment.setField(EquipmentField.NAME, if (kind == EquipmentKind.BOW) " Arc 🏹 " else " Flèches é ")
            if (kind == EquipmentKind.BOW) equipment.setField(EquipmentField.POWER, "32.000004")
            else { equipment.setField(EquipmentField.SPINE, "500 ?"); equipment.setField(EquipmentField.LENGTH, "28 1/2 pouces") }
            equipment.setField(EquipmentField.NOTES, " \n"); equipment.save()
        }
        withTimeout(15000) { equipment.state.first { !it.busy } }
        assertNull(equipment.state.value.pending); assertNull(equipment.state.value.problem)
        val value = requireNotNull(equipment.state.value.original)
        onUi { equipment.close() }; assertFalse(legacy.state.value.archiveOperationLocked)
        return value
    }
    suspend fun prepareExport(version: Int): ExportState.Choosing {
        onUi { when(version) {1 -> exporter.begin();2 -> exporter.beginV2();3 -> exporter.beginV3();else -> error("Version") } }
        return assertIs(withTimeout(15000) { exporter.state.first { it is ExportState.Choosing || it is ExportState.Failed } })
    }
    suspend fun export(version: Int, name: String): ByteArray {
        val request = prepareExport(version); val uri = documents.uri(name)
        onUi { assertNotNull(exporter.claimDestinationRequest()); exporter.destinationChosen(request.requestId, uri) }
        assertIs<ExportState.Succeeded>(withTimeout(15000) { exporter.state.first { it is ExportState.Succeeded || it is ExportState.Failed } })
        assertFalse(legacy.state.value.archiveOperationLocked)
        return documents.bytes(name)
    }
    suspend fun preview(name: String, bytes: ByteArray): ImportState {
        documents.put(name, bytes)
        onUi { importer.begin(); val request = assertIs<ImportState.Choosing>(importer.state.value); assertTrue(importer.claimSelection()); importer.selected(request.requestId, documents.uri(name)) }
        return withTimeout(15000) { importer.state.first { it is ImportState.Preview || it is ImportState.PreviewV2 || it is ImportState.PreviewV3 ||
            it is ImportState.Conflict || it is ImportState.ConflictV2 || it is ImportState.ConflictV3 || it is ImportState.Failed } }
    }
    suspend fun confirm(): ImportState {
        val preview = importer.state.value
        onUi { if (preview is ImportState.PreviewV3) importer.confirmV3(preview.confirmationId) else importer.confirm() }
        val result = withTimeout(15000) { importer.state.first { it is ImportState.Succeeded || it is ImportState.SucceededV2 || it is ImportState.SucceededV3 ||
            it is ImportState.Conflict || it is ImportState.ConflictV2 || it is ImportState.ConflictV3 || it is ImportState.UnconfirmedV3 || it is ImportState.Failed || it is ImportState.Unconfirmed } }
        ready(); return result
    }
    suspend fun assertOtherOperationsLocked() {
        onUi { exporter.beginV3(); equipment.open(); legacy.configure(); configured.create() }
        assertIs<ExportState.Failed>(exporter.state.value); assertFalse(equipment.state.value.open)
        assertEquals(SessionScreen.LIST, legacy.state.value.screen); assertTrue(legacy.state.value.archiveOperationLocked)
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) { runBlocking { job.cancelAndJoin() }; store.close(); ui.close() }
    }
}

/** Every byte goes through disk streams. Faults occur at close after the real descriptor is closed. */
internal class DiskIntegrationDocuments(private val directory: Path) : ExportDocumentAccess, ImportDocumentAccess {
    init { Files.createDirectories(directory) }
    var failOutputClose = false
    var failInputClose = false
    val outputs = AtomicLong(); val inputs = AtomicLong()
    fun uri(name: String) = "content://synthetic-pr44/$name"
    private fun file(uri: String): Path {
        val value = URI(uri); require(value.scheme == "content" && value.authority == "synthetic-pr44")
        val name = value.path.removePrefix("/"); require(name.matches(Regex("[a-z0-9-]+\\.json")))
        return directory.resolve(name)
    }
    fun put(name: String, bytes: ByteArray) { Files.write(file(uri(name)), bytes) }
    fun bytes(name: String): ByteArray = Files.readAllBytes(file(uri(name)))
    override fun displayName(destination: String) = file(destination).fileName.toString()
    override fun openOutput(destination: String): OutputStream {
        outputs.incrementAndGet()
        return object : FilterOutputStream(Files.newOutputStream(file(destination))) {
            override fun close() { super.close(); if (failOutputClose) throw IOException("Synthetic output close failure") }
        }
    }
    override fun openInput(destination: String): InputStream {
        inputs.incrementAndGet()
        return object : FilterInputStream(Files.newInputStream(file(destination))) {
            override fun close() { super.close(); if (failInputClose) throw IOException("Synthetic input close failure") }
        }
    }
}
