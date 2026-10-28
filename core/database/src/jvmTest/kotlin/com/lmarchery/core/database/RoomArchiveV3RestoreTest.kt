package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3RestoreData as r
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.InitialConfigurationOrigin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RoomArchiveV3RestoreTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("restore-v3.db")
    @Test fun `rich V3 archive restores all roots captures decisions and traces then reopens exactly`() = runBlocking<Unit> {
        val source=r.source()
        openSessionStore(path).use { store ->
            val result=assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source));assertEquals(source.sessions.size,result.added.sessions)
            assertEquals(source,store.fullV3());assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
            assertEquals(listOf("ok"),f.rows(path,"PRAGMA integrity_check"))
            d.tables.forEach { assertTrue(f.rows(path,"SELECT count(*) FROM $it").single().toInt()>0,it) }
        }
        openSessionStore(path).use { assertEquals(source,it.fullV3()) }
    }
    @Test fun `JSON V3 encoding and decoding of restored full archive retain exact business content`() = runBlocking<Unit> {
        val source=r.source();val codec=ArchiveV3JsonCodec()
        val decoded=assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(decoded))
            assertContentEquals(codec.encode(source),codec.encode(store.fullV3()))
        }
    }
    @Test fun `restored historical provenance is preserved while new root representation is CONFIGURED`() = runBlocking<Unit> {
        val source=d.configured(d.source())
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source));assertEquals(source,store.fullV3())
            assertTrue(store.fullV3().sessions.all { it.initialConfigurationOrigin==InitialConfigurationOrigin.LEGACY_DERIVED })
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive());assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
        }
        assertTrue(f.rows(path,"SELECT representation,origin FROM session_configurations").all { it=="CONFIGURED|LEGACY_DERIVED" })
    }
    @Test fun `identical local legacy sessions retain exact rows and representation while V3 additions append`() = runBlocking<Unit> {
        val old=d.source()
        openSessionStore(path).use { store ->
            store.restoreV2(old);val before=d.contents(path)
            d.tables.forEach { table->listOf("UPDATE","DELETE").forEach { op->f.rows(path,
                "CREATE TRIGGER forbid_${table}_$op BEFORE $op ON $table BEGIN SELECT RAISE(ABORT,'append only'); END") } }
            val result=assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(r.source()))
            assertEquals(old.sessions.size,result.identical.sessions);assertEquals(2,result.added.sessions)
            assertEquals(r.source(),store.fullV3());val after=d.contents(path)
            d.tables.forEach { assertTrue(after.getValue(it).containsAll(before.getValue(it)),it) }
            assertEquals(listOf("LEGACY_HOMOGENEOUS"),f.rows(path,"SELECT representation FROM session_configurations WHERE sessionUuid='${f.id(1)}'"))
        }
    }
    @Test fun `genuine V1 to V3 file preserves every old row through V3 archive restoration`() = runBlocking<Unit> {
        val old=d.source(catalog=false);d.historical(path,1,old);val original=f.contents(path,f.oldTables)
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(r.source()));val after=d.contents(path)
            d.tables.forEach { assertTrue(after.getValue(it).containsAll(before.getValue(it)),it) }
            f.oldTables.forEach { assertTrue(after.getValue(it).containsAll(original.getValue(it)),it) }
            assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"));assertEquals(r.source(),store.fullV3())
        }
        openSessionStore(path).use { assertEquals(r.source(),it.fullV3()) }
    }
    @Test fun `equipment only restoration keeps exact Float bits strings timestamps and empty session list`() = runBlocking<Unit> {
        val source=r.material()
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source));assertEquals(source,store.fullV3())
            assertEquals(f.bow().drawWeightLbs!!.toRawBits(),store.fullV3().bows.single().drawWeightLbs!!.toRawBits())
        }
    }
    @Test fun `unreferenced profiles only restore independently of sessions and material`() = runBlocking<Unit> {
        val source=r.accepted(r.source().toSnapshot().copy(sessions=emptyList(),bows=emptyList(),arrowSets=emptyList()))
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source));assertEquals(source,store.fullV3())
        }
    }
    @Test fun `empty archive confirmation is no DML and never clears local catalog or sessions`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV3(r.source());val before=d.contents(path);f.denyWrites(path,d.tables)
            val result=assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.restoreV3(r.empty()))
            assertTrue(result.emptyArchive);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `fresh identical preview and confirmation execute no INSERT UPDATE DELETE`() = runBlocking<Unit> {
        val writes=AtomicInteger()
        hookedStore(path) { sql,_->if(sql.matches(Regex("(?is)^\\s*(INSERT|UPDATE|DELETE).*")))writes.incrementAndGet() }.use { store ->
            val source=r.source();store.restoreV3(source);val before=d.contents(path);f.denyWrites(path,d.tables);writes.set(0)
            val preview=store.previewV3(source);assertTrue(preview.plan.canImport);assertFalse(preview.plan.hasAdditions)
            assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.importArchiveV3(preview));assertEquals(0,writes.get());assertEquals(before,d.contents(path))
        }
    }
    @Test fun `preview on existing configured and legacy data is strictly read only under SQL guards`() = runBlocking<Unit> {
        val writes=AtomicInteger()
        hookedStore(path) { sql,_->if(sql.matches(Regex("(?is)^\\s*(INSERT|UPDATE|DELETE).*")))writes.incrementAndGet() }.use { store ->
            store.restoreV2(d.source());store.createConfiguredSession(c.draft());f.denyWrites(path,d.tables)
            val before=d.contents(path);writes.set(0)
            val p=store.previewV3(r.source());assertFalse(p.plan.canImport) // Existing creation has less history, never auto-upgraded.
            assertEquals(0,writes.get());assertEquals(before,d.contents(path))
        }
    }
    @Test fun `opaque references with explicitly empty catalogs never invent or erase equipment`() = runBlocking<Unit> {
        val source=r.accepted(r.onlyMixed().toSnapshot().copy(bows=emptyList(),arrowSets=emptyList()))
        openSessionStore(path).use { store ->
            store.equipment.create(f.bow(999));val bowBefore=f.contents(path,listOf("bows"))
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source))
            assertEquals(f.id(9999),store.fullV3().sessions.single().metadata.arrowSetUuid)
            assertEquals(bowBefore,f.contents(path,listOf("bows")));assertTrue(store.fullV3().arrowSets.isEmpty())
        }
    }
    @Test fun `root order becomes canonical but inherited end order and exact predecessor remain preserved`() = runBlocking<Unit> {
        val snapshot=r.source().toSnapshot()
        val incoming=r.accepted(snapshot.copy(profiles=snapshot.profiles!!.reversed(),sessions=snapshot.sessions!!.reversed().map { s->
            if(s.uuid==f.id(6000))s.copy(ends=s.ends!!.reversed()) else s },bows=snapshot.bows!!.reversed(),arrowSets=snapshot.arrowSets!!.reversed()))
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(incoming));val after=store.fullV3()
            assertEquals(incoming.sessions.single { it.uuid==f.id(6000) },after.sessions.single { it.uuid==f.id(6000) })
            assertEquals(after.sessions.map { it.uuid }.sorted(),after.sessions.map { it.uuid })
            assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        }
    }
    @Test fun `normal creation still rejects legacy provenance but confirmed restoration permits it`() = runBlocking<Unit> {
        val source=d.configured(d.source());val s=source.sessions.first()
        openSessionStore(path).use { store ->
            c.conflict(store.createConfiguredSession(s),ConfiguredWriteConflict.INVALID_PROVENANCE)
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source))
            val revised=s.withMetadata(s.metadata.copy(notes="Legitimate explicit local revision"),s.updatedAt.plusSeconds(1))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,revised));assertEquals(revised,store.fullV3().sessions.first())
        }
    }
    @Test fun `material only V3 import keeps historical V1 and V2 archive bytes unchanged outside explicit additions`() = runBlocking<Unit> {
        val legacy=f.archive(f.session());val codec=ArchiveJsonCodec()
        openSessionStore(path).use { store ->
            store.create(legacy);val v1=codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive)
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(r.material()))
            assertContentEquals(v1,codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            val v2=store.fullV2();assertEquals(listOf(f.bow()),v2.bows);assertEquals(legacy.sessions,v2.sessions)
            assertEquals(v2,assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(v2))).archive)
        }
    }
    @Test fun `source and plan stay exact immutable values throughout preview`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source=r.source();val p=store.previewV3(source)
            assertEquals(source,p.archive);assertFailsWith<UnsupportedOperationException> { (p.plan.ends.newUuids as MutableList).clear() }
            assertFailsWith<UnsupportedOperationException> { (p.baselineFingerprint as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (p.baselineFingerprint.values.first() as MutableList).clear() }
        }
    }
}
