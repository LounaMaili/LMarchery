package com.lmarchery.feature.sessions

import com.lmarchery.core.export.ArchiveJsonCodec
import com.lmarchery.core.export.ArchiveJsonRead
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.*
import java.util.UUID
import kotlin.test.*

class SessionPresentationTest {
    private val now = Instant.parse("2026-10-16T10:00:00Z")
    private val wa = IndoorProfile.RECURVE_40.target
    private fun id() = UUID.randomUUID().toString()
    private fun session(date: SessionDateTime = SessionDateTime.fromInstant(now, ZoneId.of("UTC")),
        uuid: String = id(), target: TargetType = wa): Session = Session(uuid, date, SessionStatus.DRAFT,
        title = "Synthetic presentation", targetType = target, distanceM = 18, arrowCountMode = ArrowCountMode.FIXED,
        arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING, createdAt = now, updatedAt = now)
    private fun arrow(radius: Float, index: Int, target: TargetType = wa) =
        scoreArrowImpact(id(), index, target, NormalizedPoint(radius, 0f), ScoringMode.TRAINING, createdAt = now)
    private fun end(impacts: List<ArrowImpact> = emptyList(), index: Int = 1) = End(id(), index,
        EndStatus.EDITING, impacts, createdAt = now, updatedAt = impacts.maxOfOrNull { it.updatedAt } ?: now)
    private fun history(vararg sessions: Session) = presentSessionHistory(sessions.map { SessionEntry(it.uuid, it) })

