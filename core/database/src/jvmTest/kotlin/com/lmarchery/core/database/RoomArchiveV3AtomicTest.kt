package com.lmarchery.core.database

import com.lmarchery.core.database.ArchiveV3RestoreData as r
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import kotlin.test.*

class RoomArchiveV3AtomicTest {
    @TempDir lateinit var directory:Path
    private val path get()=directory.resolve("atomic-v3.db")
    @Test fun `native failure after real impact INSERT rolls back entire source and preserves all old tables`() = runBlocking<Unit> {
        val inserted=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("INSERT")&&sql.contains("`impacts`"))inserted.set(true) }.use { store ->
            store.equipment.create(f.bow(850));val p=store.previewV3(r.source());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fail_late BEFORE INSERT ON arrow_sets BEGIN SELECT RAISE(ABORT,'late SQL error'); END")
            val result=assertIs<ArchiveV3ImportResult.CommitUnconfirmed>(store.importArchiveV3(p))
            assertSame(p,result.preview);assertTrue(inserted.get());assertEquals(before,d.contents(path))
        }
        openSessionStore(path).use { assertEquals(listOf(f.bow(850)),it.fullV3().bows);assertTrue(it.fullV3().sessions.isEmpty()) }
    }
    @Test fun `late altered current decision is detected by complete reconstruction and rolled back`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV3(r.material());val p=store.previewV3(r.onlyMixed());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER corrupt_decision AFTER INSERT ON score_decisions BEGIN UPDATE score_decisions SET assertions_final=99 WHERE impactUuid=NEW.impactUuid AND revision=NEW.revision; END")
            assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `late modification of existing catalog causes complete rollback including imported captures`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV3(r.material());val p=store.previewV3(r.onlyMixed());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER alter_old AFTER INSERT ON session_configurations BEGIN UPDATE bows SET name='Unexpected' WHERE uuid='${f.id(800)}'; END")
            assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `late equivalent raw SQL change of legacy date is detected despite equal business value`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(d.source());d.raw(path,"UPDATE sessions SET localDateTime='2026-10-17T12:00:00' WHERE uuid='${f.id(1)}'")
            val p=store.previewV3(r.onlyMixed());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER alter_lexeme AFTER INSERT ON session_configurations BEGIN UPDATE sessions SET localDateTime='2026-10-17T12:00' WHERE uuid='${f.id(1)}'; END")
            val result=assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p))
            assertEquals("importV3.unchanged.sessions",result.diagnostic.path);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `late orphan identity is intervention with full rollback rather than partial archive`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV3();val p=store.previewV3(r.onlyMixed());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER orphan AFTER INSERT ON session_configurations BEGIN INSERT INTO entity_identities VALUES('${f.id(9999)}','BOW'); END")
            val result=assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p))
            assertEquals(ArchiveV3CommitOutcome.UNCONFIRMED,result.commitOutcome);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `late foreign key violation rolls back profiles roots descendants and registry`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV3();val p=store.previewV3(r.onlyMixed());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fk_failure AFTER INSERT ON end_configurations BEGIN UPDATE end_configurations SET previousEndUuid='${f.id(9999)}' WHERE endUuid=NEW.endUuid; END")
            assertIs<ArchiveV3ImportResult.CommitUnconfirmed>(store.importArchiveV3(p));assertEquals(before,d.contents(path))
            assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        }
    }
    @Test fun `cancelled caller before confirmation performs no DML`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val p=store.previewV3(r.source());val before=d.contents(path);var caught=false
            val job=launch(start=CoroutineStart.LAZY) { try { currentCoroutineContext().job.cancel();store.importArchiveV3(p) }
                catch(_:CancellationException) { caught=true } }
            job.start();job.join();assertTrue(caught);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `real caller cancellation after native INSERT propagates and rolls back whole archive`() = runBlocking<Unit> {
        val cancel=AtomicReference<(() -> Unit)?>(null);val inserted=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("INSERT")&&sql.contains("`impacts`")) { inserted.set(true);cancel.get()?.invoke() } }.use { store ->
            val p=store.previewV3(r.source());val before=d.contents(path);var caught=false
            val job=launch { val caller=currentCoroutineContext().job;cancel.set { caller.cancel() }
                try { store.importArchiveV3(p) } catch(_:CancellationException) { caught=true } }
            job.join();cancel.set(null);assertTrue(inserted.get());assertTrue(caught);assertEquals(before,d.contents(path))
        }
        openSessionStore(path).use { assertTrue(it.fullV3().sessions.isEmpty()) }
    }
    @Test fun `lost acknowledgement after acquired native COMMIT is uncertain and reconciles with fresh no DML preview`() = runBlocking<Unit> {
        val arm=AtomicBoolean(false);val commitPending=AtomicBoolean(false);val committed=AtomicBoolean(false);val source=r.onlyMixed()
        lateinit var preview:ArchiveV3ImportPreview
        hookedStore(path) { sql,_->
            if(sql.startsWith("INSERT")&&sql.contains("`sessions`")&&arm.get())commitPending.set(true)
            if(sql=="END TRANSACTION"&&commitPending.compareAndSet(true,false)) { arm.set(false);committed.set(true);throw IllegalStateException("Synthetic lost commit ACK") }
        }.use { store ->
            preview=store.previewV3(source);arm.set(true)
            val result=assertIs<ArchiveV3ImportResult.CommitUnconfirmed>(store.importArchiveV3(preview));assertSame(preview,result.preview);assertTrue(committed.get())
        }
        openSessionStore(path).use { store ->
            assertEquals(source,store.fullV3());f.denyWrites(path,d.tables);val before=d.contents(path)
            assertIs<ArchiveV3ImportResult.Conflict>(store.importArchiveV3(preview))
            assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.restoreV3(preview.archive));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `cancellation after actual COMMIT never implies rollback and retains exact candidate`() = runBlocking<Unit> {
        val arm=AtomicBoolean(false);val pending=AtomicBoolean(false);val source=r.onlyMixed()
        hookedStore(path) { sql,_->
            if(sql.startsWith("INSERT")&&sql.contains("`sessions`")&&arm.get())pending.set(true)
            if(sql=="END TRANSACTION"&&pending.compareAndSet(true,false)) { arm.set(false);throw CancellationException("After COMMIT acquired") }
        }.use { store ->
            val p=store.previewV3(source);arm.set(true)
            val error=assertFailsWith<ArchiveV3ImportCancelled> { store.importArchiveV3(p) }
            assertSame(p,error.preview);assertEquals(ArchiveV3CommitOutcome.UNCONFIRMED,error.commitOutcome)
        }
        openSessionStore(path).use { assertEquals(source,it.fullV3()) }
    }
    @Test fun `legitimate explicit new confirmation succeeds after verified rollback and failure removal`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source=r.onlyMixed();val p=store.previewV3(source);val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fail_capture BEFORE INSERT ON end_configurations BEGIN SELECT RAISE(ABORT,'fail'); END")
            assertIs<ArchiveV3ImportResult.CommitUnconfirmed>(store.importArchiveV3(p));assertEquals(before,d.contents(path));assertTrue(store.fullV3().sessions.isEmpty())
            f.rows(path,"DROP TRIGGER fail_capture")
            assertIs<ArchiveV3ImportResult.Imported>(store.importArchiveV3(store.previewV3(source)));assertEquals(source,store.fullV3())
        }
    }
    @Test fun `preview uses one real reader snapshot even while a second store commits catalog revision`() = runBlocking<Unit> {
        openSessionStore(path).use { it.restoreV3(r.material()) }
        val enter=CountDownLatch(1);val resume=CountDownLatch(1);val armed=AtomicBoolean(true)
        hookedStore(path) { sql,row->if(row&&sql=="SELECT * FROM entity_identities ORDER BY uuid"&&armed.compareAndSet(true,false)) {
            enter.countDown();check(resume.await(15,TimeUnit.SECONDS))
        } }.use { reader ->
            val pending=async(Dispatchers.IO) { reader.previewV3(r.onlyMixed()) }
            try { withContext(Dispatchers.IO) {
                assertTrue(enter.await(15,TimeUnit.SECONDS))
                openSessionStore(path).use { writer ->
                    assertIs<EquipmentUpdateResult.Updated>(writer.equipment.update(f.bow(),f.bow().copy(name="Concurrent",updatedAt=f.now.plusSeconds(1))))
                }
            } } finally { resume.countDown() }
            val p=pending.await();assertEquals(listOf(f.bow()),p.baseline.bows)
            assertIs<ArchiveV3ImportResult.Conflict>(reader.importArchiveV3(p))
        }
    }
    @Test fun `source exceeding default text budget is rejected before preview SQL writes`() = runBlocking<Unit> {
        val snap=r.onlyMixed().toSnapshot();val s=snap.sessions!!.single()
        val source=assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(snap.copy(sessions=listOf(s.copy(
            metadata=s.metadata!!.copy(notes="x".repeat(16385))))),limits=ArchiveV3Limits(maxTextLength=16385))).archive
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            val issue=assertIs<ArchiveV3PreviewResult.NeedsIntervention>(store.previewImportV3(source)).diagnostic
            assertEquals(ArchiveV3IssueCode.LIMIT,issue.archiveIssue!!.code);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `combined collection budget is checked before any insertion although both archives are valid`() = runBlocking<Unit> {
        openSessionStore(path).use { it.equipment.create(f.bow()) }
        val id="printf('70000000-0000-4000-8000-%012d',x)"
        val series="WITH RECURSIVE n(x) AS (SELECT 10000 UNION ALL SELECT x+1 FROM n WHERE x<29998) "
        d.raw(path,"PRAGMA foreign_keys=ON","BEGIN IMMEDIATE",series+"INSERT INTO entity_identities SELECT $id,'BOW' FROM n",
            series+"INSERT INTO bows SELECT $id,name,type,drawWeightLbsBits,brand,model,notes,created_epochSecond,created_nano,updated_epochSecond,updated_nano FROM n CROSS JOIN bows WHERE uuid='${f.id(800)}'","COMMIT")
        val source=r.accepted(snapshotArchiveV3(emptyList(),emptyList(),listOf(f.bow(9000)),emptyList()))
        openSessionStore(path).use { store ->
            val p=store.previewV3(source);assertTrue(p.plan.canImport);assertEquals(20000,store.fullV3().bows.size)
            val before=d.contents(path);f.denyWrites(path,d.tables)
            val result=assertIs<ArchiveV3ImportResult.NeedsIntervention>(store.importArchiveV3(p))
            assertEquals(ArchiveV3CommitOutcome.NOT_ATTEMPTED,result.commitOutcome)
            assertEquals(ArchiveV3IssueCode.LIMIT,result.diagnostic.archiveIssue!!.code);assertEquals(before,d.contents(path))
        }
    }
}
