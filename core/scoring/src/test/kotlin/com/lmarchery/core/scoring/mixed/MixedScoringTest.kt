package com.lmarchery.core.scoring.mixed

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class MixedScoringTest {
    private val now = Instant.parse("2026-10-20T10:00:00.123456789Z")
    private fun id(n: Int) = "a0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun config(target: TargetType = WA.recurve40, count: Int? = 3, distance: Int = 18,
        edition: String? = BuiltInTargetRegistry.find(target.uuid)?.regulatoryEdition,
        scoring: ScoringMode = ScoringMode.TRAINING, indoor: Boolean = true) = EndConfiguration(
        target, edition, distance, if (count == null) ArrowCountMode.FREE else ArrowCountMode.FIXED, count, indoor, scoring)
    private fun arrow(n: Int, index: Int, target: TargetType, radius: Float) = scoreArrowImpact(
        id(10000 + n * 100 + index), index, target, NormalizedPoint(radius, -0.0f), ScoringMode.TRAINING, createdAt = now)
    private fun context(n: Int = 1, c: EndConfiguration = config(), radii: List<Float> = listOf(.025f, .15f, .25f),
        status: EndStatus = EndStatus.VALIDATED, impacts: List<ArrowImpact>? = null): EndScoringContext {
        val values = impacts ?: radii.mapIndexed { i, r -> arrow(n, i + 1, c.targetType, r) }
        val updated = values.maxOfOrNull { it.updatedAt } ?: now
        val end = End(id(n), n, EndStatus.EDITING, values, createdAt = now, updatedAt = updated)
            .let { if (status == EndStatus.VALIDATED) it.validate(updated) else it }
        return EndScoringContext(end, EndConfigurationBinding(end.uuid, c))
    }
    private fun custom(n: Int = 900, max: Int = 10, x: Boolean = false) = TargetType(id(n), "Synthetic profile $n", "test",
        40, max, 1, 3, 18, false, x, if (x) "X" else "", if (x) .05f else 0f,
        if (max == 1) listOf(TargetZone(1, 0f, 1f, "white")) else listOf(
            TargetZone(max, 0f, .1f, "gold"), TargetZone(1, .1f, 1f, "white")))
    private fun summary(vararg contexts: EndScoringContext) = summarizeConfiguredEnds(contexts.toList())
    private fun issue(expected: MixedScoringIssue, operation: () -> Unit): MixedScoringException =
        assertFailsWith<MixedScoringException>(block = operation).also { assertEquals(expected, it.code); assertTrue(it.path.isNotBlank()) }
    private fun legacy(contexts: List<EndScoringContext>, planned: Int? = null): Session {
        val c = contexts.first().configuration
        return Session(id(999), SessionDateTime.fromInstant(now, ZoneId.of("UTC")), SessionStatus.IN_PROGRESS,
            startedAt = now, plannedEndCount = planned, title = "Synthetic homogeneous session", targetType = c.targetType,
            distanceM = c.distanceM, arrowCountMode = c.arrowCountMode, arrowCountPerEnd = c.arrowCountPerEnd,
            isIndoor = c.isIndoor, scoringMode = c.scoringMode, ends = contexts.map { it.end }, createdAt = now,
            updatedAt = contexts.maxOf { it.end.updatedAt })
    }

    @Test fun `all eight published definitions and editions calculate without substitution`() {
        BuiltInTargetRegistry.all.forEach { entry ->
            val c = context(c = config(entry.definition))
            val scored = calculateConfiguredEndScore(c)
            assertSame(entry.definition, scored.context.configuration.targetType)
            assertEquals(entry.regulatoryEdition, scored.context.configuration.regulatoryEdition)
            assertEquals(c.end.impacts.sumOf { it.scoreFinal }.toLong(), scored.totals.score)
        }
    }
    @Test fun `explicit binding is required even for empty ends`() {
        val c = context(radii = emptyList())
        val e = issue(MixedScoringIssue.MISSING_CONFIGURATION) { bindEndConfigurations(listOf(c.end), emptyList()) }
        assertEquals("ends[${c.end.uuid}].configuration", e.path)
    }
    @Test fun `identical or conflicting duplicate bindings are rejected rather than overwritten`() {
        val c = context()
        for (duplicate in listOf(c.binding, c.binding.copy(configuration = c.configuration.copy(distanceM = 70))))
            issue(MixedScoringIssue.DUPLICATE_CONFIGURATION) { bindEndConfigurations(listOf(c.end), listOf(c.binding, duplicate)) }
    }
    @Test fun `binding for an absent end is rejected`() {
        val c = context()
        issue(MixedScoringIssue.UNKNOWN_END) { bindEndConfigurations(listOf(c.end), listOf(c.binding, c.binding.copy(endUuid = id(2)))) }
    }
    @Test fun `wrong owner cannot construct or copy a context even with no impacts`() {
        val c = context(radii = emptyList())
        issue(MixedScoringIssue.WRONG_END) { EndScoringContext(c.end, c.binding.copy(endUuid = id(2))) }
        issue(MixedScoringIssue.WRONG_END) { c.copy(binding = c.binding.copy(endUuid = id(2))) }
    }
    @Test fun `same diameter does not allow a classic impact under a compound configuration`() {
        val c = context(c = config(WA.recurve40))
        issue(MixedScoringIssue.PROFILE_MISMATCH) { c.copy(binding = c.binding.copy(configuration = config(WA.compound40))) }
        assertEquals(10, c.end.impacts.first().scoreFinal)
    }
    @Test fun `known profile cannot be changed under its UUID including isBuiltIn false`() {
        val c = context(radii = emptyList())
        for (target in listOf(WA.recurve40.copy(name = "Changed"), WA.recurve40.copy(isBuiltIn = false)))
            issue(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT) { context(c = config(target), radii = emptyList()) }
        assertEquals(WA.recurve40, c.configuration.targetType)
    }
    @Test fun `known edition must be exact and cannot be guessed when missing`() {
        for (edition in listOf(null, "different edition"))
            issue(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT) { context(c = config(edition = edition), radii = emptyList()) }
    }
    @Test fun `unknown built in profile is rejected`() {
        issue(MixedScoringIssue.UNKNOWN_BUILT_IN) { context(c = config(custom().copy(isBuiltIn = true)), radii = emptyList()) }
    }
    @Test fun `custom profile permits null or declared nonblank edition without certification`() {
        for (edition in listOf(null, " Club 🏹 edition ")) {
            val c = context(c = config(custom(), edition = edition))
            assertEquals(edition, summary(c).groups.single().configuration.regulatoryEdition)
        }
    }
    @Test fun `one custom UUID cannot carry two editions across contexts including empty ends`() {
        val target = custom()
        issue(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT) { summary(
            context(1, config(target, edition = "edition 1"), emptyList()),
            context(2, config(target, edition = "edition 2"), emptyList())) }
    }
    @Test fun `one custom UUID cannot carry two definitions across contexts`() {
        val first = custom(); val second = first.copy(name = "Contradictory definition")
        issue(MixedScoringIssue.PROFILE_IDENTITY_CONFLICT) { summary(context(1, config(first)), context(2, config(second))) }
    }
    @Test fun `distinct identities with same diameter and geometry but different editions form separate groups`() {
        val first = custom(); val second = first.copy(uuid = id(901))
        val s = summary(context(1, config(first, edition = "edition 1")), context(2, config(second, edition = "edition 2")))
        assertEquals(2, s.groups.size); assertEquals(ConfigurationHomogeneity.HETEROGENEOUS, s.homogeneity)
        assertEquals(listOf("edition 1", "edition 2"), s.groups.map { it.configuration.regulatoryEdition })
        assertEquals(first.zones, second.zones)
    }
    @Test fun `repeated end UUIDs are rejected both during binding and summarizing`() {
        val c = context()
        issue(MixedScoringIssue.DUPLICATE_END) { bindEndConfigurations(listOf(c.end, c.end), listOf(c.binding)) }
        issue(MixedScoringIssue.DUPLICATE_END) { summary(c, c.copy()) }
    }
    @Test fun `an impact cannot be counted under two different ends`() {
        val first = context(); val second = context(2, impacts = first.end.impacts)
        issue(MixedScoringIssue.DUPLICATE_IMPACT) { summary(first, second) }
    }
    @Test fun `UUID collisions across end impact and profile types are rejected`() {
        val c = context()
        issue(MixedScoringIssue.IDENTITY_COLLISION) {
            val end = End(c.configuration.targetType.uuid, 2, EndStatus.EDITING, createdAt = now, updatedAt = now)
            EndScoringContext(end, EndConfigurationBinding(end.uuid, c.configuration))
        }
        val duplicate = ArrowImpact(c.end.uuid, 1, NormalizedPoint(0f, 0f), inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = WA.recurve40)
        issue(MixedScoringIssue.IDENTITY_COLLISION) { context(1, impacts = listOf(duplicate)) }
        val other = context(2)
        val cross = ArrowImpact(other.end.uuid, 1, NormalizedPoint(0f, 0f), inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = WA.recurve40)
        issue(MixedScoringIssue.IDENTITY_COLLISION) { summary(context(1, impacts = listOf(cross)), other) }
    }
    @Test fun `binding captures collections and returns an unmodifiable canonical list`() {
        val first = context(1); val second = context(2)
        val ends = mutableListOf(second.end, first.end); val bindings = mutableListOf(second.binding, first.binding)
        val resolved = bindEndConfigurations(ends, bindings); ends.clear(); bindings.clear()
        assertEquals(listOf(first, second), resolved)
        assertFailsWith<UnsupportedOperationException> { (resolved as MutableList).clear() }
    }
    @Test fun `homogeneous fixed complete ends agree with every historical summary field at equivalent scope`() {
        for (count in listOf(3, 6, 5)) {
            val c = config(count = count)
            val contexts = listOf(context(1, c, List(count) { .025f }), context(2, c, List(count) { 1.1f }))
            val oldSession = legacy(contexts, planned = 2); val old = summarizeSessionScore(oldSession)
            val result = summarizeConfiguredEnds(contexts); val t = result.totals.validated
            assertEquals(calculateEndScore(contexts[0].end).toLong(), result.ends[0].totals.score)
            assertEquals(calculateSessionScore(oldSession).toLong(), t.score)
            assertEquals(calculateMaxPossibleScore(oldSession).toLong(), t.recordedCapacityMaximum)
            assertEquals(old.percentage, t.percentageOfRecordedCapacity)
            assertEquals(old.averagePerArrow, t.averagePerArrow); assertEquals(old.averagePerEnd, t.averagePerEnd)
            assertEquals(old.maxScoreCount.toLong(), t.maxScoreCount); assertEquals(old.xCount.toLong(), t.xCount)
            assertEquals(old.missCount.toLong(), t.missCount); assertEquals(old.nineCount.toLong(), t.nineCount)
            assertEquals(old.validatedEndCount.toLong(), t.endCount); assertEquals(old.validatedImpactCount.toLong(), t.impactCount)
            assertEquals(ConfigurationHomogeneity.HOMOGENEOUS, result.homogeneity)
        }
    }
    @Test fun `homogeneous FREE agrees with historical results including empty ends and arbitrary planned count`() {
        val c = config(count = null)
        val contexts = listOf(context(1, c, listOf(.025f, .15f)), context(2, c, emptyList()), context(3, c, listOf(.25f)))
        val session = legacy(contexts, planned = 999); val old = summarizeSessionScore(session)
        val t = summarizeConfiguredEnds(contexts).totals.validated
        assertEquals(old.totalScore.toLong(), t.score); assertEquals(old.maxPossibleScore.toLong(), t.recordedCapacityMaximum)
        assertEquals(t.recordedArrowMaximum, t.recordedCapacityMaximum); assertEquals(old.percentage, t.percentageOfRecordedCapacity)
        assertEquals(old.averagePerArrow, t.averagePerArrow); assertEquals(old.averagePerEnd, t.averagePerEnd)
    }
    @Test fun `classic and compound alternate without reinterpreting the same point`() {
        val contexts = (1..4).map { i -> context(i, config(if (i % 2 == 1) WA.recurve40 else WA.compound40), listOf(.075f)) }
        val s = summarizeConfiguredEnds(contexts)
        assertEquals(listOf(10L, 9L, 10L, 9L), s.ends.map { it.totals.score })
        assertEquals(38L, s.totals.validated.score); assertEquals(2L, s.totals.validated.maxScoreCount)
        assertEquals(2L, s.totals.validated.nineCount); assertEquals(0L, s.totals.validated.xCount)
        assertEquals(2, s.groups.size)
    }
    @Test fun `validated provisional and recorded scopes have independent denominators and counters`() {
        val first = context(1, radii = listOf(.025f))
        val second = context(2, config(WA.compound40, count = 6, distance = 70), listOf(.075f, 1.1f), EndStatus.EDITING)
        val s = summary(first, second)
        assertEquals(10L, s.totals.validated.score); assertEquals(9L, s.totals.provisional.score); assertEquals(19L, s.totals.recorded.score)
        assertEquals(30L, s.totals.validated.recordedCapacityMaximum); assertEquals(60L, s.totals.provisional.recordedCapacityMaximum)
        assertEquals(90L, s.totals.recorded.recordedCapacityMaximum)
        assertEquals(1L, s.totals.provisional.missCount); assertEquals(0L, s.totals.validated.missCount)
        assertEquals(1L, s.totals.validated.endCount); assertEquals(2L, s.totals.recorded.endCount)
        assertEquals(0L, s.groups[0].totals.provisional.endCount)
    }
    @Test fun `all provisional ends leave validated totals empty and ratios undefined`() {
        val s = summary(context(status = EndStatus.EDITING))
        assertEquals(0L, s.totals.validated.score); assertEquals(0L, s.totals.validated.recordedCapacityMaximum)
        assertNull(s.totals.validated.percentageOfRecordedCapacity); assertNull(s.totals.validated.averagePerEnd)
        assertEquals(27L, s.totals.provisional.score)
    }
    @Test fun `no ends means no maximum no averages and no percentage`() {
        val s = summary()
        assertEquals(ConfigurationHomogeneity.EMPTY, s.homogeneity); assertTrue(s.groups.isEmpty())
        for (t in listOf(s.totals.validated, s.totals.provisional, s.totals.recorded)) {
            assertEquals(0L, t.score); assertEquals(0L, t.endCount); assertEquals(0L, t.recordedCapacityMaximum)
            assertNull(t.percentageOfRecordedCapacity); assertNull(t.percentageOfRecordedArrows)
            assertNull(t.averagePerArrow); assertNull(t.averagePerEnd)
        }
        assertTrue(bindEndConfigurations(emptyList(), emptyList()).isEmpty())
    }
    @Test fun `empty FIXED and FREE expose capacity versus observed denominator without judging validation`() {
        val fixed = context(1, radii = emptyList())
        val free = context(2, config(count = null), emptyList(), EndStatus.EDITING)
        val s = summary(fixed, free)
        assertEquals(30L, s.ends[0].totals.recordedCapacityMaximum); assertEquals(0L, s.ends[0].totals.recordedArrowMaximum)
        assertEquals(0.0, s.ends[0].totals.percentageOfRecordedCapacity); assertNull(s.ends[0].totals.percentageOfRecordedArrows)
        assertEquals(EndArrowCountRelation.BELOW_FIXED, s.ends[0].arrowCountRelation)
        assertEquals(EndArrowCountRelation.UNBOUNDED, s.ends[1].arrowCountRelation)
        assertEquals(0L, s.ends[1].totals.recordedCapacityMaximum); assertNull(s.ends[1].totals.percentageOfRecordedCapacity)
        assertEquals(1L, s.totals.validated.endCount); assertEquals(0.0, s.totals.validated.averagePerEnd)
        assertNull(s.totals.validated.averagePerArrow)
    }
    @Test fun `underfilled FIXED reports both technical percentages without deciding a product policy`() {
        val scored = calculateConfiguredEndScore(context(radii = listOf(.025f, .025f)))
        assertEquals(EndArrowCountRelation.BELOW_FIXED, scored.arrowCountRelation)
        assertEquals(30L, scored.totals.recordedCapacityMaximum); assertEquals(20L, scored.totals.recordedArrowMaximum)
        assertEquals(200.0 / 3.0, scored.totals.percentageOfRecordedCapacity!!, 1e-12)
        assertEquals(100.0, scored.totals.percentageOfRecordedArrows)
        assertEquals(EndStatus.VALIDATED, scored.context.end.status)
    }
    @Test fun `overfilled FIXED remains visible without clamping score or approving its status`() {
        for (status in EndStatus.entries) {
            val scored = calculateConfiguredEndScore(context(radii = List(4) { .025f }, status = status))
            assertEquals(EndArrowCountRelation.ABOVE_FIXED, scored.arrowCountRelation)
            assertEquals(40L, scored.totals.score); assertEquals(30L, scored.totals.recordedCapacityMaximum)
            assertTrue(scored.totals.percentageOfRecordedCapacity!! > 100.0)
            assertEquals(100.0, scored.totals.percentageOfRecordedArrows); assertEquals(status, scored.context.end.status)
        }
    }
    @Test fun `FIXED 3 6 other positive counts and FREE use explicit correct recorded maximum`() {
        for (count in listOf(1, 3, 6, 7, Int.MAX_VALUE, null)) {
            val c = context(c = config(count = count, distance = Int.MAX_VALUE), radii = listOf(.025f, .15f))
            assertEquals((count?.toLong() ?: 2L) * 10L, calculateRecordedMaximum(c))
            assertEquals(20L, calculateConfiguredEndScore(c).totals.recordedArrowMaximum)
        }
    }
    @Test fun `totals use Long before multiplying or summing Int maximum scores`() {
        val c = context(c = config(custom(max = Int.MAX_VALUE), count = Int.MAX_VALUE), radii = List(3) { 0f })
        val t = calculateConfiguredEndScore(c).totals
        assertEquals(6_442_450_941L, t.score)
        assertEquals(4_611_686_014_132_420_609L, t.recordedCapacityMaximum)
        assertEquals(3L, t.maxScoreCount); assertEquals(0L, t.nineCount)
        assertTrue(t.percentageOfRecordedCapacity!!.isFinite()); assertEquals(100.0, t.percentageOfRecordedArrows)
    }
    @Test fun `two extreme capacities fit exactly while ratios and averages remain finite`() {
        val c = config(custom(max = Int.MAX_VALUE), count = Int.MAX_VALUE)
        val s = summary(context(1, c, listOf(0f)), context(2, c, listOf(0f)))
        assertEquals(9_223_372_028_264_841_218L, s.totals.validated.recordedCapacityMaximum)
        for (t in listOf(s.totals.validated, s.totals.provisional, s.totals.recorded))
            for (r in listOf(t.percentageOfRecordedCapacity, t.percentageOfRecordedArrows, t.averagePerEnd, t.averagePerArrow))
                assertTrue(r == null || r.isFinite())
    }
    @Test fun `overflow inside a group rejects the whole aggregation with a typed path`() {
        val c = config(custom(max = Int.MAX_VALUE), count = Int.MAX_VALUE)
        val contexts = (1..3).map { context(it, c, emptyList()) }
        val before = contexts.toList()
        val error = issue(MixedScoringIssue.NUMERIC_OVERFLOW) { summarizeConfiguredEnds(contexts) }
        assertEquals("validated.recordedCapacityMaximum", error.path); assertEquals(before, contexts)
    }
    @Test fun `overflow across individually valid groups rejects the global result too`() {
        val c = config(custom(max = Int.MAX_VALUE), count = Int.MAX_VALUE)
        val contexts = (1..3).map { context(it, c.copy(distanceM = it), emptyList()) }
        contexts.forEach { assertEquals(1, summary(it).groups.size) }
        issue(MixedScoringIssue.NUMERIC_OVERFLOW) { summarizeConfiguredEnds(contexts) }
    }
    @Test fun `maximum score counters use each profile not a global ten`() {
        val five = context(1, config(custom(900, 5)), listOf(0f, .2f, 1.1f))
        val nine = context(2, config(custom(901, 9)), listOf(0f, .2f))
        val s = summary(five, nine).totals.validated
        assertEquals(2L, s.maxScoreCount); assertEquals(1L, s.nineCount); assertEquals(1L, s.missCount)
        assertEquals(16L, s.score)
    }
    @Test fun `X M nine and max counters use final decisions rather than geometry`() {
        val target = ClassicTargetFixtures.classic40
        fun correction(a: ArrowImpact, value: BoundaryValue) = a.withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(value, "Synthetic final correction", now), now)
        val toNine = correction(arrow(1, 1, target, 0f), BoundaryValue(9))
        val border = target.scoreBoundaries().single { it.reference.type == BoundaryType.TARGET_EDGE }
        val fromMiss = arrow(1, 2, target, 1.1f).withFinalScore(target,
            ScoreDecisionRequest.JudgeBoundary(border.reference, border.inner, now), now)
        val tenNine = target.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
        val judged = arrow(1, 3, target, .105f).withFinalScore(target,
            ScoreDecisionRequest.JudgeBoundary(tenNine.reference, tenNine.inner, now), now)
        val remainsX = arrow(1, 4, target, 0f)
        val toMiss = correction(arrow(1, 5, target, 0f), BoundaryValue(0))
        val c = context(c = config(target, count = null), impacts = listOf(toNine, fromMiss, judged, remainsX, toMiss))
        val t = summary(c).totals.validated
        assertEquals(30L, t.score); assertEquals(2L, t.maxScoreCount); assertEquals(1L, t.xCount)
        assertEquals(1L, t.missCount); assertEquals(1L, t.nineCount)
        assertEquals(0, fromMiss.scoreCalculated); assertFalse(fromMiss.isMiss)
        assertEquals(10, toMiss.scoreCalculated); assertTrue(toMiss.isMiss); assertFalse(toMiss.isX)
        val old = summarizeSessionScore(legacy(listOf(c)))
        assertEquals(old.totalScore.toLong(), t.score); assertEquals(old.maxPossibleScore.toLong(), t.recordedCapacityMaximum)
        assertEquals(old.xCount.toLong(), t.xCount); assertEquals(old.missCount.toLong(), t.missCount)
        assertEquals(old.nineCount.toLong(), t.nineCount); assertEquals(old.percentage, t.percentageOfRecordedCapacity)
    }
    @Test fun `generic X remains a distinction of the actual profile maximum even when it is not ten`() {
        val c = context(c = config(custom(max = 5, x = true)), radii = listOf(0f))
        val t = summary(c).totals.validated
        assertEquals(5L, t.score); assertEquals(1L, t.maxScoreCount); assertEquals(1L, t.xCount)
        assertEquals(0L, t.nineCount); assertEquals(0L, t.missCount)
    }
    @Test fun `manual invalidations decisions and reposition traces are retained exactly through aggregation`() {
        val target = ClassicTargetFixtures.classic40
        val base = arrow(1, 1, target, .105f)
        val judged = base.withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(10), "Observed ten", now), now)
        val moved = judged.reposition(NormalizedPoint(.25f, -0.0f), now.plusSeconds(1), "Synthetic move")
        val final = moved.withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed nine", now.plusSeconds(2)), now.plusSeconds(2))
        val c = context(c = config(target), impacts = listOf(final)); val before = c.end.copy()
        val result = summary(c)
        assertSame(c.end, result.ends.single().context.end); assertEquals(before, c.end)
        assertSame(final.finalScoreDecision, result.ends.single().context.end.impacts.single().finalScoreDecision)
        assertSame(final.repositions, result.ends.single().context.end.impacts.single().repositions)
        assertEquals(judged.finalScoreDecision, final.repositions.single().previousDecision)
        assertEquals(8, final.scoreCalculated); assertEquals(9L, result.totals.validated.score)
    }
    @Test fun `aggregate percentage is computed from summed denominators not averaged percentages`() {
        val s = summary(context(1, config(count = 3), List(3) { .025f }), context(2, config(count = 6), List(6) { 1.1f }))
        assertEquals(30L, s.totals.validated.score); assertEquals(90L, s.totals.validated.recordedCapacityMaximum)
        assertEquals(100.0 / 3.0, s.totals.validated.percentageOfRecordedCapacity!!, 1e-12)
        assertNotEquals(50.0, s.totals.validated.percentageOfRecordedCapacity)
    }
    @Test fun `FREE denominator counts misses too and is null only without any positive maximum`() {
        val missed = calculateConfiguredEndScore(context(c = config(count = null), radii = listOf(1.1f))).totals
        assertEquals(10L, missed.recordedCapacityMaximum); assertEquals(0.0, missed.percentageOfRecordedCapacity)
        val empty = calculateConfiguredEndScore(context(c = config(count = null), radii = emptyList())).totals
        assertEquals(0L, empty.recordedCapacityMaximum); assertNull(empty.percentageOfRecordedCapacity)
    }
    @Test fun `groups distinguish distance scoring mode count mode count and indoor independently`() {
        val initial = config()
        val configurations = listOf(initial, initial.copy(distanceM = 70), initial.copy(scoringMode = ScoringMode.COMPETITION),
            initial.copy(arrowCountPerEnd = 6), initial.copy(arrowCountMode = ArrowCountMode.FREE, arrowCountPerEnd = null), initial.copy(isIndoor = false),
            initial.copy(scoringMode = ScoringMode.MANUAL_CORDON))
        val s = summarizeConfiguredEnds(configurations.mapIndexed { i, c -> context(i + 1, c) })
        assertEquals(configurations, s.groups.map { it.configuration }); assertEquals(7, s.groups.size)
        assertEquals(ConfigurationHomogeneity.HETEROGENEOUS, s.homogeneity)
    }
    @Test fun `input permutations produce identical results hash and first end group order`() {
        val contexts = listOf(context(1), context(2, config(count = 6)), context(3), context(4, config(count = null)))
        val canonical = summarizeConfiguredEnds(contexts)
        for (input in listOf(contexts.reversed(), listOf(contexts[2], contexts[0], contexts[3], contexts[1]))) {
            assertEquals(canonical, summarizeConfiguredEnds(input)); assertEquals(canonical.hashCode(), summarizeConfiguredEnds(input).hashCode())
            val bound = bindEndConfigurations(input.map { it.end }, input.reversed().map { it.binding })
            assertEquals(canonical, summarizeConfiguredEnds(bound))
        }
        assertEquals(listOf(contexts[0].configuration, contexts[1].configuration, contexts[3].configuration), canonical.groups.map { it.configuration })
    }
    @Test fun `equal end indexes from different sources use UUID tie break without inventing a session owner`() {
        val first = context(1); val secondBase = context(2)
        val second = secondBase.copy(end = secondBase.end.copy(index = 1))
        assertEquals(listOf(first, second), summary(second, first).ends.map { it.context })
    }
    @Test fun `result lists at every level are owned and unmodifiable`() {
        val c = context(); val input = mutableListOf(c); val s = summarizeConfiguredEnds(input); val beforeHash = s.hashCode()
        input.clear()
        assertEquals(1, s.ends.size); assertEquals(beforeHash, s.hashCode())
        assertFailsWith<UnsupportedOperationException> { (s.ends as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (s.groups as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (s.groups.single().ends as MutableList).clear() }
        assertEquals(summary(c), s)
    }
    @Test fun `changing the next configuration cannot retroactively change a scored end`() {
        val previous = context(); val before = summary(previous)
        val next = resolveNextEndConfiguration(previous.configuration, previous.configuration,
            NextEndConfigurationChoice.Replace(config(WA.compound40, count = 6, distance = 70)))
        val following = context(2, next, listOf(.075f))
        assertEquals(before, summary(previous)); assertEquals(9L, calculateConfiguredEndScore(following).totals.score)
        assertEquals(WA.recurve40, previous.configuration.targetType); assertEquals(3, previous.configuration.arrowCountPerEnd)
    }
    @Test fun `partial homogeneous totals preserve historical values only at corresponding scopes`() {
        val contexts = listOf(context(1), context(2, status = EndStatus.EDITING))
        val session = legacy(contexts); val old = summarizeSessionScore(session); val s = summarizeConfiguredEnds(contexts)
        assertEquals(old.totalScore.toLong(), s.totals.validated.score)
        assertEquals(old.maxPossibleScore.toLong(), s.totals.recorded.recordedCapacityMaximum)
        assertEquals(old.percentage, old.totalScore.toDouble() / s.totals.recorded.recordedCapacityMaximum.toDouble() * 100.0)
        assertEquals(90.0, s.totals.validated.percentageOfRecordedCapacity)
        assertEquals(45.0, old.percentage)
    }
    @Test fun `recorded maximum excludes planned ends that have not been supplied`() {
        val c = context(); val old = summarizeSessionScore(legacy(listOf(c), planned = 12))
        val t = summary(c).totals.recorded
        assertEquals(360, old.maxPossibleScore); assertEquals(30L, t.recordedCapacityMaximum)
        assertEquals(old.totalScore.toLong(), t.score); assertEquals(90.0, t.percentageOfRecordedCapacity)
    }
    @Test fun `combined scope overflow is detected even when validated and provisional each fit`() {
        val c = config(custom(max = Int.MAX_VALUE), count = Int.MAX_VALUE)
        val validated = listOf(context(1, c, emptyList()), context(2, c, emptyList()))
        val provisional = context(3, c, emptyList(), EndStatus.EDITING)
        assertTrue(summarizeConfiguredEnds(validated).totals.validated.recordedCapacityMaximum > 0)
        assertTrue(summary(provisional).totals.provisional.recordedCapacityMaximum > 0)
        val e = issue(MixedScoringIssue.NUMERIC_OVERFLOW) { summarizeConfiguredEnds(validated + provisional) }
        assertEquals("recorded.recordedCapacityMaximum", e.path)
    }

}
