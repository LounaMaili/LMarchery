package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.export.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RoomConfiguredCreateTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("configured-create.db")
    @Test fun `empty domain creation persists initial capture and exact metadata after reopening`() = runBlocking<Unit> {
        val s=c.draft()
        openSessionStore(path).use { assertIs<ConfiguredWriteResult.Created>(it.createConfiguredSession(s));assertEquals(s,c.stored(it)) }
        openSessionStore(path).use { assertEquals(s,c.stored(it)) }
        assertEquals(listOf("CONFIGURED|CREATION"),f.rows(path,"SELECT representation,origin FROM session_configurations"))
        assertEquals(listOf("3|1"),f.rows(path,"SELECT archiveSchemaVersion,scoringContractVersion FROM sessions"))
        assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"));assertEquals(listOf("ok"),f.rows(path,"PRAGMA integrity_check"))
    }
    @Test fun `complete rich graph preserves manual decisions X M and two reposition traces`() = runBlocking<Unit> {
        val s=c.rich()
        openSessionStore(path).use { store ->
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s));assertEquals(s,c.stored(store))
            val impacts=c.stored(store).ends.single().end.impacts
            assertEquals(2,impacts.first().repositions.size);assertTrue(impacts.first().isCordDecisionManual)
            assertTrue(impacts[1].isX);assertTrue(impacts[2].isMiss)
            val codec=ArchiveV3JsonCodec();val archive=store.fullV3()
            assertEquals(archive,assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(archive))).archive)
        }
        openSessionStore(path).use { assertEquals(s,c.stored(it)) }
    }
    @Test fun `mixed profiles use effective end profile rather than initial alias`() = runBlocking<Unit> {
        var s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(10),NextEndConfigurationChoice.Replace(c.compound))
        val arrow=c.arrow(s,1,.075f);s=s.withImpact(s.ends.last().uuid,arrow)
        openSessionStore(path).use { store ->
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s));assertEquals(s,c.stored(store))
            assertEquals(9,c.stored(store).ends.last().end.impacts.single().scoreFinal)
            assertEquals(c.classic.targetType.uuid,f.rows(path,"SELECT profileUuid FROM sessions").single())
            assertEquals(c.compound.targetType.uuid,f.rows(path,"SELECT profileUuid FROM impacts").single())
        }
    }
    @Test fun `first initial then inherited empty captures have exact predecessor and no extra identity`() = runBlocking<Unit> {
        val s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(2)).createNextEnd(f.id(6021),f.now.plusSeconds(3))
        openSessionStore(path).use { assertIs<ConfiguredWriteResult.Created>(it.createConfiguredSession(s));assertEquals(s,c.stored(it)) }
        assertEquals(listOf("INITIAL|<null>","INHERITED|${f.id(6001)}","INHERITED|${f.id(6020)}"),
            f.rows(path,"SELECT origin,previousEndUuid FROM end_configurations ORDER BY endUuid"))
        assertEquals(listOf("5"),f.rows(path,"SELECT count(*) FROM entity_identities")) // profile + session + 3 ends
    }
    @Test fun `explicit equal choice retains explicit provenance then subsequent inheritance`() = runBlocking<Unit> {
        val s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(2),NextEndConfigurationChoice.Replace(c.classic))
            .createNextEnd(f.id(6021),f.now.plusSeconds(3))
        openSessionStore(path).use { assertIs<ConfiguredWriteResult.Created>(it.createConfiguredSession(s));assertEquals(s,c.stored(it)) }
        assertEquals(listOf("INITIAL","EXPLICIT","INHERITED"),f.rows(path,"SELECT origin FROM end_configurations ORDER BY endUuid"))
    }
    @Test fun `fixed three six custom and free are captured without sport policy inference`() = runBlocking<Unit> {
        val configurations=listOf(c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=3),
            c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=6),
            c.classic.copy(arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=5,distanceM=70),c.classic)
        var s=c.draft(configuration=configurations.first()).start(f.now)
        configurations.forEachIndexed { i,config -> s=s.createNextEnd(f.id(6020+i),f.now.plusSeconds(i+1L),NextEndConfigurationChoice.Replace(config)) }
        openSessionStore(path).use { assertIs<ConfiguredWriteResult.Created>(it.createConfiguredSession(s));assertEquals(configurations,c.stored(it).ends.map { e->e.configuration }) }
    }
    @Test fun `catalog unused profiles and opaque references survive creation on populated store`() = runBlocking<Unit> {
        val before=d.source();openSessionStore(path).use { store ->
            assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(before));val old=store.fullV3()
            val s=c.rich();assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s));val after=store.fullV3()
            assertEquals(old.profiles,after.profiles);assertEquals(old.bows,after.bows);assertEquals(old.arrowSets,after.arrowSets)
            assertEquals(old.sessions,after.sessions.filterNot { it.uuid==s.uuid });assertEquals(f.id(9999),c.stored(store).metadata.arrowSetUuid)
        }
    }
    @Test fun `historically migrated V1 rows remain byte identical after configurable creation and revision`() = runBlocking<Unit> {
        d.historical(path,1,d.source(catalog=false));val before=f.contents(path,f.oldTables)
        openSessionStore(path).use { store ->
            store.fullV3();val migrated=d.contents(path);val s=c.draft();assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s))
            val next=s.withMetadata(s.metadata.copy(title="Revised"),f.now.plusSeconds(2))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next))
            val after=d.contents(path)
            for(table in d.tables) assertTrue(after.getValue(table).containsAll(migrated.getValue(table)),table)
        }
        for(table in f.oldTables) assertTrue(f.contents(path,f.oldTables).getValue(table).containsAll(before.getValue(table)),table)
        assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
    }
    @Test fun `second identical creation performs no DML even with rejecting SQL guards`() = runBlocking<Unit> {
        val s=c.rich();val writes=AtomicInteger()
        hookedStore(path) { sql,_-> if(sql.matches(Regex("(?is)^\\s*(INSERT|UPDATE|DELETE).*"))) writes.incrementAndGet() }.use { store ->
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(s));f.denyWrites(path,d.tables);writes.set(0)
            val before=d.contents(path);assertIs<ConfiguredWriteResult.AlreadyApplied>(store.createConfiguredSession(s.copy()))
            assertEquals(0,writes.get());assertEquals(before,d.contents(path))
        }
    }
    @Test fun `different content under session identity conflicts without any row changes`() = runBlocking<Unit> {
        val s=c.draft();openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.createConfiguredSession(s.withMetadata(s.metadata.copy(title="Other"),f.now.plusSeconds(1))),ConfiguredWriteConflict.IDENTITY_OCCUPIED)
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `creation cannot present historical provenance even under new UUID`() = runBlocking<Unit> {
        val snapshot=c.snapshot(c.draft());val session=snapshot.sessions!!.single()
        val historical=c.reconstructed(snapshot.copy(sessions=listOf(session.copy(initialConfigurationOrigin=InitialConfigurationOrigin.LEGACY_DERIVED))))
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            c.conflict(store.createConfiguredSession(historical),ConfiguredWriteConflict.INVALID_PROVENANCE);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `legacy session is never implicitly converted even if V3 values are identical`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.create(f.archive(f.session()));val legacy=store.fullV3().sessions.single();val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(legacy,legacy),ConfiguredWriteConflict.NOT_CONFIGURED)
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `material root identity collision is rejected before insertion`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(f.bow(6000));val before=d.contents(path)
            c.conflict(store.createConfiguredSession(c.draft()),ConfiguredWriteConflict.IDENTITY_OCCUPIED);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `end identity cannot adopt another session or equipment identity`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(f.arrows(6001));val before=d.contents(path)
            c.conflict(store.createConfiguredSession(c.oneEnd()),ConfiguredWriteConflict.IDENTITY_OCCUPIED);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `impact identity collision with unrelated stored graph is rejected`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.equipment.create(f.bow(6010));val before=d.contents(path)
            c.conflict(store.createConfiguredSession(c.rich()),ConfiguredWriteConflict.IDENTITY_OCCUPIED);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `profile edition mismatch is not overwritten`() = runBlocking<Unit> {
        val target=c.classic.targetType.copy(uuid=f.id(9000),isBuiltIn=false,name="Club")
        openSessionStore(path).use { store ->
            store.restoreV2(v2(extraProfiles=listOf(TargetProfileSnapshot(target,"edition A"))));val before=d.contents(path)
            c.conflict(store.createConfiguredSession(c.draft(configuration=c.classic.copy(targetType=target,regulatoryEdition="edition B"))),ConfiguredWriteConflict.IDENTITY_OCCUPIED)
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `contradictory profile definition under existing UUID is rejected`() = runBlocking<Unit> {
        val target=c.classic.targetType.copy(uuid=f.id(9000),isBuiltIn=false,name="Club")
        openSessionStore(path).use { store ->
            store.restoreV2(v2(extraProfiles=listOf(TargetProfileSnapshot(target,null))));val before=d.contents(path)
            c.conflict(store.createConfiguredSession(c.draft(configuration=c.classic.copy(targetType=target.copy(name="Changed")))),ConfiguredWriteConflict.IDENTITY_OCCUPIED)
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `legacy session and catalog operations remain available beside configured records`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val s=c.rich();store.createConfiguredSession(s)
            val old=f.session();assertIs<SessionCreateResult.Created>(store.create(f.archive(old)))
            val revised=old.withEquipmentReferences(f.id(7777),null,f.now.plusSeconds(20))
            assertIs<SessionUpdateResult.Updated>(store.update(f.archive(old),f.archive(revised)))
            assertEquals(revised,assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
            store.equipment.create(f.bow());store.equipment.create(f.arrows())
            assertEquals(s,c.stored(store));assertEquals(2,store.fullV3().sessions.size)
            assertEquals(emptyList(),f.rows(path,"PRAGMA foreign_key_check"))
        }
    }
    @Test fun `all old complete archive APIs refuse even empty configured representation`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            val source=v2(listOf(f.session()));val preview=store.previewV2(source)
            store.createConfiguredSession(c.draft());val before=d.contents(path)
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive());assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(preview))
            assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(source));assertEquals(before,d.contents(path))
        }
    }
}
