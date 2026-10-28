package com.lmarchery.core.database

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.export.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class RoomConfiguredMigrationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("migration.db")
    private val f = EquipmentTestData
    private val d = ArchiveV3DiskData

    private suspend fun migrate(version: Int, catalog: Boolean = version == 2) {
        val source = d.source(catalog); d.historical(path,version,source)
        val oldTables = if (version == 1) f.oldTables else f.tables
        val before = f.contents(path,oldTables); val ddl = d.schema(path,oldTables)
        val codec = ArchiveJsonCodec(); val oldBytes = codec.encode(source)
        val expected = d.configured(source)
        openSessionStore(path).use { store ->
            assertEquals(expected,store.fullV3())
            assertEquals(source,store.fullV2()); assertContentEquals(oldBytes,codec.encode(store.fullV2()))
            val legacy = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertEquals(source.sessions,legacy.sessions)
            assertTrue(legacy.profiles.size < source.profiles.size) // Unreferenced profile remains in V2/V3, not historical V1 scope.
            assertEquals(before,f.contents(path,oldTables)); assertEquals(ddl,d.schema(path,oldTables))
        }
        assertEquals(listOf("3"),f.rows(path,"PRAGMA user_version"))
        assertEquals(12,f.rows(path,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','sqlite_sequence','android_metadata')").size)
        assertEquals(source.sessions.size,f.rows(path,"SELECT * FROM session_configurations").size)
        assertEquals(source.sessions.sumOf { it.ends.size },f.rows(path,"SELECT * FROM end_configurations").size)
        assertEquals(listOf("LEGACY_DERIVED"),f.rows(path,"SELECT DISTINCT origin FROM session_configurations"))
        assertEquals(listOf("LEGACY_DERIVED"),f.rows(path,"SELECT DISTINCT origin FROM end_configurations"))
        assertTrue(f.rows(path,"SELECT previousEndUuid FROM end_configurations WHERE previousEndUuid IS NOT NULL").isEmpty())
        assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty()); assertEquals(listOf("ok"),f.rows(path,"PRAGMA integrity_check"))
        val all = d.contents(path)
        openSessionStore(path).use { store ->
            val reopened = store.fullV3(); assertEquals(expected,reopened)
            val json = ArchiveV3JsonCodec().encode(reopened)
            val read = assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(json))
            assertEquals(expected,read.archive); assertEquals(expected.toSnapshot(),read.snapshot)
            assertContentEquals(json,ArchiveV3JsonCodec().encode(read.archive))
        }
        assertEquals(all,d.contents(path))
    }
    @Test fun `real populated V1 follows 1 to 2 to 3 preserving all old rows and DDL`() = runBlocking<Unit> { migrate(1) }
    @Test fun `real populated V2 migrates to 3 with catalog and exact V3 conversion`() = runBlocking<Unit> { migrate(2) }
    @Test fun `real V2 with explicit empty catalog preserves unresolved references`() = runBlocking<Unit> { migrate(2,false) }

    @Test fun `draft active closed empty provisional validated and sparse indexes are captured exactly`() = runBlocking<Unit> {
        val source = d.source(); d.historical(path,2,source)
        openSessionStore(path).use { store ->
            val archive = store.fullV3()
            assertEquals(source.sessions.map { it.uuid },archive.sessions.map { it.uuid })
            assertEquals(setOf(SessionStatus.DRAFT,SessionStatus.IN_PROGRESS,SessionStatus.COMPLETED),archive.sessions.map { it.status }.toSet())
            source.sessions.zip(archive.sessions).forEach { (old,next) ->
                assertEquals(old.ends,next.ends.map { it.end }); assertEquals(old.createdAt,next.createdAt); assertEquals(old.updatedAt,next.updatedAt)
                assertEquals(old.startedAt,next.startedAt); assertEquals(old.endedAt,next.endedAt)
                assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED,next.initialConfigurationOrigin)
                assertTrue(next.ends.all { it.origin == EndConfigurationOrigin.LegacyDerived && it.configuration == next.initialConfiguration })
                assertEquals(old.ends.map { it.index },next.ends.map { it.end.index })
            }
            assertTrue(archive.sessions.flatMap { it.ends }.any { it.end.impacts.isEmpty() && it.end.status == EndStatus.VALIDATED })
            val impacts = archive.sessions.flatMap { s -> s.ends.flatMap { it.end.impacts } }
            assertTrue(impacts.any { it.isX }); assertTrue(impacts.any { it.isMiss }); assertTrue(impacts.any { it.isCordDecisionManual })
            assertTrue(impacts.any { it.repositions.size == 2 }); assertEquals(123456789,impacts.first().createdAt.nano)
        }
    }
    @Test fun `stored editions including null custom and unused profiles are preserved without registry substitution`() = runBlocking<Unit> {
        val source = d.source(); d.historical(path,2,source)
        val profiles = f.contents(path,listOf("target_profiles","target_zones"))
        openSessionStore(path).use { store ->
            val archive = store.fullV3(); assertEquals(source.profiles,archive.profiles)
            assertTrue(archive.profiles.any { it.regulatoryEdition == null })
            assertTrue(archive.profiles.any { it.regulatoryEdition == " source club 2026 " })
            assertTrue(archive.profiles.any { it.regulatoryEdition == "2026-01-27" })
            archive.sessions.forEach { s -> assertEquals(source.profiles.single { it.definition == s.initialConfiguration.targetType }.regulatoryEdition,
                s.initialConfiguration.regulatoryEdition) }
        }
        assertEquals(profiles,f.contents(path,listOf("target_profiles","target_zones")))
    }
    @Test fun `invalid source fails before migration DDL without rewriting data`() = runBlocking<Unit> {
        val cases=listOf("UPDATE sessions SET status='UNKNOWN'", "UPDATE score_decisions SET selectedScore=99 WHERE revision=-1",
            "UPDATE target_profiles SET regulatoryEdition='wrong' WHERE regulatoryEdition='2026-01-27'",
            "UPDATE bows SET updated_nano=-1", "UPDATE endS SET impactCount=99", "UPDATE sessions SET distanceM=4294967314")
        for ((n,sql) in cases.withIndex()) {
            val file=directory.resolve("invalid-$n.db"); d.historical(file,2); d.raw(file,sql)
            val before=f.contents(file); val hash=f.rows(file,"SELECT * FROM room_master_table")
            openSessionStore(file).use { assertIs<ArchiveV3ReadResult.NeedsIntervention>(it.readArchiveV3()) }
            assertEquals(before,f.contents(file)); assertEquals(hash,f.rows(file,"SELECT * FROM room_master_table"))
            assertEquals(listOf("2"),f.rows(file,"PRAGMA user_version"))
            assertTrue(f.rows(file,"SELECT name FROM sqlite_master WHERE name IN ('session_configurations','end_configurations')").isEmpty())
        }
    }
    @Test fun `orphan identities and physical graph corruption block migration rather than omit children`() = runBlocking<Unit> {
        d.historical(path,2)
        d.raw(path,"INSERT INTO entity_identities VALUES('${f.id(999)}','SESSION')")
        val before=f.contents(path)
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.NeedsIntervention>(it.readArchiveV3()) }
        assertEquals(before,f.contents(path)); assertEquals(listOf("2"),f.rows(path,"PRAGMA user_version"))
    }
    @Test fun `failed full 1 to 2 to 3 chain rolls back catalog DDL and preserves the V1 file`() = runBlocking<Unit> {
        d.historical(path,1,d.source(catalog=false))
        d.raw(path,"UPDATE sessions SET status='UNKNOWN' WHERE uuid='${f.id(1)}'")
        val before=f.contents(path,f.oldTables);val ddl=d.schema(path,f.oldTables);val hash=f.rows(path,"SELECT * FROM room_master_table")
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.NeedsIntervention>(it.readArchiveV3()) }
        assertEquals(before,f.contents(path,f.oldTables));assertEquals(ddl,d.schema(path,f.oldTables));assertEquals(hash,f.rows(path,"SELECT * FROM room_master_table"))
        assertEquals(listOf("1"),f.rows(path,"PRAGMA user_version"))
        assertTrue(f.rows(path,"SELECT name FROM sqlite_master WHERE name IN ('bows','arrow_sets','session_configurations','end_configurations')").isEmpty())
    }
    @Test fun `late DDL error rolls back both new tables and preserves V2 identity hash`() = runBlocking<Unit> {
        d.historical(path,2); d.raw(path,"CREATE TABLE end_configurations (unexpected TEXT)")
        val before=f.contents(path); val hash=f.rows(path,"SELECT * FROM room_master_table")
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.TechnicalFailure>(it.readArchiveV3()) }
        assertEquals(before,f.contents(path)); assertEquals(hash,f.rows(path,"SELECT * FROM room_master_table"))
        assertTrue(f.rows(path,"SELECT name FROM sqlite_master WHERE name='session_configurations'").isEmpty())
        assertEquals(listOf("2"),f.rows(path,"PRAGMA user_version"))
    }
    @Test fun `failure after successful native capture INSERT rolls back full migration`() = runBlocking<Unit> {
        d.historical(path,2)
        val before=f.contents(path); val inserted=AtomicBoolean(false)
        hookedStore(path) { sql,_ ->
            if (sql.trimStart().startsWith("INSERT INTO end_configurations")) {
                inserted.set(true); error("Synthetic failure after native INSERT completed")
            }
        }.use { assertIs<ArchiveV3ReadResult.TechnicalFailure>(it.readArchiveV3()) }
        assertTrue(inserted.get()); assertEquals(before,f.contents(path))
        assertEquals(listOf("2"),f.rows(path,"PRAGMA user_version"))
        assertTrue(f.rows(path,"SELECT name FROM sqlite_master WHERE name IN ('session_configurations','end_configurations')").isEmpty())
        openSessionStore(path).use { assertEquals(d.configured(d.source()),it.fullV3()) }
    }
    @Test fun `late Room schema validation failure also rolls back new DDL and captures`() = runBlocking<Unit> {
        d.historical(path,2)
        d.raw(path,"ALTER TABLE sessions ADD COLUMN unexpected TEXT")
        val before=f.contents(path);val ddl=d.schema(path,f.tables);val hash=f.rows(path,"SELECT * FROM room_master_table")
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.TechnicalFailure>(it.readArchiveV3()) }
        assertEquals(before,f.contents(path));assertEquals(ddl,d.schema(path,f.tables));assertEquals(hash,f.rows(path,"SELECT * FROM room_master_table"))
        assertEquals(listOf("2"),f.rows(path,"PRAGMA user_version"))
        assertTrue(f.rows(path,"SELECT name FROM sqlite_master WHERE name IN ('session_configurations','end_configurations')").isEmpty())
    }
    @Test fun `migration preserves legacy JSON bytes and all V3 configurations through subsequent legacy writes`() = runBlocking<Unit> {
        val source=d.source(); d.historical(path,2,source)
        openSessionStore(path).use { store ->
            val before=store.fullV2(); assertEquals(source,before)
            val old=f.session(); val next=old.copy(title="Metadata revision",updatedAt=f.now.plusSeconds(30))
            assertIs<SessionUpdateResult.Updated>(store.update(f.archive(old),f.archive(next)))
            assertEquals(next,store.fullV2().sessions.first())
            assertEquals(d.configured(store.fullV2()),store.fullV3())
        }
    }
    @Test fun `real Room V2 opener refuses a migrated V3 file without destructive fallback`() = runBlocking<Unit> {
        d.historical(path,2)
        fun v2Probe() = Room.databaseBuilder(path.toString()) { RoomV2Probe() }.setDriver(BundledSQLiteDriver()).build()
        val old=v2Probe()
        try { old.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { assertTrue(it.step());assertEquals(2L,it.getLong(0)) } } }
        finally { old.close() }
        openSessionStore(path).use { it.fullV3() }
        val before=d.contents(path); val master=f.rows(path,"SELECT * FROM room_master_table")
        val downgrade=v2Probe()
        val failure=try { assertFailsWith<IllegalStateException> { downgrade.useReaderConnection { c -> c.usePrepared("SELECT 1") { it.step() } } } }
        finally { downgrade.close() }
        assertTrue(failure.message.orEmpty().contains("3 to 2"),failure.toString())
        assertEquals(before,d.contents(path));assertEquals(master,f.rows(path,"SELECT * FROM room_master_table"))
        assertEquals(listOf("3"),f.rows(path,"PRAGMA user_version"))
        openSessionStore(path).use { assertEquals(d.configured(d.source()),it.fullV3()) }
    }
}

