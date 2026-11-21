package com.lmarchery.core.database

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.export.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class RoomImpactCancellationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("cancellation.db")
    private val tables = d.tables + "impact_cancellations"
    private suspend fun RoomSessionStore.full() = assertIs<ArchiveV4ReadResult.Found>(readArchiveV4()).archive
    private fun cancelled(s: ConfiguredSession, impact: Int = 0) = s.cancelImpact(s.ends.single().uuid,
        s.ends.single().end.impacts[impact].uuid, f.now.plusSeconds(20), " Synthetic 🏹 \n")

    @Test fun `cancellation inserts only evidence and parent revisions preserving all judgments and traces`() = runBlocking<Unit> {
        val old = c.rich(); val next = cancelled(old)
        lateinit var expected: DomainArchiveV4
        openSessionStore(path).use { store ->
            store.restoreV2(d.source()); assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(old))
            val before = store.full()
            val frozen = tables - listOf("sessions", "ends", "impact_cancellations")
            val bytes = f.contents(path, frozen)
            f.denyWrites(path, frozen)
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(old, next))
            expected = store.full()
            assertEquals(next, expected.sessions.single { it.uuid == old.uuid })
            assertEquals(bytes, f.contents(path, frozen))
            assertEquals(before.sessions.filter { it.uuid != old.uuid }, expected.sessions.filter { it.uuid != old.uuid })
            assertEquals(old.ends.single().end.impacts, next.ends.single().end.impacts)
            assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertEquals(listOf("1"), f.rows(path, "SELECT count(*) FROM impact_cancellations"))
        }
        openSessionStore(path).use { assertEquals(expected, it.full()) }
        assertEquals(listOf("ok"), f.rows(path, "PRAGMA integrity_check")); assertTrue(f.rows(path, "PRAGMA foreign_key_check").isEmpty())
    }

    @Test fun `V4 strict export restore and repeated import preserve every value after reopening`() = runBlocking<Unit> {
        lateinit var expected: DomainArchiveV4
        openSessionStore(path).use { store ->
            store.restoreV2(d.source()); val s = c.rich(); store.createConfiguredSession(s)
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s, cancelled(s)))
            expected = store.full()
        }
        val codec = ArchiveV4JsonCodec(); val bytes = codec.encode(expected)
        val received = assertIs<ArchiveV4JsonRead.Accepted>(codec.decode(bytes)).archive
        val target = directory.resolve("restored.db")
        openSessionStore(target).use { store ->
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV4(received)).preview
            assertTrue(preview.plan.canImport); assertIs<ArchiveV4ImportResult.Imported>(store.importArchiveV4(preview))
            assertEquals(expected, store.full())
            f.denyWrites(target, tables)
            val identical = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV4(received)).preview
            assertIs<ArchiveV4ImportResult.AlreadyPresent>(store.importArchiveV4(identical))
            assertEquals(expected, store.full())
        }
        openSessionStore(target).use { assertEquals(expected, it.full()) }
        assertEquals(listOf("16"), f.rows(target, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','room_table_modification_log')"))
        assertTrue(f.rows(target, "PRAGMA foreign_key_check").isEmpty())
    }

    @Test fun `old V3 archive conflicts with a known cancelled impact and never resurrects it`() = runBlocking<Unit> {
        val s = c.rich(); val old = assertIs<com.lmarchery.core.scoring.archive.v3.ArchiveV3Reconstruction.Accepted>(
            com.lmarchery.core.scoring.archive.v3.reconstructArchiveV3(c.snapshot(s))).archive
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s); store.updateConfiguredSession(s, cancelled(s))
            val before = f.contents(path, tables); f.denyWrites(path, tables)
            val preview = assertIs<ArchiveV3PreviewResult.Ready>(store.previewImportV3(old)).preview
            assertFalse(preview.plan.canImport)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(preview))
            assertEquals(before, f.contents(path, tables)); assertEquals(cancelled(s), store.full().sessions.single())
        }
    }

    @Test fun `true historical schemas 1 2 and 3 migrate through 4 to 5 without changing any previous row`() = runBlocking<Unit> {
        for (version in 1..3) {
            val file = directory.resolve("v$version.db"); d.historical(file, version)
            val oldTables = when (version) { 1 -> f.oldTables; 2 -> f.tables; else -> d.tables }
            val before = f.contents(file, oldTables)
            val schema = d.schema(file, oldTables)
            openSessionStore(file).use { store -> assertTrue(store.full().sessions.isNotEmpty()) }
            assertEquals(before, f.contents(file, oldTables)); assertEquals(schema, d.schema(file, oldTables))
            assertEquals(listOf("5"), f.rows(file, "PRAGMA user_version"))
            assertEquals(listOf("0"), f.rows(file, "SELECT count(*) FROM impact_cancellations"))
            assertTrue(f.rows(file, "PRAGMA foreign_key_check").isEmpty()); assertEquals(listOf("ok"), f.rows(file, "PRAGMA integrity_check"))
        }
    }

    @Test fun `actual cancellation INSERT failure rolls back parents and all evidence`() = runBlocking<Unit> {
        val inserted = AtomicBoolean(false); val fail = AtomicBoolean(false); val s = c.rich()
        hookedStore(path) { sql, _ -> if (fail.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) {
            inserted.set(true); throw IllegalStateException("After native evidence INSERT")
        } }.use { store ->
            store.createConfiguredSession(s); val before = f.contents(path, tables)
            fail.set(true); assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.updateConfiguredSession(s, cancelled(s))); fail.set(false)
            assertTrue(inserted.get()); assertEquals(before, f.contents(path, tables)); assertEquals(s, store.full().sessions.single())
        }
    }

    @Test fun `lost cancellation ACK retains exact intent and reconciles already applied with zero DML`() = runBlocking<Unit> {
        val armed = AtomicBoolean(false); val commit = AtomicBoolean(false); val s = c.rich(); val next = cancelled(s)
        hookedStore(path) { sql, _ ->
            if (armed.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) commit.set(true)
            if (sql == "END TRANSACTION" && commit.compareAndSet(true, false)) { armed.set(false); throw IllegalStateException("Lost cancellation ACK after COMMIT") }
        }.use { store ->
            store.createConfiguredSession(s); armed.set(true)
            val r = assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.updateConfiguredSession(s, next))
            assertEquals(ConfiguredWriteRequest.Update(s, next), r.request)
        }
        openSessionStore(path).use { store ->
            assertEquals(next, store.full().sessions.single()); f.denyWrites(path, tables)
            assertIs<ConfiguredWriteResult.AlreadyApplied>(store.updateConfiguredSession(s, next))
            assertEquals(listOf("1"), f.rows(path, "SELECT count(*) FROM impact_cancellations"))
        }
    }

    @Test fun `two native writers cannot apply different cancellation intentions against one expected revision`() = runBlocking<Unit> {
        val s = c.rich()
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            one.createConfiguredSession(s); two.full(); val gate = CompletableDeferred<Unit>()
            val jobs = listOf(one, two).mapIndexed { i, store -> async(Dispatchers.IO) { gate.await(); store.updateConfiguredSession(s, cancelled(s, i)) } }
            gate.complete(Unit); val results = jobs.awaitAll()
            assertEquals(1, results.count { it is ConfiguredWriteResult.Updated }); assertEquals(1, results.count { it is ConfiguredWriteResult.Conflict })
            assertEquals(1, one.full().sessions.single().ends.single().end.cancellations.size)
            assertTrue(f.rows(path, "PRAGMA foreign_key_check").isEmpty())
        } }
    }

    @Test fun `corrupt owner and chronology are diagnosed without repairing or partially returning the archive`() = runBlocking<Unit> {
        val s = c.rich()
        openSessionStore(path).use { store ->
            store.restoreV2(d.source()); store.createConfiguredSession(s); store.updateConfiguredSession(s, cancelled(s))
            val other = d.source().sessions.flatMap { it.ends }.first().uuid
            f.rows(path, "UPDATE impact_cancellations SET endUuid='$other'")
            val before = f.contents(path, tables)
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(store.readArchiveV4()); assertEquals(before, f.contents(path, tables))
        }
    }

    @Test fun `historical writer retains its representation and every previous manual decision and trace`() = runBlocking<Unit> {
        val s = f.session(); val profile = TargetProfileSnapshot(s.targetType, null)
        val old = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(listOf(profile), listOf(s)))).archive
        val impact = s.ends.single().impacts.first()
        val next = s.cancelImpact(s.ends.single().uuid, impact.uuid, f.now.plusSeconds(20))
        val proposed = validatedSessionContent(profile, next)
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(old))
            val preserved = f.contents(path, listOf("impacts", "score_decisions", "impact_repositions", "entity_identities"))
            assertIs<ConfiguredWriteResult.Updated>(store.updateSessionContent(old, proposed))
            assertEquals(next, assertIs<SessionReadResult.Found>(store.read(s.uuid)).session)
            assertEquals(preserved, f.contents(path, preserved.keys.toList()))
            assertEquals(listOf("LEGACY_HOMOGENEOUS"), f.rows(path, "SELECT representation FROM session_configurations"))
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertIs<ConfiguredWriteResult.AlreadyApplied>(store.updateSessionContent(old, proposed))
        }
        openSessionStore(path).use { assertEquals(next, assertIs<SessionReadResult.Found>(it.read(s.uuid)).session) }
    }

    @Test fun `a preview prepared before cancellation is stale and cannot overwrite or append anything`() = runBlocking<Unit> {
        val s = c.rich()
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s)
            val source = store.full()
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV4(source)).preview
            store.updateConfiguredSession(s, cancelled(s))
            val before = f.contents(path, tables); f.denyWrites(path, tables)
            assertIs<ArchiveV4ImportResult.Conflict>(store.importArchiveV4(preview))
            assertEquals(before, f.contents(path, tables))
        }
    }

    @Test fun `V1 and V2 old previews and new previews explicitly reject revival of a cancelled historical impact`() = runBlocking<Unit> {
        val s = f.session(); val p = TargetProfileSnapshot(s.targetType, null)
        val v1 = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(listOf(p), listOf(s)))).archive
        val v2 = v2(sessions = listOf(s))
        openSessionStore(path).use { store ->
            store.create(v1)
            val one = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(v1)).preview
            val two = assertIs<ArchiveV2PreviewResult.Ready>(store.previewImportV2(v2)).preview
            val next = s.cancelImpact(s.ends.single().uuid, s.ends.single().impacts.first().uuid, f.now.plusSeconds(20))
            store.updateSessionContent(v1, validatedSessionContent(p, next))
            val before = f.contents(path, tables); f.denyWrites(path, tables)
            assertTrue(assertIs<ArchiveImportPreviewResult.NeedsIntervention>(store.previewImport(v1)).diagnostic.detail.contains("CONFLIT_ANNULATION"))
            assertTrue(assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(v2)).diagnostic.detail.contains("CONFLIT_ANNULATION"))
            assertIs<ArchiveImportResult.Conflict>(store.importArchive(one))
            assertIs<ArchiveV2ImportResult.Conflict>(store.importArchiveV2(two))
            assertEquals(before, f.contents(path, tables))
        }
    }

    @Test fun `a genuine Room V3 opener cannot reopen the migrated latest file`() = runBlocking<Unit> {
        val file = directory.resolve("v3-refuses-v4.db"); d.historical(file, 3)
        fun old() = Room.databaseBuilder(file.toString()) { RoomV3CancellationProbe() }.setDriver(BundledSQLiteDriver()).build()
        val original = old()
        try { original.useReaderConnection { connection -> connection.usePrepared("PRAGMA user_version") {
            assertTrue(it.step()); assertEquals(3L, it.getLong(0))
        } } } finally { original.close() }
        openSessionStore(file).use { assertTrue(it.full().sessions.isNotEmpty()) }
        val before = f.contents(file, tables); val hash = f.rows(file, "SELECT * FROM room_master_table")
        val downgrade = old()
        try {
            val error = assertFailsWith<IllegalStateException> { downgrade.useReaderConnection { connection -> connection.usePrepared("SELECT 1") { it.step() } } }
            assertTrue(error.message.orEmpty().contains("5 to 3"))
        } finally { downgrade.close() }
        assertEquals(before, f.contents(file, tables)); assertEquals(hash, f.rows(file, "SELECT * FROM room_master_table"))
        assertEquals(listOf("5"), f.rows(file, "PRAGMA user_version"))
    }

    @Test fun `coroutine cancellation after a true evidence INSERT preserves the intent and rolls back completely`() = runBlocking<Unit> {
        val armed = AtomicBoolean(false); val inserted = AtomicBoolean(false); val s = c.rich(); val next = cancelled(s)
        hookedStore(path) { sql, _ -> if (armed.get() && sql.startsWith("INSERT") && sql.contains("`impact_cancellations`")) {
            inserted.set(true); throw CancellationException("After native evidence INSERT")
        } }.use { store ->
            store.createConfiguredSession(s); val before = f.contents(path, tables)
            armed.set(true)
            val e = assertFailsWith<ConfiguredWriteCancelled> { store.updateConfiguredSession(s, next) }
            armed.set(false)
            assertEquals(ConfiguredWriteRequest.Update(s, next), e.request); assertEquals(ConfiguredCommitOutcome.UNCONFIRMED, e.commitOutcome)
            assertTrue(inserted.get()); assertEquals(before, f.contents(path, tables)); assertEquals(s, store.full().sessions.single())
        }
    }
}

