package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as c
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.database.ArchiveV3DiskData as d
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomConfiguredUpdateTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("configured-update.db")
    private fun changed(s: ConfiguredSession, transform: (ConfiguredSessionSnapshot)->ConfiguredSessionSnapshot): ConfiguredSession {
        val snapshot=c.snapshot(s)
        return c.reconstructed(snapshot.copy(sessions=listOf(transform(snapshot.sessions!!.single()))))
    }
    @Test fun `start first end inherit and explicit append persist through expected proposed revisions`() = runBlocking<Unit> {
        var s=c.draft();openSessionStore(path).use { store ->
            store.createConfiguredSession(s)
            suspend fun save(next: ConfiguredSession) { assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));s=next;assertEquals(s,c.stored(store)) }
            save(s.start(f.now.plusSeconds(1)));save(s.createNextEnd(f.id(6001),f.now.plusSeconds(2)))
            save(s.createNextEnd(f.id(6002),f.now.plusSeconds(3)))
            save(s.createNextEnd(f.id(6003),f.now.plusSeconds(4),NextEndConfigurationChoice.Replace(c.compound)))
            save(s.createNextEnd(f.id(6004),f.now.plusSeconds(5)))
            assertEquals(listOf(EndConfigurationOrigin.Initial,EndConfigurationOrigin.Inherited(f.id(6001)),EndConfigurationOrigin.Explicit,
                EndConfigurationOrigin.Inherited(f.id(6003))),s.ends.map { it.origin })
        }
        openSessionStore(path).use { assertEquals(s,c.stored(it)) }
    }
    @Test fun `metadata only update never updates either capture including no value changes`() = runBlocking<Unit> {
        val s=c.rich();openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val captures=f.contents(path,listOf("session_configurations","end_configurations"))
            f.denyWrites(path,listOf("session_configurations","end_configurations","impacts","score_decisions","impact_repositions"))
            val next=s.withMetadata(s.metadata.copy(title="Modified",bowUuid=f.id(5555),notes=""),f.now.plusSeconds(20))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));assertEquals(next,c.stored(store))
            assertEquals(captures,f.contents(path,listOf("session_configurations","end_configurations")))
        }
    }
    @Test fun `legitimate judgment reposition and new judgment preserve all historical decisions`() = runBlocking<Unit> {
        var s=c.rich();val e=s.ends.single().uuid;val i=s.ends.single().end.impacts.first().uuid
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s)
            var next=s.decideImpact(e,i,ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(8),"Observed eight",f.now.plusSeconds(10)),f.now.plusSeconds(10))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));s=next
            next=s.repositionImpact(e,i,NormalizedPoint(.25f,0f),f.now.plusSeconds(11),"Explicit relocation")
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));s=next
            val boundary=c.classic.targetType.scoreBoundaries().first { it.reference.type==BoundaryType.SCORE_ZONES }
            next=s.decideImpact(e,i,ScoreDecisionRequest.JudgeBoundary(boundary.reference,boundary.inner,f.now.plusSeconds(12)),f.now.plusSeconds(12))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));s=next
            val stored=c.stored(store);assertEquals(s,stored)
            val a=stored.ends.single().end.impacts.first();assertEquals(3,a.repositions.size)
            assertEquals(8,a.repositions.last().previousDecision.scoreFinal);assertEquals(10,a.scoreFinal);assertEquals(8,a.scoreCalculated)
        }
    }
    @Test fun `X and M final indicators remain exact after explicit position transitions`() = runBlocking<Unit> {
        val s=c.rich();val e=s.ends.single();var next=s.repositionImpact(e.uuid,e.end!!.impacts[1].uuid,NormalizedPoint(.15f,0f),f.now.plusSeconds(10))
        next=next.repositionImpact(e.uuid,e.end!!.impacts[2].uuid,NormalizedPoint(0f,0f),f.now.plusSeconds(11))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next));assertEquals(next,c.stored(store))
            val arrows=c.stored(store).ends.single().end.impacts
            assertFalse(arrows[1].isX);assertEquals(9,arrows[1].scoreFinal);assertTrue(arrows[2].isX);assertFalse(arrows[2].isMiss)
        }
    }
    @Test fun `new impact and subsequent validation commit exact domain revisions`() = runBlocking<Unit> {
        val s=c.oneEnd();val e=s.ends.single().uuid;val next=s.withImpact(e,c.arrow(s,1,.15f))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,next))
            val validated=next.validateEnd(e,f.now.plusSeconds(13))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(next,validated));assertEquals(validated,c.stored(store))
            assertFailsWith<IllegalArgumentException> { validated.repositionImpact(e,next.ends.single().end.impacts.single().uuid,NormalizedPoint(.25f,0f),f.now.plusSeconds(14)) }
        }
    }
    @Test fun `renumbering an impact without changing its UUID is rejected`() = runBlocking<Unit> {
        val s=c.rich();val proposed=changed(s) { it.copy(ends=it.ends!!.map { captured ->
            val end=captured.end!!;captured.copy(end=end.copy(impacts=end.impacts.mapIndexed { index,a -> if(index==0) a.copy(index=99) else a })) }) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,proposed),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `validated end cannot change even when a self consistent independent snapshot exists`() = runBlocking<Unit> {
        val s=c.rich().validateEnd(f.id(6001),f.now.plusSeconds(8))
        val proposed=changed(s) { root -> root.copy(ends=root.ends!!.map { it.copy(end=it.end!!.copy(notes="Rewritten")) }) }
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,proposed),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `completion freezes metadata and descendants while exact no operation remains possible`() = runBlocking<Unit> {
        val s=c.rich();val completed=s.complete(f.now.plusSeconds(20))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(s,completed))
            val before=d.contents(path);f.denyWrites(path,d.tables)
            assertIs<ConfiguredWriteResult.AlreadyApplied>(store.updateConfiguredSession(completed,completed.copy()))
            val attempted=changed(completed) { it.copy(metadata=it.metadata!!.copy(title="Forbidden")) }
            c.conflict(store.updateConfiguredSession(completed,attempted),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `old complete expected revision cannot overwrite a newer one with equal timestamp`() = runBlocking<Unit> {
        val s=c.draft();val one=s.withMetadata(s.metadata.copy(title="One"),f.now.plusSeconds(1))
        val two=s.withMetadata(s.metadata.copy(title="Two"),f.now.plusSeconds(1))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);store.updateConfiguredSession(s,one);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,two),ConfiguredWriteConflict.REVISION);assertEquals(before,d.contents(path))
            val resolved=one.withMetadata(one.metadata.copy(title="Explicit resolution"),f.now.plusSeconds(2))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(one,resolved));assertEquals(resolved,c.stored(store))
        }
    }
    @Test fun `retry already committed proposed performs no DML with original expected preserved`() = runBlocking<Unit> {
        val s=c.rich();val next=s.withMetadata(s.metadata.copy(title="Applied"),f.now.plusSeconds(20))
        openSessionStore(path).use { store ->
            store.createConfiguredSession(s);store.updateConfiguredSession(s,next);f.denyWrites(path,d.tables);val before=d.contents(path)
            val result=assertIs<ConfiguredWriteResult.AlreadyApplied>(store.updateConfiguredSession(s,next))
            assertEquals(ConfiguredWriteRequest.Update(s,next),result.request);assertEquals(before,d.contents(path))
        }
    }
    @Test fun `missing expected session is distinct from stale revision`() = runBlocking<Unit> {
        val s=c.draft();openSessionStore(path).use { store -> c.conflict(store.updateConfiguredSession(s,s.start(f.now)),ConfiguredWriteConflict.NOT_FOUND) }
    }
    @Test fun `replacement of empty end configuration is refused rather than fallback to initial`() = runBlocking<Unit> {
        val s=c.oneEnd();val proposed=changed(s) { it.copy(ends=it.ends!!.map { end->end.copy(
            configuration=end.configuration!!.copy(distanceM=70),origin=EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT,null)) }) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,proposed),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `capture origin cannot change even when its configuration stays identical`() = runBlocking<Unit> {
        val s=c.oneEnd();val proposed=changed(s) { it.copy(ends=it.ends!!.map { end->end.copy(origin=EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT,null)) }) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,proposed),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `initial configuration and creation identity are immutable`() = runBlocking<Unit> {
        val s=c.draft();val a=changed(s) { it.copy(initialConfiguration=it.initialConfiguration!!.copy(distanceM=70)) }
        val b=changed(s) { it.copy(createdAt=f.now.minusSeconds(1).toString()) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            for(p in listOf(a,b)) c.conflict(store.updateConfiguredSession(s,p),ConfiguredWriteConflict.INVALID_TRANSITION)
            assertEquals(before,d.contents(path)) }
    }
    @Test fun `deletion of historical impact and renumbering are rejected by transition validator`() = runBlocking<Unit> {
        val s=c.rich()
        val deleted=changed(s) { it.copy(ends=it.ends!!.map { e->e.copy(end=e.end!!.copy(impacts=e.end!!.impacts.drop(1))) }) }
        val renumbered=changed(s) { it.copy(ends=it.ends!!.map { e->e.copy(end=e.end!!.copy(index=2)) }) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            for(p in listOf(deleted,renumbered)) c.conflict(store.updateConfiguredSession(s,p),ConfiguredWriteConflict.INVALID_TRANSITION)
            assertEquals(before,d.contents(path)) }
    }
    @Test fun `impact cannot be moved to another existing end`() = runBlocking<Unit> {
        var s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(2));s=s.withImpact(f.id(6020),c.arrow(s,1,.15f))
        val moved=changed(s) { root ->
            val ends=root.ends!!;val impact=ends[1].end!!.impacts.single()
            root.copy(ends=listOf(ends[0].copy(end=ends[0].end!!.copy(impacts=listOf(impact),updatedAt=root.updatedAt)),
                ends[1].copy(end=ends[1].end!!.copy(impacts=emptyList())))) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,moved),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `reposition trace and manual provenance cannot silently disappear`() = runBlocking<Unit> {
        val s=c.rich();val root=c.snapshot(s);val e=root.sessions!!.single().ends!!.single();val impact=e.end!!.impacts.first()
        val arrow=s.ends.single().end.impacts.first()
        val fresh=ArrowImpact(arrow.uuid,arrow.index,arrow.point,inputMode=arrow.inputMode,createdAt=arrow.createdAt,
            updatedAt=arrow.updatedAt,targetType=arrow.targetType)
        val freshSession=c.oneEnd().withImpact(f.id(6001),fresh)
        val freshSnapshot=c.snapshot(freshSession).sessions!!.single().ends!!.single().end!!.impacts.single()
        val tampered=changed(s) { it.copy(ends=listOf(e.copy(end=e.end!!.copy(impacts=listOf(freshSnapshot)+e.end!!.impacts.drop(1))))) }
        assertTrue(impact.repositions.isNotEmpty())
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,tampered),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `backdated revision is rejected without any SQL writes`() = runBlocking<Unit> {
        val s=c.rich();val proposed=changed(s) { it.copy(updatedAt=f.now.plusSeconds(6).toString(),ends=emptyList()) }
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            c.conflict(store.updateConfiguredSession(s,proposed),ConfiguredWriteConflict.INVALID_TRANSITION);assertEquals(before,d.contents(path)) }
    }
    @Test fun `wrong ownership and contradictory inheritance never become writable aggregates`() = runBlocking<Unit> {
        val s=c.oneEnd().createNextEnd(f.id(6020),f.now.plusSeconds(2));val snapshot=c.snapshot(s);val root=snapshot.sessions!!.single()
        val badOwner=root.copy(ends=root.ends!!.map { it.copy(sessionUuid=f.id(7777)) })
        val badInheritance=root.copy(ends=root.ends!!.map { if(it.end!!.uuid==f.id(6020)) it.copy(
            origin=EndConfigurationOriginSnapshot(EndConfigurationOriginKind.INHERITED,f.id(7777))) else it })
        openSessionStore(path).use { store -> store.createConfiguredSession(s);val before=d.contents(path)
            for(p in listOf(badOwner,badInheritance)) assertIs<ArchiveV3Reconstruction.NeedsIntervention>(reconstructArchiveV3(snapshot.copy(sessions=listOf(p))))
            assertEquals(before,d.contents(path)) }
    }
}
