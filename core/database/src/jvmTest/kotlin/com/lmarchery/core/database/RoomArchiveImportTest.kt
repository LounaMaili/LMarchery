package com.lmarchery.core.database

import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomArchiveImportTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("source.db")
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private fun t(n: Long) = now.plusSeconds(n)
    private fun id(n: Int) = "20000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val tables = listOf("entity_identities", "target_profiles", "target_zones", "sessions", "ends", "impacts", "score_decisions", "impact_repositions")
    private fun seed(index: Int, populated: Boolean = false, completed: Boolean = false): Session {
        val target = if (completed) WA.compound60 else WA.recurve40
        var session = Session(id(index), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic archive $index", targetType = target, distanceM = target.defaultDistanceM,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true,
            scoringMode = ScoringMode.TRAINING, createdAt = now, updatedAt = now)
        if (!populated) return session
        fun arrow(n: Int, p: NormalizedPoint) = ArrowImpact(id(index * 100 + n), n, p, inputMode = InputMode.MANUAL,
            createdAt = t(3), updatedAt = t(3), targetType = target)
        val first = target.scoreBoundaries().first()
        val impact = arrow(1, NormalizedPoint(.105f, -0.0f))
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(first.reference, first.inner, t(4)), t(4))
            .reposition(NormalizedPoint(.25f, -0.0f), t(5), "Wrong position")
            .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "Observed miss", t(6)), t(6))
        val arrows = listOf(impact, arrow(2, NormalizedPoint(.2f, 0f))) +
            if (completed) listOf(arrow(3, NormalizedPoint(0f,0f))) else emptyList()
        val end = End(id(index * 100 + 90), 1, EndStatus.EDITING, arrows, createdAt = t(2), updatedAt = t(6))
        session = session.start(t(1)).withEnd(if (completed) end.validate(t(7)) else end)
        return if (completed) session.complete(t(8)) else session
    }
    private fun archive(session: Session): DomainArchive {
        val profile = BuiltInTargetRegistry.find(session.targetType.uuid)!!
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), listOf(session)))).archive
    }
    private suspend fun create(store: RoomSessionStore, session: Session) {
        assertIs<SessionCreateResult.Created>(store.create(archive(session)))
    }
    private fun rows(sql: String): List<String> = BundledSQLiteDriver().open(path.toString()).use { c ->
        c.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
        c.prepare(sql).use { s -> buildList {
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "<null>" else s.getText(it) })
        } }
    }
    private fun contents() = tables.associateWith { rows("SELECT * FROM $it ORDER BY 1,2") }

    private fun multi(vararg sessions: Session): DomainArchive = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
        sessions.map { archive(it).profiles.single() }.distinct().sortedBy { it.definition!!.uuid }, sessions.sortedBy { it.uuid }))).archive
    private suspend fun preview(store: RoomSessionStore, archive: DomainArchive) =
        assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(archive)).preview
    private suspend fun imported(store: RoomSessionStore, archive: DomainArchive, added: Int, identical: Int = 0) {
        val p=preview(store,archive)
        assertEquals(added,p.plan.newSessionUuids.size);assertTrue(p.plan.conflicts.isEmpty())
        assertEquals(ArchiveImportResult.Imported(added,identical),store.importArchive(p))
    }
    private fun guard(vararg ops: String) { tables.forEach { table -> ops.forEach { op ->
        rows("CREATE TRIGGER guard_${table}_$op BEFORE $op ON $table BEGIN SELECT RAISE(ABORT, 'forbidden write'); END")
    } } }

    @Test fun `confirmation candidate owns lists and cannot change through caller mutation`() = runBlocking<Unit> {
        val source=archive(seed(1,true))
        openSessionStore(path).use { store ->
            val real=preview(store,source)
            val ids=real.plan.newSessionUuids.toMutableList()
            val candidate=ArchiveImportPreview(source,real.plan.copy(newSessionUuids=ids))
            ids.clear();assertEquals(real.plan,candidate.plan)
            assertFailsWith<UnsupportedOperationException> { (candidate.plan.newSessionUuids as MutableList).clear() }
            assertEquals(ArchiveImportResult.Imported(1,0),store.importArchive(candidate))
        }
    }
    @Test fun `custom definition and edition are preserved and divergences conflict on disk`() = runBlocking<Unit> {
        val definition=WA.recurve40.copy(uuid=id(9000),isBuiltIn=false,name="Synthetic custom")
        fun custom(n:Int, profile:TargetProfileSnapshot):DomainArchive {
            val snapshot=archive(seed(n,true)).toSnapshot()
            val session=snapshot.sessions.single().let { original -> original.copy(targetTypeUuid=definition.uuid,
                ends=original.ends.map { e ->e.copy(impacts=e.impacts.map { it.copy(targetTypeUuid=definition.uuid) }) }) }
            return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshot.copy(profiles=listOf(profile),sessions=listOf(session)))).archive
        }
        val profile=TargetProfileSnapshot(definition,"club1");val first=custom(1,profile)
        openSessionStore(path).use { store ->
            imported(store,first,1);val before=contents()
            for(p in listOf(profile.copy(regulatoryEdition="club2"),profile.copy(definition=definition.copy(name="Other")))) {
                val preview=preview(store,custom(2,p));assertTrue(preview.plan.conflicts.any { it.uuid==definition.uuid })
                assertTrue(preview.plan.newSessionUuids.isEmpty())
                val existing=preview(store,custom(1,p))
                assertTrue(existing.plan.identicalSessionUuids.isEmpty())
                assertIs<ArchiveImportResult.Conflict>(store.importArchive(preview));assertEquals(before,contents())
            }
        }
        openSessionStore(path).use { store ->assertEquals(first.toSnapshot(),assertIs<ArchiveReadResult.Found>(store.readArchive()).archive.toSnapshot()) }
    }
    @Test fun `unreferenced profile is refused with no silent profile-only insertion`() = runBlocking<Unit> {
        val snapshot=archive(seed(1,true)).toSnapshot()
        val source=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshot.copy(profiles=snapshot.profiles+
            TargetProfileSnapshot(WA.compound60,"2026-01-27")))).archive
        openSessionStore(path).use { store ->
            val p=preview(store,source);val before=contents();assertTrue(p.plan.conflicts.any { it.code=="UNREFERENCED_PROFILE" })
            assertIs<ArchiveImportResult.Conflict>(store.importArchive(p));assertEquals(before,contents())
        }
    }
    @Test fun `single session import preserves full content after disk close reopen and reexport`() = runBlocking<Unit> {
        val source=archive(seed(1,true))
        openSessionStore(path).use { store -> imported(store,source,1) }
        openSessionStore(path).use { store ->
            val saved=assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertEquals(source.toSnapshot(),saved.toSnapshot())
            assertContentEquals(ArchiveJsonCodec().encode(source),ArchiveJsonCodec().encode(saved))
        }
        println("Atomic import: native SQLite ${rows("SELECT sqlite_version()").single()}, disk close/reopen and exact reexport")
    }
    @Test fun `multiple new sessions share one profile and include partial and completed ends`() = runBlocking<Unit> {
        val source=multi(seed(1,true),seed(2,true),seed(3,true,true))
        openSessionStore(path).use { store ->
            imported(store,source,3)
            assertEquals(listOf("2"),rows("SELECT COUNT(*) FROM target_profiles"))
            assertEquals(source.toSnapshot(),assertIs<ArchiveReadResult.Found>(store.readArchive()).archive.toSnapshot())
        }
    }
    @Test fun `identical reimport and preview perform no writes under blocking triggers`() = runBlocking<Unit> {
        val source=multi(seed(1,true),seed(2,true,true))
        openSessionStore(path).use { store ->
            imported(store,source,2);guard("INSERT","UPDATE","DELETE")
            val before=contents();imported(store,source,0,2);assertEquals(before,contents())
        }
    }
    @Test fun `mixed import never updates deletes or reinserts existing entities`() = runBlocking<Unit> {
        val old=seed(1,true);val added=seed(2,true)
        openSessionStore(path).use { store ->
            create(store,old);val before=contents();guard("UPDATE","DELETE")
            rows("CREATE TRIGGER protect_old BEFORE INSERT ON sessions WHEN NEW.uuid='${old.uuid}' BEGIN SELECT RAISE(ABORT, 'no reinsertion'); END")
            imported(store,multi(old,added),1,1)
            assertEquals(old,assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
            tables.forEach { assertTrue(contents().getValue(it).containsAll(before.getValue(it)),it) }
        }
    }
    @Test fun `divergent session refuses the whole archive before any insert`() = runBlocking<Unit> {
        val old=seed(1,true)
        openSessionStore(path).use { store ->
            create(store,old);guard("INSERT","UPDATE","DELETE");val before=contents()
            val p=preview(store,multi(old.copy(title="Different"),seed(2,true)))
            assertTrue(p.plan.conflicts.any { it.uuid==old.uuid })
            assertIs<ArchiveImportResult.Conflict>(store.importArchive(p));assertEquals(before,contents())
        }
    }
    @Test fun `end impact and cross-kind UUID collisions refuse all sessions`() = runBlocking<Unit> {
        val old=seed(1,true);val source=multi(seed(2,true)).toSnapshot()
        openSessionStore(path).use { store ->
            create(store,old);val before=contents()
            val session=source.sessions.single();val end=session.ends.single()
            val variants=listOf(session.copy(ends=listOf(end.copy(uuid=old.ends.single().uuid))),
                session.copy(ends=listOf(end.copy(impacts=end.impacts.mapIndexed { i,a -> if(i==0) a.copy(uuid=old.ends.single().impacts[0].uuid, repositions=a.repositions.map { it.copy(impactUuid=old.ends.single().impacts[0].uuid) }) else a }))),
                session.copy(uuid=old.ends.single().uuid))
            for (s in variants) {
                val a=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(source.copy(sessions=listOf(s)))).archive
                val p=preview(store,a);assertTrue(p.plan.conflicts.isNotEmpty())
                assertIs<ArchiveImportResult.Conflict>(store.importArchive(p));assertEquals(before,contents())
            }
        }
    }
    @Test fun `registry-only identity cannot be claimed by a new impact`() = runBlocking<Unit> {
        val source=archive(seed(1,true));val id=source.sessions[0].ends[0].impacts[0].uuid
        openSessionStore(path).use { store ->
            assertIs<ArchiveReadResult.Found>(store.readArchive())
            rows("INSERT INTO entity_identities VALUES('$id','END')")
            val before=contents();val p=preview(store,source)
            assertTrue(p.plan.conflicts.any { it.uuid==id && it.code=="REGISTERED_IDENTITY_CONFLICT" });assertTrue(p.plan.newSessionUuids.isEmpty())
            assertIs<ArchiveImportResult.Conflict>(store.importArchive(p));assertEquals(before,contents())
        }
    }
    @Test fun `modified persisted profile blocks import instead of substituting published definition`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            create(store,seed(1,true));rows("UPDATE target_profiles SET name='Altered'")
            val before=contents()
            assertIs<ArchiveImportPreviewResult.NeedsIntervention>(store.previewImport(archive(seed(2,true))))
            assertEquals(before,contents())
        }
    }
    @Test fun `second session insert failure rolls back first session profiles and identities`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            assertIs<ArchiveReadResult.Found>(store.readArchive())
            rows("CREATE TRIGGER fail_second BEFORE INSERT ON sessions WHEN NEW.uuid='${id(2)}' BEGIN SELECT RAISE(ABORT, 'second insertion failed'); END")
            val p=preview(store,multi(seed(1,true),seed(2,true,true)));val before=contents()
            assertIs<ArchiveImportResult.TechnicalFailure>(store.importArchive(p));assertEquals(before,contents())
        }
    }
    @Test fun `late unique constraint failure rolls back every table`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            assertIs<ArchiveReadResult.Found>(store.readArchive())
            rows("CREATE TRIGGER fail_late AFTER INSERT ON impact_repositions BEGIN INSERT INTO entity_identities(uuid,kind) VALUES(NEW.impactUuid,'IMPACT'); END")
            val p=preview(store,multi(seed(1,true),seed(2,true)));val before=contents()
            assertIs<ArchiveImportResult.TechnicalFailure>(store.importArchive(p));assertEquals(before,contents())
        }
    }
    @Test fun `readback corruption and unexpected changes to old data roll back successful inserts`() = runBlocking<Unit> {
        val old=seed(1,true)
        openSessionStore(path).use { store ->
            create(store,old)
            rows("CREATE TRIGGER alter_old AFTER INSERT ON sessions WHEN NEW.uuid='${id(2)}' BEGIN UPDATE sessions SET title='Unexpected' WHERE uuid='${old.uuid}'; END")
            val p=preview(store,archive(seed(2,true)));val before=contents()
            assertIs<ArchiveImportResult.NeedsIntervention>(store.importArchive(p));assertEquals(before,contents())
            rows("DROP TRIGGER alter_old")
            rows("CREATE TRIGGER alter_new AFTER INSERT ON impacts BEGIN UPDATE impacts SET xBits=0 WHERE uuid=NEW.uuid; END")
            val p2=preview(store,archive(seed(2,true)));val before2=contents()
            assertIs<ArchiveImportResult.NeedsIntervention>(store.importArchive(p2));assertEquals(before2,contents())
        }
    }
    @Test fun `concurrent writer invalidates new versus identical preview without silently replacing plan`() = runBlocking<Unit> {
        val source=archive(seed(1,true))
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            val p=preview(one,source);imported(two,source,1);val before=contents()
            assertIs<ArchiveImportResult.Conflict>(one.importArchive(p));assertEquals(before,contents())
            imported(one,source,0,1)
        } }
    }
    @Test fun `known session modification between preview and confirmation blocks new additions`() = runBlocking<Unit> {
        val old=seed(1,true)
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            create(one,old);val p=preview(one,multi(old,seed(2,true)))
            val changed=old.copy(title="Other writer",updatedAt=t(9))
            assertIs<SessionUpdateResult.Updated>(two.update(archive(old),archive(changed)))
            val before=contents();assertIs<ArchiveImportResult.Conflict>(one.importArchive(p));assertEquals(before,contents())
        } }
    }
    @Test fun `two independent concurrent writers yield one import and one obsolete-plan conflict`() = runBlocking<Unit> {
        val source=multi(seed(1,true),seed(2,true,true))
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            val p1=preview(one,source);val p2=preview(two,source);val start=CompletableDeferred<Unit>()
            val jobs=listOf(async(Dispatchers.Default) { start.await();one.importArchive(p1) },async(Dispatchers.Default) { start.await();two.importArchive(p2) })
            start.complete(Unit);val results=jobs.awaitAll()
            assertEquals(1,results.count { it is ArchiveImportResult.Imported });assertEquals(1,results.count { it is ArchiveImportResult.Conflict })
            assertEquals(source.toSnapshot(),assertIs<ArchiveReadResult.Found>(one.readArchive()).archive.toSnapshot())
        } }
    }
    @Test fun `cancellation after real trace insertion propagates and rolls back archive`() = runBlocking<Unit> {
        val cancel=AtomicReference<(() -> Unit)?>(null);val native=BundledSQLiteDriver()
        val driver=object:SQLiteDriver by native {
            override fun open(fileName:String):SQLiteConnection {
                val c=native.open(fileName)
                return object:SQLiteConnection by c {
                    override fun prepare(sql:String):SQLiteStatement {
                        val s=c.prepare(sql)
                        return object:SQLiteStatement by s {
                            override fun step():Boolean {
                                val result=s.step()
                                if(sql.startsWith("INSERT") && sql.contains("impact_repositions")) cancel.get()?.invoke()
                                return result
                            }
                        }
                    }
                }
            }
        }
        RoomSessionStore(Room.databaseBuilder<SessionDatabase>(path.toString()).setDriver(driver).build()).use { store ->
            val p=preview(store,multi(seed(1,true),seed(2,true)));val before=contents();var propagated=false
            val job=launch {
                val caller=currentCoroutineContext().job;cancel.set { caller.cancel() }
                try { store.importArchive(p) } catch (_:CancellationException) { propagated=true }
            }
            job.join();cancel.set(null);assertTrue(propagated);assertEquals(before,contents())
        }
    }
    @Test fun `empty unused-profile closed-store and forged previews cannot bypass validation`() = runBlocking<Unit> {
        val store=openSessionStore(path)
        val empty=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(emptyList(),emptyList()))).archive
        val p=preview(store,empty);val before=contents()
        assertIs<ArchiveImportResult.Conflict>(store.importArchive(p));assertEquals(before,contents())
        val newArchive=archive(seed(1,true));val real=preview(store,newArchive)
        val forged=ArchiveImportPreview(newArchive,real.plan.copy(newSessionUuids=emptyList()))
        assertIs<ArchiveImportResult.Conflict>(store.importArchive(forged));assertEquals(before,contents())
        store.close();assertIs<ArchiveImportResult.TechnicalFailure>(store.importArchive(real))
    }
}
