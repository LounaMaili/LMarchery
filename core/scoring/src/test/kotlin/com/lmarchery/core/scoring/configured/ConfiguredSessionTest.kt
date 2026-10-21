package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.scoring.scoreBoundaries
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.calculateEndScore
import com.lmarchery.core.scoring.calculateSessionScore
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.mixed.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.core.scoring.summarizeSessionScore
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ConfiguredSessionTest {
    private val now = Instant.parse("2026-10-20T10:00:00.123456789Z")
    private fun time(n: Long) = now.plusSeconds(n)
    private fun id(n: Int) = "b0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun config(compound: Boolean = false, distance: Int = 18, count: Int? = 3) = EndConfiguration(
        if (compound) WA.compound40 else WA.recurve40, WA.RULEBOOK_VERSION, distance,
        if (count == null) ArrowCountMode.FREE else ArrowCountMode.FIXED, count, true, ScoringMode.TRAINING)
    private fun metadata() = ConfiguredSessionMetadata(SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")),
        "  Séance synthétique 🏹  ", 12, id(800), id(801), "", " Notes conservées ")
    private fun draft(c: EndConfiguration = config(), uuid: String = id(1)) = ConfiguredSession.create(uuid, metadata(), c, now)
    private fun active(c: EndConfiguration = config()) = draft(c).start(now).createNextEnd(id(10), now)
    private fun arrow(n: Int = 100, index: Int = 1, c: EndConfiguration = config(), r: Float = .075f,
        at: Instant = now) = scoreArrowImpact(id(n), index, c.targetType, NormalizedPoint(r, -0.0f), c.scoringMode,
        createdAt = at, inputMode = InputMode.MANUAL, note = "")
    private fun legacy(c: EndConfiguration = config(), ends: List<End> = emptyList(), status: SessionStatus = SessionStatus.IN_PROGRESS,
        updated: Instant = ends.maxOfOrNull { it.updatedAt } ?: now): Session = Session(
        id(1), metadata().date, status, startedAt = if (status == SessionStatus.DRAFT) null else now,
        endedAt = if (status == SessionStatus.COMPLETED) updated else null, plannedEndCount = 12, title = metadata().title,
        targetType = c.targetType, distanceM = c.distanceM, arrowCountMode = c.arrowCountMode, arrowCountPerEnd = c.arrowCountPerEnd,
        isIndoor = c.isIndoor, scoringMode = c.scoringMode, bowUuid = metadata().bowUuid, arrowSetUuid = metadata().arrowSetUuid,
        locationName = metadata().locationName, notes = metadata().notes, ends = ends, createdAt = now, updatedAt = updated)
    private fun archive(s: Session, edition: String? = BuiltInTargetRegistry.find(s.targetType.uuid)?.regulatoryEdition): DomainArchive =
        assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(s.targetType, edition)), listOf(s)))).archive
    private fun issue(code: ConfiguredSessionIssue, operation: () -> Unit): ConfiguredSessionException =
        assertFailsWith<ConfiguredSessionException>(block = operation).also { assertEquals(code, it.code); assertTrue(it.path.isNotBlank()) }
    private fun endWith(impact: ArrowImpact, uuid: String = id(10), index: Int = 1, status: EndStatus = EndStatus.EDITING): End =
        End(uuid, index, EndStatus.EDITING, listOf(impact), notes = "", createdAt = now, updatedAt = impact.updatedAt)
            .let { if (status == EndStatus.VALIDATED) it.validate(it.updatedAt) else it }

    @Test fun `creation is a draft with explicit initial value metadata and no invented end or event`() {
        val c = config(); val s = draft(c)
        assertEquals(id(1), s.uuid); assertEquals(metadata(), s.metadata); assertSame(c, s.initialConfiguration)
        assertEquals(InitialConfigurationOrigin.CREATION, s.initialConfigurationOrigin)
        assertEquals(SessionStatus.DRAFT, s.status); assertNull(s.startedAt); assertNull(s.endedAt); assertTrue(s.ends.isEmpty())
        assertEquals(now, s.createdAt); assertEquals(now, s.updatedAt)
        assertEquals(0L, summarizeConfiguredSession(s).totals.recorded.score)
    }
    @Test fun `session identity must be nonblank canonical UUID v4`() {
        for (bad in listOf("", " ", "not-a-uuid", "B0000000-0000-4000-8000-000000000001", "b0000000-0000-1000-8000-000000000001"))
            issue(ConfiguredSessionIssue.INVALID_IDENTITY) { draft(uuid = bad) }
    }
    @Test fun `metadata is validated and preserves strings opaque equipment references and civil time`() {
        val m = metadata().copy(bowUuid = "opaque legacy bow", arrowSetUuid = "opaque legacy arrows", locationName = "", notes = "")
        val s = draft().withMetadata(m, time(1))
        assertEquals(m, s.metadata); assertEquals("", s.metadata.locationName); assertEquals(metadata().date, s.metadata.date)
        assertFailsWith<IllegalArgumentException> { m.copy(title = " \n ") }
        assertFailsWith<IllegalArgumentException> { m.copy(plannedEndCount = 0) }
        assertFailsWith<IllegalArgumentException> { m.copy(bowUuid = " ") }
        assertFailsWith<IllegalArgumentException> { m.copy(arrowSetUuid = "") }
    }
    @Test fun `start is explicit monotone and can use the same instant`() {
        val d = draft(); val started = d.start(now)
        assertEquals(SessionStatus.IN_PROGRESS, started.status); assertEquals(now, started.startedAt)
        assertEquals(SessionStatus.DRAFT, d.status); assertEquals(d.uuid, started.uuid); assertEquals(d.createdAt, started.createdAt)
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { started.start(time(1)) }
    }
    @Test fun `start cannot be backdated after metadata modification`() {
        val d = draft().withMetadata(metadata().copy(notes = "change"), time(10))
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { d.start(time(5)) }
    }
    @Test fun `draft cannot create end add impact validate or complete`() {
        val s = draft()
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.createNextEnd(id(10), now) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.withImpact(id(10), arrow()) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.validateEnd(id(10), now) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.complete(now) }
    }
    @Test fun `first empty end captures initial configuration without new configuration UUID`() {
        val s = active(); val captured = s.ends.single()
        assertEquals(id(10), captured.uuid); assertEquals(1, captured.end.index); assertEquals(s.uuid, captured.sessionUuid)
        assertSame(s.initialConfiguration, captured.configuration); assertEquals(EndConfigurationOrigin.Initial, captured.origin)
        assertTrue(captured.end.impacts.isEmpty()); assertEquals(EndStatus.EDITING, captured.end.status)
    }
    @Test fun `first end may choose explicitly even when equal to initial`() {
        val d = draft().start(now)
        for (c in listOf(config(), config(true, 25, 6))) {
            val s = d.createNextEnd(id(10), now, NextEndConfigurationChoice.Replace(c))
            assertEquals(c, s.ends.single().configuration); assertEquals(EndConfigurationOrigin.Explicit, s.ends.single().origin)
        }
    }
    @Test fun `inheritance across empty ends never revises previous captures`() {
        val first = active(); val before = first.copy()
        val second = first.createNextEnd(id(11), time(1))
        val third = second.createNextEnd(id(12), time(2))
        assertEquals(before, first); assertSame(first.ends[0], third.ends[0]); assertSame(second.ends[1], third.ends[1])
        assertEquals(listOf(1, 2, 3), third.ends.map { it.end.index })
        assertEquals(EndConfigurationOrigin.Inherited(id(10)), second.ends[1].origin)
        assertEquals(EndConfigurationOrigin.Inherited(id(11)), third.ends[2].origin)
        assertTrue(third.ends.all { it.configuration == config() })
    }
    @Test fun `explicit replacement affects only next end then becomes inherited configuration`() {
        val original = active(); val selected = config(true, 25, null)
        val changed = original.createNextEnd(id(11), time(1), NextEndConfigurationChoice.Replace(selected))
        val inherited = changed.createNextEnd(id(12), time(2))
        assertEquals(config(), inherited.initialConfiguration); assertSame(original.ends[0], inherited.ends[0])
        assertEquals(EndConfigurationOrigin.Explicit, inherited.ends[1].origin)
        assertEquals(EndConfigurationOrigin.Inherited(id(11)), inherited.ends[2].origin)
        assertSame(selected, inherited.ends[2].configuration); assertSame(selected, inherited.ends[1].configuration)
    }
    @Test fun `capturing next end cannot be backdated`() {
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { active().createNextEnd(id(11), now.minusNanos(1)) }
    }
    @Test fun `missing repeated or cross type end UUID is rejected without changing original`() {
        val s = active()
        for (uuid in listOf("", "bad")) issue(ConfiguredSessionIssue.INVALID_IDENTITY) { s.createNextEnd(uuid, now) }
        for (uuid in listOf(s.uuid, id(10), s.initialConfiguration.targetType.uuid))
            issue(ConfiguredSessionIssue.IDENTITY_COLLISION) { s.createNextEnd(uuid, now) }
        assertEquals(1, s.ends.size)
    }
    @Test fun `identity collisions include session profile impact and ends across all configurations`() {
        issue(ConfiguredSessionIssue.IDENTITY_COLLISION) { draft(uuid = config().targetType.uuid) }
        val s = active().withImpact(id(10), arrow())
        issue(ConfiguredSessionIssue.IDENTITY_COLLISION) { s.createNextEnd(id(100), now) }
        for (uuid in listOf(s.uuid, id(10), config().targetType.uuid))
            issue(ConfiguredSessionIssue.IDENTITY_COLLISION) { active().withImpact(id(10), arrow().copy(uuid = uuid)) }
        issue(ConfiguredSessionIssue.INVALID_IDENTITY) { active().withImpact(id(10), arrow().copy(uuid = "invalid")) }
    }
    @Test fun `known profile and edition must be exact even in initial configuration or empty end`() {
        for (bad in listOf(config().copy(regulatoryEdition = null), config().copy(regulatoryEdition = "different"),
            config().copy(targetType = WA.recurve40.copy(name = "forged")), config().copy(targetType = WA.recurve40.copy(isBuiltIn = false)))) {
            issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { draft(bad) }
            issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { active().createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(bad)) }
        }
    }
    @Test fun `one custom UUID cannot have contradictory definitions or editions across initial and effective configurations`() {
        val target = WA.recurve40.copy(uuid = id(900), isBuiltIn = false)
        val c = config().copy(targetType = target, regulatoryEdition = "edition original")
        val s = active(c)
        for (next in listOf(c.copy(regulatoryEdition = "edition later"), c.copy(targetType = target.copy(name = "different"))))
            issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { s.createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(next)) }
    }
    @Test fun `unknown built in profile cannot enter aggregate`() {
        issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { draft(config().copy(targetType = WA.recurve40.copy(uuid = id(900)))) }
    }
    @Test fun `different profile identities and editions with same diameter remain distinct`() {
        val c = config().copy(targetType = WA.recurve40.copy(uuid = id(900), isBuiltIn = false), regulatoryEdition = "edition A")
        val next = c.copy(targetType = c.targetType.copy(uuid = id(901)), regulatoryEdition = "edition B")
        val s = active(c).createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(next))
        assertEquals(listOf(c, next), summarizeConfiguredSession(s).groups.map { it.configuration })
    }
    @Test fun `impact always belongs to effective profile rather than initial profile`() {
        val c = config(true, 25, 6)
        val s = active().createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(c))
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(11), arrow()) }
        val next = s.withImpact(id(11), arrow(c = c))
        assertEquals(9, next.ends[1].end.impacts.single().scoreFinal)
        assertEquals(c, configuredEndScoringContext(next, next.ends[1]).configuration)
    }
    @Test fun `unknown end and wrong impact owner fail without changing session`() {
        val s = active().withImpact(id(10), arrow()).createNextEnd(id(11), now)
        issue(ConfiguredSessionIssue.UNKNOWN_END) { s.withImpact(id(999), arrow()) }
        issue(ConfiguredSessionIssue.UNKNOWN_IMPACT) { s.repositionImpact(id(11), id(100), NormalizedPoint(.25f, 0f), now) }
        issue(ConfiguredSessionIssue.UNKNOWN_IMPACT) { s.decideImpact(id(11), id(100), ScoreDecisionRequest.AcceptGeometry, now) }
        issue(ConfiguredSessionIssue.UNKNOWN_END) { s.withEnd(End(id(999), 3, EndStatus.EDITING, createdAt = now, updatedAt = now)) }
    }
    @Test fun `scoring adapter rejects foreign owner wrong capture or stale end revision`() {
        val s = active(); val other = ConfiguredSession.create(id(2), metadata(), config(), now).start(now).createNextEnd(id(10), now)
        issue(ConfiguredSessionIssue.WRONG_OWNER) { configuredEndScoringContext(s, other.ends.single()) }
        val changed = s.withImpact(id(10), arrow())
        issue(ConfiguredSessionIssue.WRONG_OWNER) { configuredEndScoringContext(changed, s.ends.single()) }
        issue(ConfiguredSessionIssue.WRONG_OWNER) { configuredEndScoringContext(s,
            ConfiguredEnd(s.uuid, s.ends.single().end, config(true), EndConfigurationOrigin.Initial)) }
    }
    @Test fun `ordinary integration preserves older parent modification without inventing event time`() {
        val s = active().withMetadata(metadata().copy(notes = "latest parent"), time(10))
        val next = s.withImpact(id(10), arrow(at = time(5)))
        assertEquals(time(10), next.updatedAt); assertEquals(time(5), next.ends.single().end.updatedAt)
        assertEquals(now, next.createdAt); assertEquals(now, next.ends.single().end.createdAt)
    }
    @Test fun `integration timestamp must cover child and parent creation`() {
        val s = active(); val a = arrow(at = time(10))
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), a, time(5)) }
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { s.withEnd(endWith(a), time(5)) }
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), arrow(at = now.minusSeconds(1))) }
    }
    @Test fun `ten ten latest revision followed by ten five validation is rejected`() {
        val s = active().withImpact(id(10), arrow(at = time(600)))
        assertFailsWith<IllegalArgumentException> { s.validateEnd(id(10), time(300)) }
        assertEquals(EndStatus.EDITING, s.ends.single().end.status)
    }
    @Test fun `validation keeps historical empty underfilled full overfilled and free semantics`() {
        for (count in listOf(3, 6, 7, null)) for (actual in listOf(0, 1, 3, 8)) {
            val c = config(count = count)
            var s = active(c)
            repeat(actual) { s = s.withImpact(id(10), arrow(100 + it, it + 1, c)) }
            val validated = s.validateEnd(id(10), now)
            assertEquals(EndStatus.VALIDATED, validated.ends.single().end.status)
            assertEquals(s.ends.single().end.validate(now), validated.ends.single().end)
            assertEquals(actual.toLong(), summarizeConfiguredSession(validated).totals.validated.impactCount)
        }
    }
    @Test fun `validated end cannot receive correction reposition metadata or a new impact`() {
        val s = active().withImpact(id(10), arrow()).validateEnd(id(10), now)
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.withImpact(id(10), arrow(101, 2)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(1)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.decideImpact(id(10), id(100), ScoreDecisionRequest.AcceptGeometry, time(1)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { s.withEnd(s.ends.single().end.copy(notes = "change", updatedAt = time(1))) }
        assertEquals(s, s.withEnd(s.ends.single().end))
    }
    @Test fun `closure freezes provisional and validated ends without reclassifying scores`() {
        val s = active().withImpact(id(10), arrow()).validateEnd(id(10), now)
            .createNextEnd(id(11), time(1)).withImpact(id(11), arrow(101, at = time(1)))
        val closed = s.complete(time(2))
        assertEquals(SessionStatus.COMPLETED, closed.status); assertEquals(time(2), closed.endedAt)
        assertEquals(s.ends, closed.ends); assertEquals(summarizeConfiguredSession(s), summarizeConfiguredSession(closed))
        assertEquals(10L, summarizeConfiguredSession(closed).totals.provisional.score)
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.withImpact(id(11), arrow(102, 2, at = time(2))) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.createNextEnd(id(12), time(2)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.withMetadata(metadata(), time(2)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.validateEnd(id(11), time(2)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.start(time(2)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { closed.complete(time(2)) }
    }
    @Test fun `empty active session may close and later operations remain forbidden`() {
        val s = draft().start(now).complete(now)
        assertTrue(s.ends.isEmpty()); assertEquals(SessionStatus.COMPLETED, s.status)
        assertNull(summarizeConfiguredSession(s).totals.recorded.percentageOfRecordedCapacity)
    }
    @Test fun `closure and metadata changes cannot predate latest aggregate revision`() {
        val s = active().withImpact(id(10), arrow(at = time(10)))
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { s.complete(time(5)) }
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { s.withMetadata(metadata(), time(5)) }
    }
    @Test fun `manual judgment same score remains manual and repositions retain invalidated decision`() {
        val c = config().copy(targetType = ClassicTargetFixtures.classic40, regulatoryEdition = null)
        val a = arrow(c = c, r = .105f)
        val boundary = c.targetType.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
        val judged = active(c).withImpact(id(10), a).decideImpact(id(10), id(100),
            ScoreDecisionRequest.JudgeBoundary(boundary.reference, BoundaryValue(9), time(1)), time(1))
        val manual = judged.ends.single().end.impacts.single()
        assertTrue(manual.isCordDecisionManual); assertEquals(9, manual.scoreFinal)
        val moved = judged.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(2), "Explicit reposition")
        val impact = moved.ends.single().end.impacts.single()
        assertEquals(8, impact.scoreCalculated); assertEquals(8, impact.scoreFinal)
        assertEquals(manual.finalScoreDecision, impact.repositions.single().previousDecision)
        assertEquals(manual.point, impact.repositions.single().previousPoint); assertEquals(time(2), moved.updatedAt)
        assertSame(manual, judged.copy().ends.single().end.impacts.single())
        assertTrue(manual.repositions.isEmpty()); assertEquals(9, manual.scoreCalculated)
        val final = moved.decideImpact(id(10), id(100), ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed nine", time(3)), time(3))
        assertEquals(9L, summarizeConfiguredSession(final).totals.provisional.score)
        assertEquals(impact.repositions, final.ends.single().end.impacts.single().repositions)
        assertEquals(9, manual.scoreFinal); assertTrue(a.repositions.isEmpty())
    }
    @Test fun `exceptional correction invalidation needs reason and preserves opaque position provenance`() {
        val s = active().withImpact(id(10), arrow(r = .105f)).decideImpact(id(10), id(100),
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Observed ten", time(1)), time(1))
        for (reason in listOf(null, " ")) assertFailsWith<IllegalArgumentException> {
            s.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(2), reason)
        }
        val moved = s.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(2), "Correction de position")
        val impact = moved.ends.single().end.impacts.single()
        assertEquals(InputMode.MANUAL, impact.inputMode); assertEquals(id(100), impact.uuid); assertEquals(now, impact.createdAt)
        assertEquals("Observed ten", impact.repositions.single().previousDecision.provenance.reason)
        assertEquals(8, impact.scoreFinal); assertFalse(impact.finalScoreDecision.provenance.isManual)
    }
    @Test fun `X M nine and maximum counts follow stored final values through corrections and moves`() {
        val c = config().copy(targetType = ClassicTargetFixtures.classic40, regulatoryEdition = null)
        var s = active(c).withImpact(id(10), arrow(c = c, r = 0f))
        assertEquals(1L, summarizeConfiguredSession(s).totals.provisional.xCount)
        s = s.repositionImpact(id(10), id(100), NormalizedPoint(.15f, 0f), time(1))
        var t = summarizeConfiguredSession(s).totals.provisional
        assertEquals(0L, t.xCount); assertEquals(1L, t.nineCount)
        s = s.repositionImpact(id(10), id(100), NormalizedPoint(1.1f, 0f), time(2))
        t = summarizeConfiguredSession(s).totals.provisional
        assertEquals(0L, t.nineCount); assertEquals(1L, t.missCount)
        val edge = c.targetType.scoreBoundaries().single { it.reference.type == BoundaryType.TARGET_EDGE }
        s = s.decideImpact(id(10), id(100), ScoreDecisionRequest.JudgeBoundary(edge.reference, edge.inner, time(3)), time(3))
        assertEquals(0L, summarizeConfiguredSession(s).totals.provisional.missCount)
        assertEquals(1L, summarizeConfiguredSession(s).totals.provisional.score)
        s = s.repositionImpact(id(10), id(100), NormalizedPoint(0f, 0f), time(4), "Move after edge judgment")
        t = summarizeConfiguredSession(s).totals.provisional
        assertEquals(1L, t.xCount); assertEquals(1L, t.maxScoreCount); assertEquals(10L, t.score)
    }
    @Test fun `score and reposition corrections cannot be backdated`() {
        val s = active().withImpact(id(10), arrow(at = time(10)))
        assertFailsWith<IllegalArgumentException> { s.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(5)) }
        assertFailsWith<IllegalArgumentException> { s.decideImpact(id(10), id(100),
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "observed", time(5)), time(10)) }
    }
    @Test fun `impact history cannot be silently removed by reconstruction copy or end replacement`() {
        val s = active().withImpact(id(10), arrow()).repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(1))
        val old = s.ends.single().end.impacts.single()
        val forged = arrow(r = .25f).copy(updatedAt = time(2))
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), forged) }
        assertFailsWith<IllegalArgumentException> { s.withEnd(endWith(forged)) }
        assertFailsWith<IllegalArgumentException> { old.copy(point = NormalizedPoint(.35f, 0f), updatedAt = time(2)) }
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { s.withEnd(End(id(10), 1, EndStatus.EDITING, createdAt = now, updatedAt = time(2))) }
        assertEquals(1, old.repositions.size)
    }
    @Test fun `ordinary impacts without traces cannot be removed or moved between ends either`() {
        val s = active().withImpact(id(10), arrow()).createNextEnd(id(11), now)
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { s.withEnd(End(id(10), 1, EndStatus.EDITING, createdAt = now, updatedAt = now)) }
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(11), arrow()) }
        assertFailsWith<IllegalArgumentException> { s.withEnd(endWith(arrow(), id(11), 2)) }
    }
    @Test fun `end and impact indexes creation time and identities cannot be rewritten`() {
        val s = active().withImpact(id(10), arrow())
        val end = s.ends.single().end
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { s.withEnd(end.copy(index = 2)) }
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { s.withImpact(id(10), arrow().copy(index = 2)) }
        issue(ConfiguredSessionIssue.UNKNOWN_END) { s.withEnd(end.copy(uuid = id(11))) }
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), arrow(at = time(1))) }
        assertFailsWith<IllegalArgumentException> { s.withEnd(End(end.uuid, end.index, EndStatus.EDITING, createdAt = time(1), updatedAt = time(1))) }
    }
    @Test fun `stale child revision is rejected even if enclosing parent timestamp is recent`() {
        val original = active().withImpact(id(10), arrow())
        val current = original.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(2))
        assertFailsWith<IllegalArgumentException> { current.withEnd(original.ends.single().end, time(10)) }
    }
    @Test fun `copy is identical immutable and exposes no replacement parameters`() {
        val s = active().withImpact(id(10), arrow()); val copy = s.copy()
        assertEquals(s, copy); assertEquals(s.hashCode(), copy.hashCode()); assertNotSame(s, copy)
        assertSame(s.ends.single(), copy.ends.single())
        assertFailsWith<UnsupportedOperationException> { (copy.ends as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (copy.ends.single().end.impacts as MutableList).clear() }
        val publicCopy = ConfiguredSession::class.java.methods.single { it.name == "copy" }
        assertEquals(0, publicCopy.parameterCount)
    }
    @Test fun `complete state validator rejects unrelated or reset same UUID reconstructions`() {
        val s = active().withImpact(id(10), arrow())
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { validateConfiguredSessionUpdate(s, draft().start(now)) }
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { validateConfiguredSessionUpdate(s, draft(uuid = id(2))) }
        issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { validateConfiguredSessionUpdate(s, active(config(true))) }
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { validateConfiguredSessionUpdate(s, active()) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { validateConfiguredSessionUpdate(s, draft()) }
    }
    @Test fun `full state revision checks preserve original capture even for empty ends`() {
        val first = active()
        val explicit = draft().start(now).createNextEnd(id(10), now, NextEndConfigurationChoice.Replace(config()))
        issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { validateConfiguredSessionUpdate(first, explicit) }
        val other = active().createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(config(true)))
        val changed = active().createNextEnd(id(11), now, NextEndConfigurationChoice.Replace(config()))
        issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { validateConfiguredSessionUpdate(other, changed) }
    }
    @Test fun `completed state rejects even otherwise coherent alternate reconstruction`() {
        val completed = active().complete(time(2))
        val alternate = active().withMetadata(metadata().copy(title = "changed"), time(1)).complete(time(2))
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { validateConfiguredSessionUpdate(completed, alternate) }
        validateConfiguredSessionUpdate(completed, completed.copy())
    }
    @Test fun `homogeneous complete scope is numerically identical to historical APIs`() {
        var s = active()
        repeat(3) { s = s.withImpact(id(10), arrow(100 + it, it + 1, r = listOf(.025f, .15f, .25f)[it])) }
        s = s.validateEnd(id(10), now)
        val old = legacy(ends = s.ends.map { it.end }).copy(plannedEndCount = null)
        val summary = summarizeConfiguredSession(s); val historical = summarizeSessionScore(old)
        assertEquals(calculateEndScore(old.ends.single()).toLong(), summary.ends.single().totals.score)
        assertEquals(calculateSessionScore(old).toLong(), summary.totals.validated.score)
        assertEquals(historical.totalScore.toLong(), summary.totals.validated.score)
        assertEquals(historical.maxPossibleScore.toLong(), summary.totals.validated.recordedCapacityMaximum)
        assertEquals(historical.percentage, summary.totals.validated.percentageOfRecordedCapacity)
    }
    @Test fun `mixed scoring uses actual profile distance count and declared status for each end`() {
        val compound = config(true, 25, 6)
        var s = active().withImpact(id(10), arrow()).validateEnd(id(10), now)
        s = s.createNextEnd(id(11), time(1), NextEndConfigurationChoice.Replace(compound))
            .withImpact(id(11), arrow(101, c = compound, at = time(1)))
        val result = summarizeConfiguredSession(s)
        assertEquals(listOf(10L, 9L), result.ends.map { it.totals.score })
        assertEquals(10L, result.totals.validated.score); assertEquals(9L, result.totals.provisional.score)
        assertEquals(19L, result.totals.recorded.score)
        assertEquals(listOf(18, 25), result.groups.map { it.configuration.distanceM })
        assertEquals(summarizeConfiguredEnds(configuredSessionScoringContexts(s)), result)
        assertEquals(1L, result.totals.recorded.maxScoreCount); assertEquals(1L, result.totals.recorded.nineCount)
    }
    @Test fun `scoring does not mutate impacts decisions traces or prior summaries`() {
        val s = active().withImpact(id(10), arrow()).repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(1))
        val before = s.copy(); val impact = s.ends.single().end.impacts.single(); val summary = summarizeConfiguredSession(s)
        val next = s.createNextEnd(id(11), time(2), NextEndConfigurationChoice.Replace(config(true)))
        assertEquals(before, s); assertEquals(summary, summarizeConfiguredSession(s))
        assertSame(impact, next.ends[0].end.impacts.single()); assertSame(impact.finalScoreDecision, next.ends[0].end.impacts.single().finalScoreDecision)
        assertSame(impact.repositions, next.ends[0].end.impacts.single().repositions)
    }
    @Test fun `legacy conversion requires validated archive and exact requested source`() {
        val source = archive(legacy())
        issue(ConfiguredSessionIssue.MISSING_HISTORICAL_SOURCE) { ConfiguredSession.fromLegacyArchive(source, id(999)) }
        val bad = snapshotArchive(listOf(TargetProfileSnapshot(WA.recurve40, null)), listOf(legacy()))
        assertIs<ArchiveReconstruction.NeedsIntervention>(reconstructArchive(bad))
        val missing = bad.copy(profiles = emptyList())
        assertIs<ArchiveReconstruction.NeedsIntervention>(reconstructArchive(missing))
    }
    @Test fun `conversion preserves all historical statuses metadata instants UUIDs indexes and source end order`() {
        val ends = listOf(endWith(arrow(101), id(11), 2), endWith(arrow(100), id(10), 1, EndStatus.VALIDATED))
        for (status in SessionStatus.entries) {
            val source = archive(legacy(ends = ends, status = status))
            val original = source.sessions.single(); val converted = ConfiguredSession.fromLegacyArchive(source, original.uuid)
            assertEquals(metadata(), converted.metadata); assertEquals(status, converted.status)
            assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, converted.initialConfigurationOrigin)
            assertEquals(original.createdAt, converted.createdAt); assertEquals(original.updatedAt, converted.updatedAt)
            assertEquals(original.startedAt, converted.startedAt); assertEquals(original.endedAt, converted.endedAt)
            assertEquals(original.ends, converted.ends.map { it.end }); assertSame(original.ends[0], converted.ends[0].end)
            assertTrue(converted.ends.all { it.origin == EndConfigurationOrigin.LegacyDerived })
            assertEquals(summarizeConfiguredEnds(configuredSessionScoringContexts(converted)), summarizeConfiguredSession(converted))
        }
    }
    @Test fun `conversion keeps original edition for eight built ins and null historical editions`() {
        for (entry in BuiltInTargetRegistry.all) {
            val c = config().copy(targetType = entry.definition, regulatoryEdition = entry.regulatoryEdition)
            val source = archive(legacy(c), entry.regulatoryEdition)
            val converted = ConfiguredSession.fromLegacyArchive(source, id(1))
            assertEquals(entry.definition, converted.initialConfiguration.targetType)
            assertEquals(entry.regulatoryEdition, converted.initialConfiguration.regulatoryEdition)
        }
    }
    @Test fun `custom historical edition is preserved exactly and never inferred from current registry`() {
        val c = config().copy(targetType = WA.recurve40.copy(uuid = id(900), isBuiltIn = false), regulatoryEdition = " Origine 🏹 2025 ")
        for (edition in listOf(c.regulatoryEdition, null)) {
            val source = archive(legacy(c), edition)
            val converted = ConfiguredSession.fromLegacyArchive(source, id(1))
            assertEquals(edition, converted.initialConfiguration.regulatoryEdition)
        }
    }
    @Test fun `converted empty legacy end is permanently bound then following end inherits its true configuration`() {
        val c = config(true, 25, null)
        val source = archive(legacy(c, listOf(End(id(10), 7, EndStatus.EDITING, createdAt = now, updatedAt = now))))
        val converted = ConfiguredSession.fromLegacyArchive(source, id(1))
        val next = converted.createNextEnd(id(11), time(1))
        assertEquals(8, next.ends[1].end.index); assertEquals(c, next.ends[1].configuration)
        assertEquals(EndConfigurationOrigin.LegacyDerived, next.ends[0].origin)
        assertEquals(EndConfigurationOrigin.Inherited(id(10)), next.ends[1].origin)
    }
    @Test fun `conversion retains manual decisions and invalidation traces by reference without replay`() {
        val c = config(); val a = arrow().withFinalScore(c.targetType,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Observed", time(1)), time(1))
            .reposition(NormalizedPoint(.25f, 0f), time(2), "Position correction")
        val source = archive(legacy(c, listOf(endWith(a))))
        val before = source.toSnapshot()
        val converted = ConfiguredSession.fromLegacyArchive(source, id(1)); val original = source.sessions.single().ends.single().impacts.single()
        assertSame(original, converted.ends.single().end.impacts.single())
        assertSame(original.repositions, converted.ends.single().end.impacts.single().repositions)
        assertEquals(8L, summarizeConfiguredSession(converted).totals.provisional.score)
        assertEquals(before, source.toSnapshot())
    }
    @Test fun `legacy index overflow is explicit instead of wrapping or renumbering`() {
        val source = archive(legacy(ends = listOf(End(id(10), Int.MAX_VALUE, EndStatus.EDITING, createdAt = now, updatedAt = now))))
        val converted = ConfiguredSession.fromLegacyArchive(source, id(1))
        issue(ConfiguredSessionIssue.INDEX_OVERFLOW) { converted.createNextEnd(id(11), time(1)) }
        assertEquals(Int.MAX_VALUE, converted.ends.single().end.index)
    }
    @Test fun `new revision cannot be replaced by reset legacy source even when configuration values match`() {
        val s = active()
        val source = archive(legacy(ends = s.ends.map { it.end }))
        val derived = ConfiguredSession.fromLegacyArchive(source, id(1))
        issue(ConfiguredSessionIssue.CONFIGURATION_CONFLICT) { validateConfiguredSessionUpdate(s, derived) }
    }
    @Test fun `legacy snapshot contradictions remain rejected before conversion without repair`() {
        val snapshot = archive(legacy(ends = listOf(endWith(arrow())))).toSnapshot()
        val root = snapshot.sessions.single(); val child = root.ends.single()
        val broken = listOf(root.copy(updatedAt = now.minusNanos(1).toString()), root.copy(startedAt = time(1).toString()),
            root.copy(ends = listOf(child.copy(createdAt = now.minusNanos(1).toString()))),
            root.copy(ends = listOf(child, child)), root.copy(ends = listOf(child.copy(uuid = root.uuid))))
        broken.forEach { assertIs<ArchiveReconstruction.NeedsIntervention>(reconstructArchive(snapshot.copy(sessions = listOf(it)))) }
    }

    @Test fun `empty legacy session retains derivation provenance before any end exists`() {
        val converted = ConfiguredSession.fromLegacyArchive(archive(legacy()), id(1))
        assertTrue(converted.ends.isEmpty())
        assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, converted.initialConfigurationOrigin)
        val next = converted.createNextEnd(id(10), now)
        assertEquals(EndConfigurationOrigin.Initial, next.ends.single().origin)
        assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, next.initialConfigurationOrigin)
    }
    @Test fun `reconstructed coordinates without reposition and erased manual decision are rejected`() {
        val s = active().withImpact(id(10), arrow())
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), arrow(r = .25f).copy(updatedAt = time(1))) }
        val manual = s.decideImpact(id(10), id(100),
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed nine", time(1)), time(1))
        assertFailsWith<IllegalArgumentException> { manual.withImpact(id(10), arrow().copy(updatedAt = time(2))) }
        assertEquals(9, manual.ends.single().end.impacts.single().scoreFinal)
    }
    @Test fun `duplicate impact indexes are rejected without losing either historical identity`() {
        val s = active().withImpact(id(10), arrow())
        assertFailsWith<IllegalArgumentException> { s.withImpact(id(10), arrow(101, 1)) }
        assertEquals(listOf(id(100)), s.ends.single().end.impacts.map { it.uuid })
    }
    @Test fun `composed forward revisions preserve starts and accept explicit completed history`() {
        val before = draft()
        val after = before.start(time(1)).createNextEnd(id(10), time(2))
            .withImpact(id(10), arrow(at = time(3))).validateEnd(id(10), time(4)).complete(time(5))
        validateConfiguredSessionUpdate(before, after)
        assertEquals(time(1), after.startedAt); assertEquals(time(4), after.ends.single().end.validatedAt)
        assertEquals(time(5), after.endedAt); assertEquals(now, after.createdAt)
        val changedStart = draft().start(time(2)).createNextEnd(id(10), time(2))
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { validateConfiguredSessionUpdate(before.start(time(1)), changedStart) }
    }
    @Test fun `initial and event chronology cannot be rewritten by otherwise valid new sessions`() {
        val before = active().withMetadata(metadata().copy(notes = "latest"), time(10))
        val earlier = active().withMetadata(metadata().copy(notes = "other"), time(5))
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { validateConfiguredSessionUpdate(before, earlier) }
        val differentCreation = ConfiguredSession.create(id(1), metadata(), config(), now.minusSeconds(1)).start(now)
        issue(ConfiguredSessionIssue.HISTORY_CONFLICT) { validateConfiguredSessionUpdate(before, differentCreation) }
        val closedTooSoon = active().complete(time(5)).copy()
        issue(ConfiguredSessionIssue.TEMPORAL_CONFLICT) { validateConfiguredSessionUpdate(before, closedTooSoon) }
    }
    @Test fun `legacy completed and validated sources remain protected after conversion`() {
        val source = archive(legacy(ends = listOf(endWith(arrow(), status = EndStatus.VALIDATED)), status = SessionStatus.COMPLETED))
        val converted = ConfiguredSession.fromLegacyArchive(source, id(1))
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { converted.repositionImpact(id(10), id(100), NormalizedPoint(.25f, 0f), time(1)) }
        issue(ConfiguredSessionIssue.FORBIDDEN_TRANSITION) { converted.withEnd(converted.ends.single().end) }
        validateConfiguredSessionUpdate(converted, converted.copy())
    }
    @Test fun `scoring contexts own immutable list and results stay deterministic across copies`() {
        val s = active().createNextEnd(id(11), time(1), NextEndConfigurationChoice.Replace(config(true)))
        val contexts = configuredSessionScoringContexts(s)
        assertFailsWith<UnsupportedOperationException> { (contexts as MutableList).clear() }
        assertEquals(summarizeConfiguredSession(s), summarizeConfiguredSession(s.copy()))
        assertEquals(summarizeConfiguredSession(s).hashCode(), summarizeConfiguredSession(s.copy()).hashCode())
    }


    @Test fun `manual judgment and reposition use compound effective configuration rather than initial recurve`() {
        val compound = config(true, 25, 6)
        val before = active().withImpact(id(10), arrow()).validateEnd(id(10), now)
        var s = before.createNextEnd(id(11), time(1), NextEndConfigurationChoice.Replace(compound))
            .withImpact(id(11), arrow(101, c = compound, at = time(1)))
        val boundary = compound.targetType.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
        assertEquals(.05f, boundary.reference.radiusNormalized)
        s = s.decideImpact(id(11), id(101), ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner, time(2)), time(2))
        val judged = s.ends[1].end.impacts.single()
        assertEquals(9, judged.scoreCalculated); assertEquals(10, judged.scoreFinal); assertFalse(judged.isX)
        s = s.repositionImpact(id(11), id(101), NormalizedPoint(.25f, 0f), time(3), "Correction poulies")
        val moved = s.ends[1].end.impacts.single()
        assertEquals(8, moved.scoreFinal); assertEquals(judged.finalScoreDecision, moved.repositions.single().previousDecision)
        assertEquals(compound.targetType, moved.targetType); assertSame(before.ends.single(), s.ends[0])
        val t = summarizeConfiguredSession(s).totals
        assertEquals(10L, t.validated.score); assertEquals(8L, t.provisional.score); assertEquals(18L, t.recorded.score)
    }

}
