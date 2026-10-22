package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3RestoreData as r
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomArchiveV3ConflictTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("conflicts-v3.db")
    private suspend fun reject(store:RoomSessionStore,source:DomainArchiveV3) {
        val before=d.contents(path);val p=store.previewV3(source);assertFalse(p.plan.canImport)
        assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
    }
    @Test fun `same session UUID with newer metadata cannot overwrite or import independent material`() = runBlocking<Unit> {
        val s=r.mixed();val changed=s.withMetadata(s.metadata.copy(title="Untrusted later revision"),s.updatedAt.plusSeconds(30))
        val source=r.accepted(c.snapshot(changed).copy(bows=r.material().toSnapshot().bows))
        openSessionStore(path).use { store ->store.createConfiguredSession(s);reject(store,source);assertTrue(store.fullV3().bows.isEmpty()) }
    }
    @Test fun `profile definition conflict rejects entire archive rather than replacing known profile`() = runBlocking<Unit> {
        val target=c.classic.targetType.copy(uuid=f.id(12000),isBuiltIn=false,name="Known club")
        val known=r.accepted(ArchiveV3Snapshot(3,1,listOf(TargetProfileSnapshot(target,null)),emptyList(),emptyList(),emptyList()))
        val incoming=r.accepted(c.snapshot(c.draft(configuration=c.classic.copy(targetType=target.copy(name="Received club")))))
        openSessionStore(path).use { store ->store.restoreV3(known);reject(store,incoming) }
    }
    @Test fun `same geometry with different regulatory edition remains a conflict`() = runBlocking<Unit> {
        val target=c.classic.targetType.copy(uuid=f.id(12000),isBuiltIn=false)
        val known=r.accepted(ArchiveV3Snapshot(3,1,listOf(TargetProfileSnapshot(target,"A")),emptyList(),emptyList(),emptyList()))
        val incoming=r.accepted(c.snapshot(c.draft(configuration=c.classic.copy(targetType=target,regulatoryEdition="B"))))
        openSessionStore(path).use { store ->store.restoreV3(known);reject(store,incoming) }
    }
    @Test fun `bow identity colliding with impact is rejected before any insertion`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->store.equipment.create(f.bow(6010));reject(store,r.onlyMixed()) }
    }
    @Test fun `arrow set identity colliding with profile is rejected globally`() = runBlocking<Unit> {
        val custom=c.classic.targetType.copy(uuid=f.id(12000),isBuiltIn=false)
        val incoming=r.accepted(c.snapshot(c.draft(configuration=c.classic.copy(targetType=custom))));val target=custom.uuid
        openSessionStore(path).use { store ->
            store.equipment.create(ArrowSet(target,"Collision",createdAt=f.now,updatedAt=f.now));reject(store,incoming)
        }
    }
    @Test fun `session root colliding with catalog identity cannot adopt it`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->store.equipment.create(f.bow(6000));reject(store,r.onlyMixed()) }
    }
    @Test fun `new root may not reparent a physically existing end`() = runBlocking<Unit> {
        val original=r.onlyMixed();val snapshot=original.toSnapshot();val s=snapshot.sessions!!.single();val uuid=f.id(7000)
        val received=r.accepted(snapshot.copy(sessions=listOf(s.copy(uuid=uuid,ends=s.ends!!.map { it.copy(sessionUuid=uuid) }))))
        openSessionStore(path).use { store ->store.restoreV3(original);reject(store,received) }
    }
    @Test fun `omitted history inside a known root is conflicting rather than a deletion request`() = runBlocking<Unit> {
        val original=r.onlyMixed();val snap=original.toSnapshot();val s=snap.sessions!!.single()
        val shorter=r.accepted(snap.copy(sessions=listOf(s.copy(ends=s.ends!!.dropLast(1)))))
        openSessionStore(path).use { store ->store.restoreV3(original);reject(store,shorter) }
    }
    @Test fun `empty capture cannot change initial origin to explicit despite equal values`() = runBlocking<Unit> {
        val original=r.accepted(c.snapshot(c.oneEnd()));val snap=original.toSnapshot();val s=snap.sessions!!.single();val e=s.ends!!.single()
        val altered=r.accepted(snap.copy(sessions=listOf(s.copy(ends=listOf(e.copy(origin=EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT,null)))))))
        openSessionStore(path).use { store ->store.restoreV3(original);reject(store,altered) }
    }
    @Test fun `unrelated catalog revision after preview invalidates the exact candidate without DML`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(f.bow());val p=store.previewV3(r.onlyMixed())
            store.equipment.update(f.bow(),f.bow().copy(name="Concurrent",updatedAt=f.now.plusSeconds(1)))
            val before=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `different representation with identical business content invalidates preview`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(d.source());val beforeDomain=store.fullV3();val p=store.previewV3(r.material())
            d.raw(path,"UPDATE sessions SET archiveSchemaVersion=3 WHERE uuid='${f.id(1)}'",
                "UPDATE session_configurations SET representation='CONFIGURED' WHERE sessionUuid='${f.id(1)}'")
            assertEquals(beforeDomain,store.fullV3());val before=d.contents(path)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `equivalent normalized civil lexeme still invalidates exact SQL preview`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(d.source());d.raw(path,"UPDATE sessions SET localDateTime='2026-10-17T12:00:00' WHERE uuid='${f.id(1)}'")
            val beforeDomain=store.fullV3();val p=store.previewV3(r.onlyMixed())
            d.raw(path,"UPDATE sessions SET localDateTime='2026-10-17T12:00' WHERE uuid='${f.id(1)}'")
            assertEquals(beforeDomain,store.fullV3());val before=d.contents(path)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `two simultaneous native confirmations commit one archive and reject the stale baseline`() = runBlocking<Unit> {
        openSessionStore(path).use { one ->openSessionStore(path).use { two ->
            one.fullV3();two.fullV3();val a=one.previewV3(r.onlyMixed());val b=two.previewV3(r.material());val start=CompletableDeferred<Unit>()
            val jobs=listOf(async(Dispatchers.IO) { start.await();one.importArchiveV3(a) },async(Dispatchers.IO) { start.await();two.importArchiveV3(b) })
            start.complete(Unit);val results=jobs.awaitAll()
            assertEquals(1,results.count { it is ArchiveV3ImportResult.Imported });assertEquals(1,results.count { it is ArchiveV3ImportResult.Conflict })
            val winner=assertIs<ArchiveV3ImportResult.Imported>(results.first { it is ArchiveV3ImportResult.Imported }).preview.archive
            assertEquals(winner,one.fullV3());assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        } }
    }
    @Test fun `same confirmed candidate is stale after success but explicit new preview is idempotent`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val p=store.previewV3(r.onlyMixed());assertIs<ArchiveV3ImportResult.Imported>(store.importArchiveV3(p))
            val before=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(p))
            assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.restoreV3(p.archive));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `orphan registry is intervention not preview with partial data`() = runBlocking<Unit> {
        openSessionStore(path).use { it.fullV3() };d.raw(path,"INSERT INTO entity_identities VALUES('${f.id(9999)}','BOW')")
        val before=d.contents(path)
        openSessionStore(path).use { assertIs<ArchiveV3PreviewResult.NeedsIntervention>(it.previewImportV3(r.source())) }
        assertEquals(before,d.contents(path))
    }
    @Test fun `missing capture after preview is intervention before insertion`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV3(r.onlyMixed());val p=store.previewV3(r.material())
            d.raw(path,"DELETE FROM end_configurations WHERE endUuid='${f.id(6001)}'");val before=d.contents(path)
            val result=assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p))
            assertEquals(ArchiveV3CommitOutcome.NOT_ATTEMPTED,result.commitOutcome);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `old V1 V2 confirmations cannot erase newly restored CONFIGURED sessions`() = runBlocking<Unit> {
        val v1=f.archive(f.session());val v2=v2(listOf(f.session()))
        openSessionStore(path).use { store ->
            val one=assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(v1)).preview;val two=store.previewV2(v2)
            store.restoreV3(r.onlyMixed());val before=d.contents(path)
            assertIs<ArchiveImportResult.NeedsIntervention>(store.importArchive(one))
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(two));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `closed store preview and confirmation are technical failures with no implicit reopening`() = runBlocking<Unit> {
        val store=openSessionStore(path);val p=store.previewV3(r.material());store.close();val before=d.contents(path)
        assertIs<ArchiveV3PreviewResult.TechnicalFailure>(store.previewImportV3(r.material()))
        val result=assertIs<ArchiveV3ImportResult.TechnicalFailure>(store.importArchiveV3(p));assertSame(p,result.preview);assertEquals(before,d.contents(path))
    }
}
