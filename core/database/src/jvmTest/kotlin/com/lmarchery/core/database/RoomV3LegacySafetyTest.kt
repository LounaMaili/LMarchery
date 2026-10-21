package com.lmarchery.core.database

import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomV3LegacySafetyTest {
    @TempDir lateinit var directory: Path
    private val path get()=directory.resolve("legacy-safety.db")
    private val f=EquipmentTestData
    private val d=ArchiveV3DiskData
    private suspend fun seed() { openSessionStore(path).use { it.restoreV2(d.source()) } }

    @Test fun `single legacy read and complete archives cannot silently homogenize or omit configured data`() = runBlocking<Unit> {
        seed();d.futureMixed(path);val before=d.contents(path)
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ReadResult.Found>(store.readArchiveV3())
            assertIs<SessionReadResult.NeedsIntervention>(store.read(f.id(301)))
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
            assertEquals(f.session(),assertIs<SessionReadResult.Found>(store.read(f.id(1))).session)
            assertTrue(f.id(301) in assertIs<SessionDiscoveryResult.Found>(store.discover()).uuids)
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `V1 and V2 import previews refuse whole database when configured rows exist`() = runBlocking<Unit> {
        seed();d.futureMixed(path);val before=d.contents(path)
        openSessionStore(path).use { store ->
            assertIs<ArchiveImportPreviewResult.NeedsIntervention>(store.previewImport(f.archive(f.session())))
            assertIs<ArchiveV2PreviewResult.NeedsIntervention>(store.previewImportV2(v2(bows=listOf(f.bow(802)))))
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `previously valid V1 V2 confirmations are blocked under writer lock after configured content appears`() = runBlocking<Unit> {
        seed()
        openSessionStore(path).use { store ->
            val v1=assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(f.archive(f.session()))).preview
            val v2=store.previewV2(v2(bows=listOf(f.bow(802))))
            d.futureMixed(path);val before=d.contents(path)
            assertIs<ArchiveImportResult.NeedsIntervention>(store.importArchive(v1))
            assertIs<ArchiveV2ImportResult.NeedsIntervention>(store.importArchiveV2(v2))
            assertEquals(before,d.contents(path))
        }
    }
    @Test fun `legacy create idempotence cannot accept a configured UUID as an identical homogeneous session`() = runBlocking<Unit> {
        val source=d.source();seed();d.futureMixed(path);val before=d.contents(path)
        val old=source.sessions.single { it.uuid==f.id(301) }
        val archive=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            source.profiles.filter { it.definition==old.targetType },listOf(old)))).archive
        openSessionStore(path).use { assertIs<SessionCreateResult.NeedsIntervention>(it.create(archive)) }
        assertEquals(before,d.contents(path))
    }
    @Test fun `legacy optimistic update cannot replace configured captures or decisions`() = runBlocking<Unit> {
        val source=d.source();seed();d.futureMixed(path);val before=d.contents(path)
        val old=source.sessions.single { it.uuid==f.id(301) }
        fun archive(s: Session)=assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            source.profiles.filter { it.definition==s.targetType },listOf(s)))).archive
        openSessionStore(path).use { store ->
            assertIs<SessionUpdateResult.NeedsIntervention>(store.update(archive(old),archive(old.copy(title="attempt",updatedAt=old.updatedAt.plusSeconds(1)))))
        }
        assertEquals(before,d.contents(path))
    }
    @Test fun `homogeneous configured marker is also rejected because legacy formats would lose capture origin`() = runBlocking<Unit> {
        seed()
        d.raw(path,"UPDATE session_configurations SET representation='CONFIGURED' WHERE sessionUuid='${f.id(1)}'",
            "UPDATE sessions SET archiveSchemaVersion=3 WHERE uuid='${f.id(1)}'")
        openSessionStore(path).use { store ->
            assertIs<ArchiveV3ReadResult.Found>(store.readArchiveV3())
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive());assertIs<ArchiveV2ReadResult.NeedsIntervention>(store.readArchiveV2())
        }
    }
    @Test fun `legacy metadata and opaque references preserve derived captures without extra UPDATE`() = runBlocking<Unit> {
        seed();val captureBefore=f.contents(path,listOf("session_configurations","end_configurations"))
        f.denyWrites(path,listOf("session_configurations","end_configurations"))
        openSessionStore(path).use { store ->
            val old=f.session();val proposed=old.withEquipmentReferences(f.id(5555),null,f.now.plusSeconds(30))
            assertIs<SessionUpdateResult.Updated>(store.update(f.archive(old),f.archive(proposed)))
            val archive=store.fullV3();assertEquals(f.id(5555),archive.sessions.first().metadata.bowUuid)
            assertEquals(d.configured(store.fullV2()),archive)
        }
        assertEquals(captureBefore,f.contents(path,listOf("session_configurations","end_configurations")))
    }
    @Test fun `preexisting legacy global configuration revision remains homogeneous and explicitly derived`() = runBlocking<Unit> {
        seed()
        val old=f.session();val proposed=old.copy(distanceM=70,arrowCountMode=ArrowCountMode.FIXED,arrowCountPerEnd=5,
            scoringMode=ScoringMode.MANUAL_CORDON,isIndoor=false,updatedAt=f.now.plusSeconds(30))
        openSessionStore(path).use { store ->
            assertIs<SessionUpdateResult.Updated>(store.update(f.archive(old),f.archive(proposed)))
            val current=store.fullV3().sessions.first()
            assertEquals(70,current.initialConfiguration.distanceM);assertEquals(5,current.initialConfiguration.arrowCountPerEnd)
            assertTrue(current.ends.all { it.configuration==current.initialConfiguration })
            assertEquals(old.ends,current.ends.map { it.end }) // No historical score/decision/trace rewrite.
            assertEquals(d.configured(store.fullV2()),store.fullV3())
        }
    }
    @Test fun `capture insertion failure rolls back complete historical create and identities`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            store.fullV3();val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fail_capture BEFORE INSERT ON end_configurations BEGIN SELECT RAISE(ABORT,'synthetic capture failure'); END")
            assertIs<SessionCreateResult.TechnicalFailure>(store.create(f.archive(f.session())))
            assertEquals(before,d.contents(path));assertIs<SessionReadResult.NotFound>(store.read(f.id(1)))
        }
    }
    @Test fun `capture insertion failure on appended end rolls back legacy optimistic revision`() = runBlocking<Unit> {
        seed()
        openSessionStore(path).use { store ->
            val old=f.session();val at=f.now.plusSeconds(30)
            val next=old.withEnd(End(f.id(999),2,EndStatus.EDITING,createdAt=at,updatedAt=at),at)
            val before=d.contents(path)
            f.rows(path,"CREATE TRIGGER fail_capture BEFORE INSERT ON end_configurations BEGIN SELECT RAISE(ABORT,'synthetic capture failure'); END")
            assertIs<SessionUpdateResult.TechnicalFailure>(store.update(f.archive(old),f.archive(next)))
            assertEquals(before,d.contents(path));assertEquals(old,assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
        }
    }
    @Test fun `new foreign keys keep old roots profiles and predecessors protected`() = runBlocking<Unit> {
        seed();val before=d.contents(path)
        for (sql in listOf("DELETE FROM sessions WHERE uuid='${f.id(301)}'", "DELETE FROM ends WHERE uuid='${f.id(302)}'",
            "DELETE FROM session_configurations WHERE sessionUuid='${f.id(301)}'",
            "UPDATE end_configurations SET previousEndUuid='${f.id(999)}' WHERE endUuid='${f.id(303)}'"))
            assertFailsWith<Exception> { f.rows(path,sql) }
        assertEquals(before,d.contents(path));assertTrue(f.rows(path,"PRAGMA foreign_key_check").isEmpty())
    }
}