/** Actual Room 3 runtime delegate with the frozen hash, positive-control opened before upgrade. */
private class RoomV3CancellationProbe : RoomDatabase() {
    override fun getRequiredAutoMigrationSpecClasses(): Set<kotlin.reflect.KClass<out androidx.room.migration.AutoMigrationSpec>> = emptySet()
    override fun createAutoMigrations(autoMigrationSpecs: Map<kotlin.reflect.KClass<out androidx.room.migration.AutoMigrationSpec>,androidx.room.migration.AutoMigrationSpec>): List<androidx.room.migration.Migration> = emptyList()
    override fun getRequiredTypeConverterClasses(): Map<kotlin.reflect.KClass<*>,List<kotlin.reflect.KClass<*>>> = emptyMap()
    override fun createInvalidationTracker() = InvalidationTracker(this,emptyMap(),emptyMap(),"sessions")
    override fun createOpenDelegate(): RoomOpenDelegate = object : RoomOpenDelegate(3,"3495b842fafc2db8b76ef8fbf50377e5","3495b842fafc2db8b76ef8fbf50377e5") {
        override fun createAllTables(connection: SQLiteConnection) = error("Probe must never create tables")
        override fun dropAllTables(connection: SQLiteConnection) = error("Destructive fallback is forbidden")
        override fun onCreate(connection: SQLiteConnection) = Unit
        override fun onPreMigrate(connection: SQLiteConnection) = Unit
        override fun onPostMigrate(connection: SQLiteConnection) = Unit
        override fun onValidateSchema(connection: SQLiteConnection) = error("Unexpected probe migration/validation")
        override fun onOpen(connection: SQLiteConnection) { connection.prepare("PRAGMA foreign_keys=ON").use { it.step() }; internalInitInvalidationTracker(connection) }
    }
}
