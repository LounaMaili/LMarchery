package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.scoring.configured.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomConfiguredTransactionTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("configured-transactions.db")
    @Test fun `two real writers with identical expected produce one revision and one conflict`() = runBlocking<Unit> {
        val s=c.rich();openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            one.createConfiguredSession(s);two.fullV3();val start=CompletableDeferred<Unit>()
            val proposals=listOf(s.withMetadata(s.metadata.copy(title="One"),f.now.plusSeconds(20)),
                s.withMetadata(s.metadata.copy(title="Two"),f.now.plusSeconds(20)))
            val results=listOf(one,two).mapIndexed { i,store -> async(Dispatchers.IO) { start.await();store.updateConfiguredSession(s,proposals[i]) } }
            start.complete(Unit);val outcomes=results.awaitAll()
            assertEquals(1,outcomes.count { it is ConfiguredWriteResult.Updated });assertEquals(1,outcomes.count { it is ConfiguredWriteResult.Conflict })
            val winner=assertIs<ConfiguredWriteResult.Updated>(outcomes.first { it is ConfiguredWriteResult.Updated }).request.proposed
            assertEquals(winner,c.stored(one));assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        } }
    }
    @Test fun `simultaneous identical creations confirm once and recognize exact committed content`() = runBlocking<Unit> {
        val s=c.rich();openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            one.fullV3();two.fullV3();val start=CompletableDeferred<Unit>()
            val jobs=listOf(one,two).map { store -> async(Dispatchers.IO) { start.await();store.createConfiguredSession(s) } }
            start.complete(Unit);val results=jobs.awaitAll()
            assertEquals(1,results.count { it is ConfiguredWriteResult.Created });assertEquals(1,results.count { it is ConfiguredWriteResult.AlreadyApplied })
            assertEquals(s,c.stored(one))
        } }
    }
    @Test fun `failure after actual INSERT rolls back identities profiles roots captures and descendants`() = runBlocking<Unit> {
        val inserted=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("INSERT") && sql.contains("`impacts`")) inserted.set(true) }.use { store ->
            store.fullV3();val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fail_decision BEFORE INSERT ON score_decisions BEGIN SELECT RAISE(ABORT,'late'); END")
            assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.createConfiguredSession(c.rich()))
            assertTrue(inserted.get());assertEquals(before,d.contents(path))
        }
        openSessionStore(path).use { assertTrue(it.fullV3().sessions.isEmpty()) }
    }
    @Test fun `failure after actual decision UPDATE rolls back prior decision and every other row`() = runBlocking<Unit> {
        val updated=AtomicBoolean(false);val fail=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("UPDATE") && sql.contains("`score_decisions`") && fail.get()) { updated.set(true);throw IllegalStateException("After actual decision update") } }.use { store ->
            val s=c.rich();store.createConfiguredSession(s);val before=d.contents(path)
            val next=s.decideImpact(s.ends.single().uuid,s.ends.single().end.impacts.first().uuid,
                ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5),"Observed five",f.now.plusSeconds(10)),f.now.plusSeconds(10))
            fail.set(true);assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.updateConfiguredSession(s,next));fail.set(false)
            assertTrue(updated.get());assertEquals(before,d.contents(path));assertEquals(s,c.stored(store))
        }
    }
    @Test fun `postwrite altered capture causes reconstruction failure and full rollback`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val s=c.oneEnd();store.createConfiguredSession(s);val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER break_capture AFTER UPDATE ON sessions BEGIN UPDATE end_configurations SET distanceM=99 WHERE endUuid='${f.id(6001)}'; END")
            val next=s.withMetadata(s.metadata.copy(title="Trigger"),f.now.plusSeconds(20))
            assertIs<ConfiguredWriteResult.NeedsIntervention>(store.updateConfiguredSession(s,next));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `unchanged explicit capture is protected even if altered value remains independently valid`() = runBlocking<Unit> {
        val s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(2),com.lmarchery.core.model.session.NextEndConfigurationChoice.Replace(c.compound))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER break_explicit AFTER UPDATE ON sessions BEGIN UPDATE end_configurations SET distanceM=99 WHERE endUuid='${f.id(6020)}'; END")
            assertIs<ConfiguredWriteResult.NeedsIntervention>(store.updateConfiguredSession(s,s.withMetadata(s.metadata.copy(title="Trigger"),f.now.plusSeconds(20))))
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `trigger modification of unrelated catalog is detected and entire create rolled back`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(d.source());val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER alter_catalog AFTER INSERT ON sessions BEGIN UPDATE bows SET name='Unexpected' WHERE uuid='${f.id(800)}'; END")
            assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(c.rich()));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `raw equivalent change of unrelated civil date cannot hide behind domain normalization`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.restoreV2(d.source())
            // Different SQL lexemes normalize to the same civil time; only the physical audit distinguishes them.
            f.rows(path,"UPDATE sessions SET localDateTime='2026-10-17T12:00:00' WHERE uuid='${f.id(1)}'")
            val snapshot=d.contents(path)
            f.rows(path,"CREATE TRIGGER alter_raw AFTER INSERT ON sessions BEGIN UPDATE sessions SET localDateTime='2026-10-17T12:00' WHERE uuid='${f.id(1)}'; END")
            val result=assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(c.rich()))
            assertEquals("configured.untouched",result.diagnostic.path);assertEquals(snapshot,d.contents(path))
        }
    }
    @Test fun `late orphan identity insertion is detected and rolls back whole new aggregate`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER orphan AFTER INSERT ON sessions BEGIN INSERT INTO entity_identities VALUES('${f.id(9990)}','BOW'); END")
            assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(c.draft()));assertEquals(before,d.contents(path))
        }
    }
    @Test fun `source budget overrun is rejected before writer DML`() = runBlocking<Unit> {
        val s=c.draft().withMetadata(c.draft().metadata.copy(notes="x".repeat(16385)),f.now)
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            val result=assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(s))
            assertEquals(ConfiguredCommitOutcome.NOT_ATTEMPTED,result.commitOutcome);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `corrupt stored owner is diagnosed before any configured write`() = runBlocking<Unit> {
        openSessionStore(path).use { store -> store.createConfiguredSession(c.oneEnd()) }
        d.raw(path,"UPDATE end_configurations SET sessionUuid='${f.id(999)}'")
        val before=d.contents(path)
        openSessionStore(path).use { store ->
            val result=assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(c.draft(7000)))
            assertEquals(ConfiguredCommitOutcome.NOT_ATTEMPTED,result.commitOutcome)
        };assertEquals(before,d.contents(path))
    }
    @Test fun `collection budget is enforced even for a value reconstructed with larger external limits`() = runBlocking<Unit> {
        val snapshot=c.snapshot(c.oneEnd());val root=snapshot.sessions!!.single();val end=root.ends!!.single()
        val large=root.copy(ends=List(20001) { index -> end.copy(end=end.end!!.copy(uuid=f.id(100000+index),index=index+1),
            origin=com.lmarchery.core.scoring.archive.v3.EndConfigurationOriginSnapshot(
                com.lmarchery.core.scoring.archive.v3.EndConfigurationOriginKind.EXPLICIT,null)) })
        val source=assertIs<com.lmarchery.core.scoring.archive.v3.ArchiveV3Reconstruction.Accepted>(
            com.lmarchery.core.scoring.archive.v3.reconstructArchiveV3(snapshot.copy(sessions=listOf(large)),
                limits=com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits(maxCollectionElements=20001))).archive.sessions.single()
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            val result=assertIs<ConfiguredWriteResult.NeedsIntervention>(store.createConfiguredSession(source))
            assertEquals(ConfiguredCommitOutcome.NOT_ATTEMPTED,result.commitOutcome)
            assertEquals(com.lmarchery.core.scoring.archive.v3.ArchiveV3IssueCode.LIMIT,result.diagnostic.archiveIssue!!.code)
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `cancellation after real decision modification preserves expected and rolls back all SQL`() = runBlocking<Unit> {
        val armed=AtomicBoolean(false);val observed=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("UPDATE") && sql.contains("`score_decisions`") && armed.get()) {
            observed.set(true);throw CancellationException("Interrupted actual decision update")
        } }.use { store ->
            val s=c.rich();store.createConfiguredSession(s);val before=d.contents(path)
            val next=s.decideImpact(s.ends.single().uuid,s.ends.single().end.impacts.first().uuid,
                ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5),"Observed",f.now.plusSeconds(10)),f.now.plusSeconds(10))
            armed.set(true);val error=assertFailsWith<ConfiguredWriteCancelled> { store.updateConfiguredSession(s,next) };armed.set(false)
            assertTrue(observed.get());assertEquals(ConfiguredWriteRequest.Update(s,next),error.request)
            assertEquals(ConfiguredCommitOutcome.UNCONFIRMED,error.commitOutcome);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `explicit reconciliation can apply original proposal after verified rollback without rebasing`() = runBlocking<Unit> {
        val s=c.oneEnd();val next=s.createNextEnd(f.id(6020),f.now.plusSeconds(2))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER late_fail BEFORE INSERT ON end_configurations BEGIN SELECT RAISE(ABORT,'synthetic'); END")
            val result=assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.updateConfiguredSession(s,next))
            assertEquals(ConfiguredWriteRequest.Update(s,next),result.request);assertEquals(before,d.contents(path));assertEquals(s,c.stored(store))
            f.rows(path,"DROP TRIGGER late_fail")
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(result.request.let { (it as ConfiguredWriteRequest.Update).expected },result.request.proposed))
            assertEquals(next,c.stored(store));assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        }
    }
    @Test fun `closed store is technical failure with exact intent and no attempted commit`() = runBlocking<Unit> {
        val s=c.draft();val store=openSessionStore(path);store.fullV3();store.close();val before=d.contents(path)
        val result=assertIs<ConfiguredWriteResult.TechnicalFailure>(store.createConfiguredSession(s))
        assertEquals(ConfiguredWriteRequest.Create(s),result.request);assertEquals(before,d.contents(path))
    }
    @Test fun `cancelled caller before operation never writes`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path);var caught=false
            val job=launch(start=CoroutineStart.LAZY) { try { currentCoroutineContext().job.cancel();store.createConfiguredSession(c.rich()) }
                catch(_:CancellationException) { caught=true } }
            job.start();job.join();assertTrue(caught);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `actual caller cancellation after native INSERT rolls back and propagates`() = runBlocking<Unit> {
        val cancel=AtomicReference<(() -> Unit)?>(null);val inserted=AtomicBoolean(false)
        hookedStore(path) { sql,_->if(sql.startsWith("INSERT") && sql.contains("`impacts`")) { inserted.set(true);cancel.get()?.invoke() } }.use { store ->
            store.fullV3();val before=d.contents(path);var caught=false
            val job=launch { val caller=currentCoroutineContext().job;cancel.set { caller.cancel() }
                try { store.createConfiguredSession(c.rich()) } catch(_:CancellationException) { caught=true } }
            job.join();cancel.set(null);assertTrue(inserted.get());assertTrue(caught);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `lost creation acknowledgement after real COMMIT retains intent and reconciles without DML`() = runBlocking<Unit> {
        val pending=AtomicBoolean(false);val fail=AtomicBoolean(false);val committed=AtomicBoolean(false);val s=c.rich()
        hookedStore(path) { sql,_->
            if(sql.startsWith("INSERT") && sql.contains("`sessions`") && fail.get()) pending.set(true)
            if(sql=="END TRANSACTION" && pending.compareAndSet(true,false)) { fail.set(false);committed.set(true);throw IllegalStateException("Lost creation ACK") }
        }.use { store ->
            store.fullV3();fail.set(true)
            val result=assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.createConfiguredSession(s));assertEquals(ConfiguredWriteRequest.Create(s),result.request);assertTrue(committed.get())
        }
        openSessionStore(path).use { store ->assertEquals(s,c.stored(store));f.denyWrites(path,d.tables)
            assertIs<ConfiguredWriteResult.AlreadyApplied>(store.createConfiguredSession(s)) }
    }
    @Test fun `lost update acknowledgement after real COMMIT retains both revisions and reconciles`() = runBlocking<Unit> {
        val pending=AtomicBoolean(false);val fail=AtomicBoolean(false);val s=c.rich();val next=s.withMetadata(s.metadata.copy(title="Committed"),f.now.plusSeconds(20))
        hookedStore(path) { sql,_->
            if(sql.startsWith("UPDATE") && sql.contains("`sessions`") && fail.get()) pending.set(true)
            if(sql=="END TRANSACTION" && pending.compareAndSet(true,false)) { fail.set(false);throw IllegalStateException("Lost update ACK") }
        }.use { store ->
            store.createConfiguredSession(s);fail.set(true)
            val actual=store.updateConfiguredSession(s,next)
            val result=assertIs<ConfiguredWriteResult.CommitUnconfirmed>(actual);assertEquals(ConfiguredWriteRequest.Update(s,next),result.request)
        }
        openSessionStore(path).use { store ->assertEquals(next,c.stored(store));f.denyWrites(path,d.tables)
            assertIs<ConfiguredWriteResult.AlreadyApplied>(store.updateConfiguredSession(s,next)) }
    }
    @Test fun `cancellation reported after acquired COMMIT is never evidence of rollback`() = runBlocking<Unit> {
        val pending=AtomicBoolean(false);val fail=AtomicBoolean(false);val s=c.rich()
        hookedStore(path) { sql,_->
            if(sql.startsWith("INSERT") && sql.contains("`sessions`") && fail.get()) pending.set(true)
            if(sql=="END TRANSACTION" && pending.compareAndSet(true,false)) { fail.set(false);throw CancellationException("Cancelled after COMMIT") }
        }.use { store ->
            store.fullV3();fail.set(true)
            val error=assertFailsWith<ConfiguredWriteCancelled> { store.createConfiguredSession(s) }
            assertEquals(ConfiguredCommitOutcome.UNCONFIRMED,error.commitOutcome);assertEquals(ConfiguredWriteRequest.Create(s),error.request)
        }
        openSessionStore(path).use { assertEquals(s,c.stored(it)) }
    }
    @Test fun `late SQL foreign key failure rolls back parent revision and all additions`() = runBlocking<Unit> {
        val s=c.oneEnd();val next=s.createNextEnd(f.id(6020),f.now.plusSeconds(2))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER break_fk AFTER INSERT ON end_configurations BEGIN UPDATE end_configurations SET previousEndUuid='${f.id(9990)}' WHERE endUuid=NEW.endUuid; END")
            assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.updateConfiguredSession(s,next));assertEquals(before,d.contents(path))
            assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        }
    }
}