    @Test fun `history sorts by session instant descending rather than revision or discovery`() {
        val older = session().copy(updatedAt = now.plusSeconds(500))
        val newer = session(SessionDateTime.fromInstant(now.plusSeconds(100), ZoneId.of("UTC")))
        assertEquals(listOf(newer, older), history(older, newer).resumable.map { it.session })
    }
    @Test fun `identical civil dates with distinct offsets sort by real instant`() {
        val civil = LocalDateTime.parse("2026-10-16T12:00:00")
        val paris = session(SessionDateTime.of(civil, ZoneId.of("Europe/Paris")))
        val utc = session(SessionDateTime.of(civil, ZoneId.of("UTC")))
        assertEquals(listOf(utc, paris), history(paris, utc).resumable.map { it.session })
        assertTrue(paris.civilDateLabel().contains("12:00"))
        assertTrue(paris.civilDateLabel().contains("Europe/Paris (+02:00)"))
    }
    @Test fun `repeated winter hour keeps explicitly selected offset`() {
        val local = LocalDateTime.parse("2026-10-25T02:30:00")
        val early = session(SessionDateTime.of(local, ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(2)))
        val late = session(SessionDateTime.of(local, ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(1)))
        assertEquals(listOf(late, early), history(early, late).resumable.map { it.session })
        assertNotEquals(early.civilDateLabel(), late.civilDateLabel())
    }
    @Test fun `equal instants use ascending UUID independent of local time`() {
        val first = session(uuid = "00000000-0000-4000-8000-000000000001")
        val last = session(SessionDateTime.fromInstant(now, ZoneId.of("Asia/Tokyo")), "00000000-0000-4000-8000-000000000002")
        assertEquals(listOf(first.uuid, last.uuid), history(last, first).resumable.map { it.uuid })
    }
    @Test fun `statuses partition and corrupt entries including contradictory entries stay visible`() {
        val draft = session(); val active = session().start(now); val completed = session().start(now).complete(now)
        val broken = SessionEntry(id(), problem = UiProblem(ProblemKind.INTERVENTION, "Synthetic corruption"))
        val inconsistent = SessionEntry(id(), draft, UiProblem(ProblemKind.TECHNICAL, "Read failed"))
        val result = presentSessionHistory(listOf(broken, SessionEntry(completed.uuid, completed),
            SessionEntry(active.uuid, active), inconsistent, SessionEntry(draft.uuid, draft)))
        assertEquals(setOf(draft, active), result.resumable.map { it.session }.toSet())
        assertEquals(listOf(completed), result.completed.map { it.session })
        assertEquals(setOf(broken, inconsistent), result.intervention.toSet())
        assertEquals("Reprendre", active.openActionLabel()); assertEquals("Consulter", completed.openActionLabel())
    }
    @Test fun `empty history and absent averages are explicit`() {
        assertEquals(SessionHistory(emptyList(), emptyList(), emptyList()), presentSessionHistory(emptyList()))
        val view = presentSessionScore(session())
        assertNull(view.summary.averagePerArrow); assertNull(view.summary.averagePerEnd)
        assertEquals("Indisponible · aucune donnée validée", view.averagePerArrowLabel)
        assertEquals(view.averagePerArrowLabel, view.averagePerEndLabel)
    }
    @Test fun `unsupported valid configuration remains consultable`() {
        val s = session().copy(arrowCountPerEnd = 6)
        assertEquals("Consulter", s.openActionLabel())
        assertFalse(SessionUiState().copy(saved = archive(s)).supportedConfiguration)
    }
    @Test fun `empty end is explicitly empty with no displayed zero`() {
        val row = presentEndScore(end())
        assertNull(row.total); assertEquals("Vide · aucune flèche", row.arrowsLabel)
        assertTrue(row.totalLabel.contains("—")); assertTrue(row.stateLabel.contains("Provisoire"))
    }
    @Test fun `partial and full editing ends stay provisional and use stored arrow order`() {
        val arrows = listOf(arrow(1.1f, 3), arrow(.15f, 1), arrow(.075f, 2))
        val partial = presentEndScore(end(arrows.take(2)))
        assertEquals(listOf("#3 : M", "#1 : 9"), partial.arrows)
        val full = end(arrows); val row = presentEndScore(full)
        assertEquals(EndStatus.EDITING, row.status)
        assertEquals(calculateEndScore(full), row.total)
        assertEquals(listOf("#3 : M", "#1 : 9", "#2 : 10"), row.arrows)
        assertEquals(0, presentSessionScore(session().start(now).withEnd(full)).summary.totalScore)
    }
    @Test fun `validated ends contribute exactly the domain totals and counters`() {
        val target = ClassicTargetFixtures.classic40
        val validated = end(listOf(arrow(.025f, 1, target), arrow(.15f, 2, target), arrow(1.1f, 3, target))).validate(now)
        val s = session(target = target).start(now).withEnd(validated)
        val view = presentSessionScore(s)
        assertEquals(summarizeSessionScore(s), view.summary)
        assertEquals(listOf("#1 : 10X", "#2 : 9", "#3 : M"), presentEndScore(validated).arrows)
        assertEquals(listOf(19, 1, 3, 1, 1, 1, 1), with(view.summary) {
            listOf(totalScore, validatedEndCount, validatedImpactCount, maxScoreCount, nineCount, xCount, missCount)
        })
        assertTrue(view.showX); assertEquals("6,33", view.averagePerArrowLabel); assertEquals("19,00", view.averagePerEndLabel)
        assertFalse(presentSessionScore(session()).showX)
    }
    @Test fun `completed session preserves empty provisional end and explains recorded maximum`() {
        val s = session().start(now).withEnd(end(listOf(arrow(0f, 1))).validate(now))
            .withEnd(end(index = 2)).complete(now)
        val view = presentSessionScore(s)
        assertEquals(summarizeSessionScore(s), view.summary)
        assertEquals(10, view.summary.totalScore); assertEquals(60, view.summary.maxPossibleScore)
        assertTrue(view.maximumLabel.contains("2 volées enregistrées")); assertTrue(view.heading.contains("terminée"))
        assertNull(presentEndScore(s.ends.last()).total)
    }
    @Test fun `planned and free maximum labels match domain denominator semantics`() {
        assertTrue(presentSessionScore(session().copy(plannedEndCount = 20)).maximumLabel.contains("20 volées prévues"))
        assertTrue(presentSessionScore(session().copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null))
            .maximumLabel.contains("flèches enregistrées"))
    }
    @Test fun `manual final score is displayed instead of geometric score`() {
        val impact = arrow(.15f, 1).withFinalScore(wa,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10, false), "Synthetic correction", now), now)
        assertEquals(9, impact.scoreCalculated)
        val e = end(listOf(impact)).validate(now)
        val s = session().start(now).withEnd(e)
        assertEquals(listOf("#1 : 10"), presentEndScore(e).arrows)
        assertEquals(summarizeSessionScore(s), presentSessionScore(s).summary)
        assertEquals(10, presentSessionScore(s).summary.totalScore)
    }
    @Test fun `reposition keeps UUID and presentation reflects new final value without mutating old`() {
        val old = arrow(.15f, 1); val moved = old.reposition(NormalizedPoint(.25f, 0f), now)
        assertEquals(old.uuid, moved.uuid)
        assertEquals(listOf("#1 : 9"), presentEndScore(end(listOf(old))).arrows)
        val e = end(listOf(moved)).validate(now); val s = session().start(now).withEnd(e)
        assertEquals(listOf("#1 : 8"), presentEndScore(e).arrows)
        assertEquals(summarizeSessionScore(s), presentSessionScore(s).summary)
        assertEquals(8, presentSessionScore(s).summary.totalScore)
    }
    @Test fun `imported archive produces identical rows summaries and session identity`() {
        val s = session().start(now).withEnd(end(listOf(arrow(.075f, 1), arrow(.15f, 2), arrow(1.1f, 3))).validate(now)).complete(now)
        val codec = ArchiveJsonCodec(); val source = archive(s)
        val imported = assertIs<ArchiveJsonRead.Accepted>(codec.decode(codec.encode(source))).archive.sessions.single()
        assertEquals(s.uuid, imported.uuid)
        assertEquals(presentSessionScore(s), presentSessionScore(imported))
        assertEquals(s.ends.map(::presentEndScore), imported.ends.map(::presentEndScore))
    }
    private fun archive(s: Session): DomainArchive {
        val p = BuiltInTargetRegistry.find(s.targetType.uuid)!!
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(p.definition, p.regulatoryEdition)), listOf(s)))).archive
    }
}
