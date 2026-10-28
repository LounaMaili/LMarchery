package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3RestoreData as r
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Native SQLite files plus real local JSON files. These are NOT Android SAF-provider tests. */
class RoomV3DocumentWorkflowTest {
    @TempDir lateinit var directory: Path
    private val decoder = ArchiveDocumentDecoder()
    private val codec = ArchiveV3JsonCodec()
    private val database get() = directory.resolve("document-target.db")
    private fun document(source: DomainArchiveV3): DomainArchiveV3 {
        val file = directory.resolve("synthetic-document.json")
        Files.newOutputStream(file).use { it.write(codec.encode(source)) }
        return assertIs<ArchiveDocumentRead.V3>(decoder.decode(Files.newInputStream(file).use { it.readBytes() })).archive
    }
    @Test fun `full document export restore and reopen retains mixed and historical roots all profiles catalog and traces`() = runBlocking<Unit> {
        val source = r.source(); val decoded = document(source)
        openSessionStore(database).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(decoded))
            assertEquals(source, store.fullV3()); assertTrue(store.fullV3().sessions.any { it.ends.map { e -> e.configuration }.distinct().size > 1 })
            val before = d.contents(database); f.denyWrites(database, d.tables)
            assertContentEquals(codec.encode(source), codec.encode(store.fullV3())); assertEquals(before, d.contents(database))
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive()); assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
        }
        openSessionStore(database).use { store ->
            assertEquals(source, store.fullV3()); assertEquals(emptyList(), f.rows(database, "PRAGMA foreign_key_check"))
            assertEquals(listOf("ok"), f.rows(database, "PRAGMA integrity_check"))
        }
    }
    @Test fun `genuine V1 migrated source exports full V3 then restores without losing old lines or empty captures`() = runBlocking<Unit> {
        val old = d.source(catalog = false); val sourcePath = directory.resolve("source-v1.db")
        d.historical(sourcePath, 1, old); val ancient = f.contents(sourcePath, f.oldTables)
        lateinit var snapshot: DomainArchiveV3
        openSessionStore(sourcePath).use { store ->
            store.fullV3(); val before = d.contents(sourcePath)
            store.restoreV3(r.source()); val after = d.contents(sourcePath)
            f.oldTables.forEach { assertTrue(after.getValue(it).containsAll(ancient.getValue(it))) }
            d.tables.forEach { assertTrue(after.getValue(it).containsAll(before.getValue(it))) }
            snapshot = store.fullV3(); f.denyWrites(sourcePath, d.tables)
            assertEquals(snapshot, document(store.fullV3()))
        }
        openSessionStore(database).use { target -> assertIs<ArchiveV3ImportResult.Imported>(target.restoreV3(document(snapshot))) }
        openSessionStore(database).use { target -> assertEquals(snapshot, target.fullV3()); assertTrue(target.fullV3().sessions.any { s -> s.ends.any { it.end.impacts.isEmpty() } }) }
    }
    @Test fun `V1 V2 original document paths coexist before V3 and refuse configured contents afterward`() = runBlocking<Unit> {
        val old = d.source()
        val firstSession = old.sessions.first()
        val snapshot = snapshotArchive(listOf(old.profiles.single { it.definition == firstSession.targetType }), listOf(firstSession))
        val v1Source = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshot)).archive
        val historical = ArchiveJsonCodec()
        val one = assertIs<ArchiveDocumentRead.V1>(decoder.decode(historical.encode(v1Source))).archive
        val two = assertIs<ArchiveDocumentRead.V2>(decoder.decode(historical.encode(old))).archive
        openSessionStore(database).use { store ->
            val p1 = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(one)).preview
            assertIs<ArchiveImportResult.Imported>(store.importArchive(p1))
            val p2 = assertIs<ArchiveV2PreviewResult.Ready>(store.previewImportV2(two)).preview
            assertIs<ArchiveV2ImportResult.Imported>(store.importArchiveV2(p2))
            assertContentEquals(historical.encode(old), historical.encode(assertIs<ArchiveV2ReadResult.Found>(store.readArchiveV2()).archive))
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(document(r.source())))
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive()); assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(two))
            assertIs<ArchiveImportPreviewResult.NeedsIntervention>(store.previewImport(one))
        }
    }
    @Test fun `fresh document preview idempotence has no DML after real process equivalent reopen`() = runBlocking<Unit> {
        val source = document(r.source())
        openSessionStore(database).use { store -> store.restoreV3(source) }
        openSessionStore(database).use { store ->
            val before = d.contents(database); f.denyWrites(database, d.tables)
            val preview = store.previewV3(source); assertFalse(preview.plan.hasAdditions)
            assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.importArchiveV3(preview)); assertEquals(before, d.contents(database))
        }
    }
    @Test fun `late document failure after actual native INSERT rolls back all tables and explicit fresh preview succeeds`() = runBlocking<Unit> {
        val seen = AtomicBoolean(false); val source = document(r.source())
        hookedStore(database) { sql, _ -> if (sql.startsWith("INSERT") && sql.contains("`impacts`")) seen.set(true) }.use { store ->
            store.equipment.create(f.bow(850)); val preview = store.previewV3(source); val before = d.contents(database)
            f.rows(database, "CREATE TRIGGER document_failure BEFORE INSERT ON arrow_sets BEGIN SELECT RAISE(ABORT,'late document fixture'); END")
            assertIs<ArchiveV3ImportResult.CommitUnconfirmed>(store.importArchiveV3(preview)); assertTrue(seen.get()); assertEquals(before, d.contents(database))
            f.rows(database, "DROP TRIGGER document_failure")
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source)); assertTrue(store.fullV3().bows.contains(f.bow(850)))
        }
    }
    @Test fun `document preview stale after catalog revision blocks before any restoration`() = runBlocking<Unit> {
        openSessionStore(database).use { store ->
            val source = document(r.source()); store.equipment.create(f.bow(850)); val p = store.previewV3(source)
            store.equipment.update(f.bow(850), f.bow(850).copy(notes = "Changed", updatedAt = f.now.plusSeconds(30)))
            val before = d.contents(database); assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p)); assertEquals(before, d.contents(database))
        }
    }
    @Test fun `corrupt local capture cannot yield an exportable full document or a prepared restore`() = runBlocking<Unit> {
        openSessionStore(database).use { store ->
            store.restoreV3(r.source()); d.raw(database, "DELETE FROM end_configurations WHERE endUuid='${f.id(6001)}'")
            val before = d.contents(database); assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3())
            assertIs<ArchiveV3PreviewResult.NeedsIntervention>(store.previewImportV3(document(r.source()))); assertEquals(before, d.contents(database))
        }
    }
    @Test fun `malformed document never reaches a restore and existing native rows remain exact`() = runBlocking<Unit> {
        openSessionStore(database).use { store ->
            store.restoreV3(r.material()); val before = d.contents(database); val bytes = codec.encode(r.source())
            listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), "{\"schemaVersion\":3,\"schemaVersion\":2}".toByteArray()).forEach {
                assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(it)); assertEquals(before, d.contents(database))
            }
        }
    }
}