/** Real Room runtime/open delegate at version 2, no DAOs or fake SQLite. Opening a genuine V2 file
 * is the positive control; downgrade rejection must come from Room's missing migration, not this probe. */
private class RoomV2Probe : RoomDatabase() {
    override fun getRequiredAutoMigrationSpecClasses(): Set<kotlin.reflect.KClass<out androidx.room.migration.AutoMigrationSpec>> = emptySet()
    override fun createAutoMigrations(autoMigrationSpecs: Map<kotlin.reflect.KClass<out androidx.room.migration.AutoMigrationSpec>,androidx.room.migration.AutoMigrationSpec>): List<androidx.room.migration.Migration> = emptyList()
    override fun getRequiredTypeConverterClasses(): Map<kotlin.reflect.KClass<*>,List<kotlin.reflect.KClass<*>>> = emptyMap()
    override fun createInvalidationTracker() = InvalidationTracker(this,emptyMap(),emptyMap(),"sessions")
    override fun createOpenDelegate(): RoomOpenDelegate = object : RoomOpenDelegate(2,"157a4c13ba1dcfc04b30ca09ebe1e432","157a4c13ba1dcfc04b30ca09ebe1e432") {
        override fun createAllTables(connection: SQLiteConnection) = error("Probe must never create tables")
        override fun dropAllTables(connection: SQLiteConnection) = error("Destructive fallback is forbidden")
        override fun onCreate(connection: SQLiteConnection) = Unit
        override fun onPreMigrate(connection: SQLiteConnection) = Unit
        override fun onPostMigrate(connection: SQLiteConnection) = Unit
        override fun onValidateSchema(connection: SQLiteConnection) = error("Unexpected probe migration/validation")
        override fun onOpen(connection: SQLiteConnection) { connection.prepare("PRAGMA foreign_keys=ON").use { it.step() }; internalInitInvalidationTracker(connection) }
    }
}
