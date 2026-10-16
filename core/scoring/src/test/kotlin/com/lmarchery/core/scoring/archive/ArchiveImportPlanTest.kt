package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.*
import kotlin.test.*

class ArchiveImportPlanTest {
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private val target = ClassicTargetFixtures.classic40
    private fun id(n: Int) = "40000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private fun seed(n: Int): Session {
        val a = ArrowImpact(id(n*100+1),1,NormalizedPoint(.105f,-0.0f),inputMode=InputMode.MANUAL,
            createdAt=now,updatedAt=now,targetType=target)
            .withFinalScore(target,ScoreDecisionRequest.JudgeBoundary(BoundaryReference(BoundaryType.SCORE_ZONES,.1f),BoundaryValue(9),now),now)
            .reposition(NormalizedPoint(.25f,0f),now.plusSeconds(1),"Synthetic move")
        val end=End(id(n*100+2),1,EndStatus.EDITING,listOf(a),createdAt=now,updatedAt=now.plusSeconds(1))
        return Session(id(n),SessionDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00"),ZoneId.of("Europe/Paris"),ZoneOffset.ofHours(2)),
            SessionStatus.DRAFT,title="Synthetic",targetType=target,distanceM=18,arrowCountMode=ArrowCountMode.FREE,
            isIndoor=true,scoringMode=ScoringMode.TRAINING,createdAt=now,updatedAt=now).start(now).withEnd(end)
    }
    private fun archive(vararg s: Session) = accepted(snapshotArchive(s.map { TargetProfileSnapshot(it.targetType,null) }.distinct(),s.toList()))
    private fun accepted(s: ArchiveSnapshot) = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(s)).archive

    @Test fun `new identical mixed and discovery order compare whole domain`() {
        val a=seed(1);val b=seed(2);val empty=archive()
        val fresh=planArchiveImport(archive(a,b),empty)
        assertTrue(fresh.canImport);assertEquals(listOf(a.uuid,b.uuid),fresh.newSessionUuids)
        val identical=planArchiveImport(archive(a,b),archive(b,a))
        assertFalse(identical.canImport);assertTrue(identical.conflicts.isEmpty());assertEquals(2,identical.identicalSessionUuids.size)
        val mixed=planArchiveImport(archive(b,a),archive(a))
        assertEquals(listOf(b.uuid),mixed.newSessionUuids);assertEquals(listOf(a.uuid),mixed.identicalSessionUuids)
        assertEquals(123456789, b.createdAt.nano);assertEquals(ZoneOffset.ofHours(2),b.date.offset)
    }
    @Test fun `title updatedAt point same-score provenance and removed history are divergences`() {
        val original=seed(1);val base=archive(original);val snap=base.toSnapshot();val s=snap.sessions.single()
        val e=s.ends.single();val a=e.impacts.single()
        val sameScore=a.copy(decision=a.decision!!.copy(kind=ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
            decidedAt=a.updatedAt,reason="Observed same score"))
        val noTrace=a.copy(repositions=emptyList())
        val changedHistory=a.copy(repositions=a.repositions.map { trace ->trace.copy(previousDecision=trace.previousDecision!!.let {
            it.copy(selected=BoundaryValue(10),assertions=it.assertions!!.copy(scoreFinal=10))
        }) })
        val variants=listOf(s.copy(title="Other"),s.copy(updatedAt=now.plusSeconds(2).toString()),
            s.copy(ends=listOf(e.copy(impacts=listOf(sameScore)))),s.copy(ends=listOf(e.copy(impacts=listOf(noTrace)))),
            s.copy(ends=listOf(e.copy(impacts=listOf(changedHistory)))))
        variants.forEach { changed ->
            val received=accepted(snap.copy(sessions=listOf(changed)))
            assertTrue(planArchiveImport(received,base).conflicts.isNotEmpty())
        }
        val manual=original.withEnd(original.ends.single().withImpact(original.ends.single().impacts.single().withFinalScore(
            target,ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(8),"First reason",now.plusSeconds(2)),now.plusSeconds(2))))
        val other=manual.withEnd(manual.ends.single().withImpact(manual.ends.single().impacts.single().withFinalScore(
            target,ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(8),"Different reason",now.plusSeconds(2)),now.plusSeconds(2))))
        assertEquals(manual.ends[0].impacts[0].scoreFinal,other.ends[0].impacts[0].scoreFinal)
        assertTrue(planArchiveImport(archive(other),archive(manual)).conflicts.isNotEmpty())
        // A changed point must have a consistent geometric decision to be independently valid.
        val pointSession=seed(2);val pointBase=archive(pointSession)
        val revised=pointSession.withEnd(pointSession.ends.single().withImpact(pointSession.ends.single().impacts.single()
            .reposition(NormalizedPoint(.35f,0f),now.plusSeconds(2)),now.plusSeconds(2)))
        assertFalse(planArchiveImport(archive(revised),pointBase).canImport)
        assertTrue(planArchiveImport(archive(revised),pointBase).conflicts.isNotEmpty())
    }
    @Test fun `descendant reused under another session or entity kind is a conflict`() {
        val old=seed(1);val incoming=seed(2)
        val s=archive(incoming).toSnapshot();val e=s.sessions.single().ends.single()
        val reused=s.copy(sessions=listOf(s.sessions.single().copy(ends=listOf(e.copy(uuid=old.ends.single().uuid)))))
        val plan=planArchiveImport(accepted(reused),archive(old))
        assertTrue(plan.conflicts.any { it.uuid==old.ends.single().uuid });assertTrue(plan.newSessionUuids.isEmpty())
        val wrongKind=s.copy(sessions=listOf(s.sessions.single().copy(uuid=old.ends.single().impacts.single().uuid)))
        assertTrue(planArchiveImport(accepted(wrongKind),archive(old)).conflicts.isNotEmpty())
        val profile=target.copy(uuid=old.ends.single().impacts.single().uuid,isBuiltIn=false)
        val changed=s.sessions.single().let { session ->session.copy(targetTypeUuid=profile.uuid,
            ends=session.ends.map { end ->end.copy(impacts=end.impacts.map { it.copy(targetTypeUuid=profile.uuid) }) }) }
        val profileCollision=planArchiveImport(accepted(s.copy(profiles=listOf(TargetProfileSnapshot(profile,null)),sessions=listOf(changed))),archive(old))
        assertTrue(profileCollision.conflicts.any { it.uuid==profile.uuid });assertTrue(profileCollision.newSessionUuids.isEmpty())
    }
    @Test fun `custom profiles retain UUID definition and edition while different edition conflicts`() {
        val custom=target.copy(uuid=id(90),isBuiltIn=false,name="Synthetic custom")
        fun customArchive(edition:String)=accepted(snapshotArchive(listOf(TargetProfileSnapshot(custom,edition)),listOf(
            Session(id(91),SessionDateTime.fromInstant(now,ZoneId.of("UTC")),SessionStatus.DRAFT,title="Custom",targetType=custom,
                distanceM=18,arrowCountMode=ArrowCountMode.FREE,isIndoor=true,scoringMode=ScoringMode.TRAINING,createdAt=now,updatedAt=now))))
        assertTrue(planArchiveImport(customArchive("edition1"),archive()).canImport)
        val divergent=planArchiveImport(customArchive("edition2"),customArchive("edition1"))
        assertTrue(divergent.conflicts.any { it.uuid==custom.uuid })
        assertTrue(divergent.identicalSessionUuids.isEmpty());assertTrue(divergent.newSessionUuids.isEmpty())
        val altered=archive(seed(1)).toSnapshot().let { it.copy(profiles=listOf(it.profiles.single().copy(definition=target.copy(name="Altered")))) }
        assertIs<ArchiveReconstruction.NeedsIntervention>(reconstructArchive(altered))
    }
    @Test fun `empty archives and unreferenced profiles are explicit refusals`() {
        assertEquals("EMPTY_ARCHIVE",planArchiveImport(archive(),archive()).conflicts.single().code)
        val unused=accepted(snapshotArchive(listOf(TargetProfileSnapshot(target,null)),emptyList()))
        assertTrue(planArchiveImport(unused,archive()).conflicts.any { it.code=="UNREFERENCED_PROFILE" })
        val completed=seed(1).let { it.withEnd(it.ends.single().validate(now.plusSeconds(2))).complete(now.plusSeconds(3)) }
        assertTrue(planArchiveImport(archive(completed),archive()).canImport)
    }
}
