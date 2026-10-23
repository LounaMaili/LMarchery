package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Native bundled SQLite, real files, Room reader/writer transactions; no Android device implied. */
class RoomConfiguredEditorTest {
    @TempDir lateinit var directory: Path
    private val path get()=directory.resolve("editor.db")
    @Test fun `typed reader distinguishes physical configured legacy occupied and missing identities without DML`() = runBlocking<Unit> {
        d.historical(path,1,d.source(false))
        val created=c.oneEnd()
        openSessionStore(path).use { store ->
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(created))
            val before=d.contents(path); f.denyWrites(path,d.tables)
            assertEquals(created,assertIs<ConfiguredSessionReadResult.Found>(store.readConfiguredSession(created.uuid)).session)
            assertIs<ConfiguredSessionReadResult.LegacyHomogeneous>(store.readConfiguredSession(f.id(1)))
            assertIs<ConfiguredSessionReadResult.IdentityOccupied>(store.readConfiguredSession(c.classic.targetType.uuid))
            assertIs<ConfiguredSessionReadResult.IdentityOccupied>(store.readConfiguredSession(created.ends.single().uuid))
            assertIs<ConfiguredSessionReadResult.NotFound>(store.readConfiguredSession(f.id(999999)))
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `configured reader fails entire corrupt source without returning partial editable content`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.createConfiguredSession(c.oneEnd()); d.raw(path,"UPDATE end_configurations SET distanceM=0")
            val before=d.contents(path); val failure=assertIs<ConfiguredSessionReadResult.NeedsIntervention>(store.readConfiguredSession(c.draft().uuid))
            assertTrue(failure.diagnostic.path.contains("end")); assertEquals(before,d.contents(path))
        }
    }
    @Test fun `closed typed reader reports technical failure without falling back to legacy projection`() = runBlocking<Unit> {
        val store=openSessionStore(path); store.createConfiguredSession(c.draft()); store.close()
        assertIs<ConfiguredSessionReadResult.TechnicalFailure>(store.readConfiguredSession(c.draft().uuid))
    }
    @Test fun `full editable workflow on migrated V1 survives reopen JSON V3 restore and preserves outside rows`() = runBlocking<Unit> {
        val historical=d.source(false); d.historical(path,1,historical)
        var s=c.draft(configuration=c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=3))
        lateinit var archive: DomainArchiveV3
        openSessionStore(path).use { store ->
            store.fullV3() // migration is complete before the outside snapshot
            val old=f.contents(path,f.oldTables)
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(f.bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(f.arrows()))
            val equipment=f.contents(path,listOf("bows","arrow_sets"))
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s))
            suspend fun save(next:ConfiguredSession) {
                assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));s=next
                assertEquals(s,assertIs<ConfiguredSessionReadResult.Found>(store.readConfiguredSession(s.uuid)).session)
            }
            save(s.start(f.now.plusSeconds(1)));save(s.createNextEnd(f.id(6001),f.now.plusSeconds(2)))
            save(s.withImpact(f.id(6001),c.arrow(s,1,.105f,3)))
            val boundary=c.classic.targetType.scoreBoundaries().first { it.inner.score==10 && it.outer.score==9 }
            val arrow=s.ends.first().end.impacts.single().uuid
            save(s.decideImpact(f.id(6001),arrow,ScoreDecisionRequest.JudgeBoundary(boundary.reference,boundary.inner,f.now.plusSeconds(4)),f.now.plusSeconds(4)))
            save(s.repositionImpact(f.id(6001),arrow,NormalizedPoint(.25f,0f),f.now.plusSeconds(5)," Placement corrigé 🏹 "))
            save(s.decideImpact(f.id(6001),arrow,ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9),"Observation",f.now.plusSeconds(6)),f.now.plusSeconds(6)))
            save(s.repositionImpact(f.id(6001),arrow,NormalizedPoint(.35f,-0.0f),f.now.plusSeconds(7),"Deuxième correction"))
            save(s.withImpact(f.id(6001),c.arrow(s,2,0f,8)));save(s.withImpact(f.id(6001),c.arrow(s,3,1.1f,9)))
            save(s.validateEnd(f.id(6001),f.now.plusSeconds(10)))
            save(s.createNextEnd(f.id(6002),f.now.plusSeconds(11),NextEndConfigurationChoice.Replace(c.compound)))
            save(s.withImpact(f.id(6002),c.arrow(s,1,.075f,12).copy(uuid=f.id(6200))))
            save(s.createNextEnd(f.id(6003),f.now.plusSeconds(13),NextEndConfigurationChoice.Replace(c.compound)))
            save(s.createNextEnd(f.id(6004),f.now.plusSeconds(14)))
            save(s.createNextEnd(f.id(6005),f.now.plusSeconds(15),NextEndConfigurationChoice.Replace(c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=7))))
            save(s.createNextEnd(f.id(6006),f.now.plusSeconds(16),NextEndConfigurationChoice.Replace(c.classic)))
            val captures=f.contents(path,listOf("session_configurations","end_configurations"))
            f.denyWrites(path,listOf("session_configurations","end_configurations"))
            save(s.withMetadata(s.metadata.copy(title=" Saved synthetic 🏹 ",notes=""),f.now.plusSeconds(17)))
            assertEquals(captures,f.contents(path,listOf("session_configurations","end_configurations")))
            save(s.complete(f.now.plusSeconds(18)))
            assertEquals(equipment,f.contents(path,listOf("bows","arrow_sets")))
            // Restrict each old table to its original rows, including the global identity registry and profiles.
            val after=f.contents(path,f.oldTables)
            old.forEach { (table,rows) -> assertTrue(after.getValue(table).containsAll(rows),"Old $table rows changed") }
            archive=store.fullV3();assertEquals(s,archive.sessions.single { it.uuid==s.uuid })
            assertEquals(listOf(EndConfigurationOrigin.Initial,EndConfigurationOrigin.Explicit,EndConfigurationOrigin.Explicit,
                EndConfigurationOrigin.Inherited(f.id(6003)),EndConfigurationOrigin.Explicit,EndConfigurationOrigin.Explicit),s.ends.map { it.origin })
            assertEquals(2,s.ends.first().end.impacts.first().repositions.size)
            assertTrue(s.ends.first().end.impacts[1].isX);assertTrue(s.ends.first().end.impacts[2].isMiss)
            assertEquals(9,s.ends[1].end.impacts.single().scoreFinal)
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
        }
        assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty());assertEquals(listOf("ok"),f.rows(path,"PRAGMA integrity_check"))
        assertEquals(12,f.rows(path,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','sqlite_sequence','android_metadata')").size)
        openSessionStore(path).use { assertEquals(archive,it.fullV3());assertEquals(s,assertIs<ConfiguredSessionReadResult.Found>(it.readConfiguredSession(s.uuid)).session) }
        val codec=ArchiveV3JsonCodec();val bytes=codec.encode(archive);val decoded=assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(bytes)).archive
        assertEquals(archive,decoded)
        val second=directory.resolve("restored.db")
        openSessionStore(second).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(decoded));assertEquals(archive,store.fullV3())
            f.denyWrites(second,d.tables);assertIs<ArchiveV3ImportResult.AlreadyPresent>(store.restoreV3(decoded));assertEquals(archive,store.fullV3())
        }
        openSessionStore(second).use { assertEquals(archive,it.fullV3()) }
        assertTrue(f.rows(second,"PRAGMA foreign_key_check").isEmpty());assertEquals(listOf("ok"),f.rows(second,"PRAGMA integrity_check"))
    }
    @Test fun `two editor writers with exact expected conflict then can explicitly reconcile from typed read`() = runBlocking<Unit> {
        val s=c.oneEnd();val a=s.withMetadata(s.metadata.copy(title="A"),f.now.plusSeconds(20));val b=s.withMetadata(s.metadata.copy(title="B"),f.now.plusSeconds(20))
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            one.createConfiguredSession(s)
            val results=coroutineScope { listOf(async(Dispatchers.Default){one.updateConfiguredSession(s,a)},async(Dispatchers.Default){two.updateConfiguredSession(s,b)}).awaitAll() }
            assertEquals(1,results.count { it is ConfiguredWriteResult.Updated });assertEquals(1,results.count { it is ConfiguredWriteResult.Conflict })
            val actual=assertIs<ConfiguredSessionReadResult.Found>(two.readConfiguredSession(s.uuid)).session
            val other=actual.withMetadata(actual.metadata.copy(title="Explicitly resolved"),f.now.plusSeconds(21))
            assertIs<ConfiguredWriteResult.Updated>(two.updateConfiguredSession(actual,other));assertEquals(other,one.fullV3().sessions.single())
        } }
    }
    @Test fun `V3 restored historical provenance stays configured and editable without changing its existing captures`() = runBlocking<Unit> {
        val source=d.configured(d.source())
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ImportResult.Imported>(store.restoreV3(source))
            val old=assertIs<ConfiguredSessionReadResult.Found>(store.readConfiguredSession(f.id(301))).session
            assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED,old.initialConfigurationOrigin)
            val before=f.contents(path,listOf("session_configurations","end_configurations"))
            val next=old.createNextEnd(f.id(9800),f.now.plusSeconds(50))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(old,next))
            assertEquals(EndConfigurationOrigin.Inherited(old.ends.last().uuid),next.ends.last().origin)
            val after=f.contents(path,listOf("session_configurations","end_configurations"))
            before.forEach { (table,rows)->assertTrue(after.getValue(table).containsAll(rows)) }
            assertEquals(next,assertIs<ConfiguredSessionReadResult.Found>(store.readConfiguredSession(old.uuid)).session)
        }
    }

}
