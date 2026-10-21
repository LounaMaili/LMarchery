package com.lmarchery.core.database

import com.lmarchery.core.export.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.summarizeConfiguredSession
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomArchiveV3ReadTest {
    @TempDir lateinit var directory: Path
    private val path get()=directory.resolve("read-v3.db")
    private val f=EquipmentTestData
    private val d=ArchiveV3DiskData
    private suspend fun seed() { openSessionStore(path).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(d.source())) } }

    @Test fun `empty V3 database returns four explicit empty lists without fabricated content`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val archive=store.fullV3()
            assertTrue(archive.profiles.isEmpty());assertTrue(archive.sessions.isEmpty());assertTrue(archive.bows.isEmpty());assertTrue(archive.arrowSets.isEmpty())
            assertEquals(3,archive.toSnapshot().schemaVersion);assertEquals(1,archive.toSnapshot().scoringContractVersion)
        }
    }
    @Test fun `complete read is deterministic across close reopen and JSON V3 round trip`() = runBlocking<Unit> {
        seed(); val expected=d.configured(d.source()); val before=d.contents(path)
        val codec=ArchiveV3JsonCodec()
        val bytes=openSessionStore(path).use { store -> assertEquals(expected,store.fullV3());codec.encode(store.fullV3()) }
        openSessionStore(path).use { store -> assertContentEquals(bytes,codec.encode(store.fullV3())) }
        assertEquals(expected,assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(bytes)).archive)
        assertEquals(before,d.contents(path))
    }
    @Test fun `SQL guards forbid every DML even no-op updates while all twelve tables are read`() = runBlocking<Unit> {
        seed(); val before=d.contents(path)
        f.denyWrites(path,d.tables)
        for (table in d.tables) {
            val firstColumn=if (table=="session_configurations") "sessionUuid" else if (table=="end_configurations") "endUuid"
                else if (table=="target_zones") "profileUuid" else if (table in listOf("score_decisions","impact_repositions")) "impactUuid" else "uuid"
            assertFailsWith<Exception> { f.rows(path,"UPDATE $table SET $firstColumn=$firstColumn") }
        }
        val guard=AtomicBoolean(false);var dml=0
        hookedStore(path) { sql,_ -> if (guard.get() && Regex("^(INSERT|UPDATE|DELETE)\\b",RegexOption.IGNORE_CASE).containsMatchIn(sql.trimStart())) { dml++;error("Reader attempted DML") } }.use { store ->
            store.fullV3();guard.set(true)
            assertEquals(d.configured(d.source()),store.fullV3())
            assertEquals(d.configured(d.source()),store.fullV3())
        }
        assertEquals(0,dml);assertEquals(before,d.contents(path))
    }
    @Test fun `catalog and unreferenced profiles are retained with opaque references`() = runBlocking<Unit> {
        seed();openSessionStore(path).use { store ->
            val archive=store.fullV3();assertEquals(d.source().profiles,archive.profiles)
            assertEquals(listOf(f.bow()),archive.bows);assertEquals(listOf(f.arrows()),archive.arrowSets)
            val opaque=archive.sessions.single { it.uuid==f.id(301) }
            assertEquals(f.id(9999),opaque.metadata.bowUuid);assertNull(opaque.metadata.arrowSetUuid)
            assertTrue(archive.profiles.any { p -> archive.sessions.none { it.initialConfiguration.targetType==p.definition } })
        }
    }
    @Test fun `native read with missing initial configuration never falls back to legacy columns`() = corrupt("DELETE FROM session_configurations WHERE sessionUuid='${f.id(1)}'","configurations")
    @Test fun `missing capture for an empty validated end is diagnosed`() = corrupt("DELETE FROM end_configurations WHERE endUuid='${f.id(202)}'","end_configurations[${f.id(202)}]")
    @Test fun `capture owned by another valid session is refused`() = corrupt("UPDATE end_configurations SET sessionUuid='${f.id(101)}' WHERE endUuid='${f.id(2)}'","end_configurations[${f.id(2)}]")
    @Test fun `initial edition contradicting the stored exact profile is refused`() = corrupt("UPDATE session_configurations SET regulatoryEdition='wrong' WHERE sessionUuid='${f.id(301)}'","session_configurations[${f.id(301)}]")
    @Test fun `end edition contradiction is refused without replacing from registry`() = corrupt("UPDATE end_configurations SET regulatoryEdition='wrong' WHERE endUuid='${f.id(302)}'","end_configurations[${f.id(302)}]")
    @Test fun `missing or unknown representation is not treated as historical`() = corrupt("UPDATE session_configurations SET representation='FUTURE' WHERE sessionUuid='${f.id(1)}'","representation")
    @Test fun `legacy marker with configured row version is a contradiction`() = corrupt("UPDATE sessions SET archiveSchemaVersion=3 WHERE uuid='${f.id(1)}'","versions")
    @Test fun `legacy capture cannot manufacture a human choice`() = corrupt("UPDATE end_configurations SET origin='EXPLICIT' WHERE endUuid='${f.id(2)}'","end_configurations")
    @Test fun `stored numeric overflow is rejected before it can wrap to a plausible Int`() = corrupt("UPDATE end_configurations SET distanceM=4294967314 WHERE endUuid='${f.id(2)}'","distanceM")
    @Test fun `REAL numeric storage cannot truncate to a valid integer`() = corrupt("UPDATE session_configurations SET distanceM=18.5 WHERE sessionUuid='${f.id(1)}'","distanceM")
    @Test fun `old numeric fields are also checked without SQL coercion`() = corrupt("UPDATE score_decisions SET selectedScore=10.5 WHERE revision=-1","selectedScore")
    @Test fun `unknown registry kind is refused before root reconstruction`() = corrupt("UPDATE entity_identities SET kind='UNKNOWN' WHERE uuid='${f.id(800)}'","identities[${f.id(800)}]")
    @Test fun `unreachable physical descendant with identity cannot disappear from archive`() = runBlocking<Unit> {
        seed()
        d.raw(path,"INSERT INTO entity_identities VALUES('${f.id(999)}','END')",
            """INSERT INTO ends SELECT '${f.id(999)}','${f.id(998)}',0,1,status,notes,impactCount,created_epochSecond,created_nano,updated_epochSecond,updated_nano,validated_epochSecond,validated_nano FROM ends WHERE uuid='${f.id(202)}'""")
        val before=d.contents(path)
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.NeedsIntervention>(it.readArchiveV3()) }
        assertEquals(before,d.contents(path))
    }
    @Test fun `extra orphan configuration is refused even if FK enforcement was bypassed externally`() = corrupt(
        "INSERT INTO end_configurations SELECT '${f.id(999)}',sessionUuid,origin,previousEndUuid,profileUuid,regulatoryEdition,distanceM,arrowCountMode,arrowCountPerEnd,isIndoor,scoringMode FROM end_configurations LIMIT 1",
        "end_configurations[${f.id(999)}]")
    @Test fun `corrupt score assertion and lost reposition trace block the whole archive`() = runBlocking<Unit> {
        for ((n,sql) in listOf("UPDATE score_decisions SET assertions_final=99 WHERE revision=-1","DELETE FROM impact_repositions WHERE sequence=1").withIndex()) {
            val file=directory.resolve("trace-$n.db")
            openSessionStore(file).use { it.restoreV2(d.source()) }
            d.raw(file,sql);val before=d.contents(file)
            openSessionStore(file).use { assertIs<ArchiveV3ReadResult.NeedsIntervention>(it.readArchiveV3()) }
            assertEquals(before,d.contents(file))
        }
    }
    @Test fun `future mixed fixture uses exact end profile not the initial alias`() = runBlocking<Unit> {
        seed();d.futureMixed(path);val before=d.contents(path)
        openSessionStore(path).use { store ->
            val archive=store.fullV3();val mixed=archive.sessions.single { it.uuid==f.id(301) }
            assertEquals(WA.compound40,mixed.initialConfiguration.targetType)
            val effective=mixed.ends.single { it.uuid==f.id(303) }
            assertEquals(WA.recurve40,effective.configuration.targetType);assertEquals(18,effective.configuration.distanceM)
            val impact=effective.end.impacts.single();assertEquals(10,impact.scoreCalculated);assertEquals(10,impact.scoreFinal)
            assertEquals(WA.recurve40,impact.targetType);assertFalse(impact.isX);assertFalse(impact.isMiss)
            assertEquals(10L,summarizeConfiguredSession(mixed).totals.recorded.score)
            val bytes=ArchiveV3JsonCodec().encode(archive)
            assertEquals(archive,assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(bytes)).archive)
        }
        assertEquals(before,d.contents(path));assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty())
    }
    @Test fun `configured inheritance contradiction retains original typed business path`() = runBlocking<Unit> {
        seed();d.futureMixed(path)
        d.raw(path,"UPDATE end_configurations SET origin='INHERITED',previousEndUuid='${f.id(302)}' WHERE endUuid='${f.id(303)}'")
        openSessionStore(path).use { store ->
            val diagnostic=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3()).diagnostic
            assertEquals(ArchiveV3StorageIssueKind.DOMAIN,diagnostic.kind)
            assertEquals(ArchiveV3IssueCode.INHERITANCE_CONFLICT,diagnostic.archiveIssue!!.code)
            assertEquals(diagnostic.archiveIssue.path,diagnostic.path)
            assertTrue(diagnostic.path.endsWith(".ends[1].origin"))
        }
    }
    @Test fun `configured profile edition and temporal contradictions are rejected without repair`() = runBlocking<Unit> {
        seed();d.futureMixed(path)
        d.raw(path,"UPDATE end_configurations SET regulatoryEdition='other' WHERE endUuid='${f.id(303)}'")
        val before=d.contents(path)
        openSessionStore(path).use { store ->
            val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3()).diagnostic.archiveIssue
            assertEquals(ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT,issue!!.code)
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `one read transaction spans captures sessions profiles and catalog under concurrent writes`() = runBlocking<Unit> {
        seed();val old=d.configured(d.source());val entered=CountDownLatch(1);val resume=CountDownLatch(1);val once=AtomicBoolean(false)
        hookedStore(path) { sql,result -> if (sql=="SELECT * FROM entity_identities ORDER BY uuid" && result && once.compareAndSet(false,true)) {
            entered.countDown();check(resume.await(20,TimeUnit.SECONDS))
        } }.use { reader ->
            val pending=async(Dispatchers.IO) { reader.fullV3() }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(20,TimeUnit.SECONDS) })
                openSessionStore(path).use { writer ->
                    val before=f.session();val after=before.copy(title="Concurrent",distanceM=70,updatedAt=f.now.plusSeconds(30))
                    assertIs<SessionUpdateResult.Updated>(writer.update(f.archive(before),f.archive(after)))
                    assertIs<EquipmentUpdateResult.Updated>(writer.equipment.update(f.bow(),f.bow().copy(name="Concurrent bow",updatedAt=f.now.plusSeconds(30))))
                    assertIs<EquipmentCreateResult.Created>(writer.equipment.create(f.bow(802)))
                }
            } finally { resume.countDown() }
            assertEquals(old,pending.await())
            val next=reader.fullV3();val session=next.sessions.first()
            assertEquals("Concurrent",session.metadata.title);assertEquals(70,session.initialConfiguration.distanceM)
            assertTrue(session.ends.all { it.configuration.distanceM==70 });assertEquals(2,next.bows.size)
        }
    }
    @Test fun `caller cancellation during collection is propagated without archive or SQL mutation`() = runBlocking<Unit> {
        seed();val before=d.contents(path);val cancel=AtomicReference<(() -> Unit)?>(null)
        hookedStore(path) { sql,result -> if (sql.startsWith("SELECT * FROM end_configurations") && result) cancel.get()?.invoke() }.use { store ->
            var propagated=false
            val job=launch { val caller=currentCoroutineContext().job;cancel.set { caller.cancel() }
                try { store.readArchiveV3() } catch (_: CancellationException) { propagated=true } }
            job.join();cancel.set(null);assertTrue(propagated)
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `SQL failures and closed lifetime remain technical not business corruption`() = runBlocking<Unit> {
        seed();val store=openSessionStore(path);store.fullV3();store.close()
        assertIs<ArchiveV3ReadResult.TechnicalFailure>(store.readArchiveV3())
        d.raw(path,"DROP TABLE end_configurations")
        openSessionStore(path).use { assertIs<ArchiveV3ReadResult.TechnicalFailure>(it.readArchiveV3()) }
    }
    @Test fun `business budgets yield structured rejection of the entire read and cannot be enlarged`() = runBlocking<Unit> {
        seed();val before=d.contents(path)
        openSessionStore(path).use { store ->
            for (limits in listOf(ArchiveV3Limits(maxCollectionElements=1),ArchiveV3Limits(maxTotalElements=1),ArchiveV3Limits(maxTextLength=1))) {
                val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3(limits)).diagnostic
                assertEquals(ArchiveV3IssueCode.LIMIT,issue.archiveIssue!!.code)
            }
            assertFailsWith<IllegalArgumentException> { store.readArchiveV3(ArchiveV3Limits(maxTotalElements=200001)) }
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `SQL preflight refuses more than twenty thousand roots before materializing them`() = runBlocking<Unit> {
        seed()
        val id="printf('70000000-0000-4000-8000-%012d',x)"
        val series="WITH RECURSIVE n(x) AS (SELECT 10000 UNION ALL SELECT x+1 FROM n WHERE x<30000) "
        d.raw(path,"PRAGMA foreign_keys=ON","BEGIN IMMEDIATE",
            series+"INSERT INTO entity_identities SELECT $id,'BOW' FROM n",
            series+"INSERT INTO bows SELECT $id,name,type,drawWeightLbsBits,brand,model,notes,created_epochSecond,created_nano,updated_epochSecond,updated_nano FROM n CROSS JOIN bows WHERE uuid='${f.id(800)}'","COMMIT")
        val before=d.contents(path)
        openSessionStore(path).use { store ->
            val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3()).diagnostic
            assertEquals(ArchiveV3IssueCode.LIMIT,issue.archiveIssue!!.code);assertEquals("bows",issue.path)
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `cumulative budget is inclusive across roots zones ends impacts and traces`() = runBlocking<Unit> {
        seed();val source=d.configured(d.source()).toSnapshot()
        val total=source.profiles!!.size+source.sessions!!.size+source.bows!!.size+source.arrowSets!!.size+
            source.profiles!!.sumOf { it.definition!!.zones.size }+source.sessions!!.sumOf { s -> s.ends!!.sumOf { e ->
                1+e.end!!.impacts.sumOf { 1+it.repositions.size } } }
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ReadResult.Found>(store.readArchiveV3(ArchiveV3Limits(maxTotalElements=total.toLong())))
            val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3(ArchiveV3Limits(maxTotalElements=total.toLong()-1))).diagnostic
            assertEquals(ArchiveV3IssueCode.LIMIT,issue.archiveIssue!!.code);assertEquals("archive",issue.path)
        }
    }
    @Test fun `stored UTF16 text budget handles astral pairs and embedded NUL without truncation`() = runBlocking<Unit> {
        seed()
        // SQL char() allows the synthetic NUL case without Java/SQLite C-string interpretation in the fixture.
        d.raw(path,"UPDATE bows SET notes='${"🏹".repeat(8192)}'")
        openSessionStore(path).use { assertEquals(16_384,it.fullV3().bows.single().notes!!.length) }
        for (value in listOf("'${"x".repeat(16_385)}'","char(0) || '${"x".repeat(70_000)}'")) {
            d.raw(path,"UPDATE bows SET notes=$value");val before=d.contents(path)
            openSessionStore(path).use { store ->
                val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3()).diagnostic
                assertEquals(ArchiveV3IssueCode.LIMIT,issue.archiveIssue!!.code);assertTrue(issue.path.contains("notes"))
            }
            assertEquals(before,d.contents(path))
        }
    }
    private fun corrupt(sql: String, pathPart: String) = runBlocking<Unit> {
        seed();d.raw(path,sql);val before=d.contents(path)
        openSessionStore(path).use { store ->
            val issue=assertIs<ArchiveV3ReadResult.NeedsIntervention>(store.readArchiveV3()).diagnostic
            assertTrue(issue.path.contains(pathPart),issue.toString());assertTrue(issue.detail.isNotBlank())
        }
        assertEquals(before,d.contents(path))
    }
}
