package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.summarizeSessionScore
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArchiveReconstructionTest {
    private val target = ClassicTargetFixtures.classic40
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private val boundary = BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f)

    @Test
    fun `all eight frozen profiles are recognized without changing their identities or content`() {
        val expected = ClassicTargetFixtures.all + WorldArcheryIndoorTargetProfiles.all
        assertEquals(8, BuiltInTargetRegistry.all.size)
        assertEquals(expected, BuiltInTargetRegistry.all.map { it.definition })
        assertEquals(8, expected.map { it.uuid }.distinct().size)
        expected.forEach { profile ->
            val source = session(listOf(impact(profile = profile)), profile)
            assertEquals(source, accepted(archive(source)).sessions.single())
        }
        assertTrue(BuiltInTargetRegistry.all.take(4).all { it.regulatoryEdition == null })
        assertTrue(BuiltInTargetRegistry.all.drop(4).all { it.regulatoryEdition == "2026-01-27" })
    }

    @Test
    fun `published UUID rejects changes to geometry display flags defaults and zone ordering`() {
        val official = WorldArcheryIndoorTargetProfiles.compound40
        val base = archive(session(listOf(impact(profile = official)), official))
        val variants = listOf(official.copy(diameterCm = 60), official.copy(name = "Renamed"),
            official.copy(isBuiltIn = false), official.copy(defaultDistanceM = 25),
            official.copy(zones = official.zones.reversed()),
            official.copy(zones = official.zones.map { it.copy(colorHex = null) }))
        variants.forEach { changed ->
            issue(base.copy(profiles = listOf(base.profiles.single().copy(definition = changed))),
                ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT)
        }
    }

    @Test
    fun `edition is checked separately and cannot be inferred from an official UUID`() {
        val official = WorldArcheryIndoorTargetProfiles.recurve40
        val base = archive(session(listOf(impact(profile = official)), official))
        listOf(null, "2027-01-01").forEach { edition ->
            issue(base.copy(profiles = listOf(base.profiles.single().copy(regulatoryEdition = edition))),
                ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT)
        }
        issue(archive().copy(profiles = listOf(TargetProfileSnapshot(target, "2026-01-27"))),
            ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT)
    }

    @Test
    fun `unknown built-in is not accepted just because it claims built-in status`() {
        issue(archive().copy(profiles = listOf(TargetProfileSnapshot(target.copy(uuid = id(40)), null))),
            ArchiveIssueCode.UNKNOWN_PROFILE)
    }

    @Test
    fun `different complete definitions under one UUID conflict inside an archive`() {
        val custom = custom()
        val base = archive(session(listOf(impact(profile = custom)), custom))
        issue(base.copy(profiles = base.profiles + TargetProfileSnapshot(custom.copy(name = "Other"), null)),
            ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT)
    }

    @Test
    fun `a custom profile retains every field and its explicit optional edition`() {
        val custom = custom()
        val source = session(listOf(impact(profile = custom)), custom)
        val snapshot = snapshotArchive(listOf(TargetProfileSnapshot(custom, "Club definition 1")), listOf(source))
        val result = accepted(snapshot)
        assertEquals(snapshot.profiles, result.profiles)
        assertEquals(source, result.sessions.single())
        assertEquals(snapshot, result.toSnapshot())
    }

    @Test
    fun `unknown definition and dangling profile references are unresolved`() {
        issue(archive().copy(profiles = listOf(TargetProfileSnapshot(null, null))), ArchiveIssueCode.UNKNOWN_PROFILE)
        issue(archive().copy(profiles = emptyList()), ArchiveIssueCode.UNKNOWN_PROFILE)
    }

    @Test
    fun `absent and unsupported schema or scoring versions never fall back`() {
        listOf(null, 0, 2, 99).forEach { version ->
            val code = if (version == null) ArchiveIssueCode.MISSING_FIELD else ArchiveIssueCode.UNSUPPORTED_VERSION
            issue(archive().copy(schemaVersion = version), code)
            issue(archive().copy(scoringContractVersion = version), code)
        }
    }

    @Test
    fun `ordinary complete session round trips with all metadata and civil context`() {
        val source = session().copy(notes = "Synthetic notes", locationName = "Synthetic range",
            bowUuid = id(60), arrowSetUuid = id(61), plannedEndCount = 20)
        val snapshot = archive(source)
        val restored = accepted(snapshot)
        assertEquals(source, restored.sessions.single())
        assertEquals(snapshot, restored.toSnapshot())
    }

    @Test
    fun `manual boundary judgment and same-score confirmation retain provenance`() {
        listOf(9, 10).forEach { score ->
            val original = impact().withFinalScore(target,
                ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(score), now.plusSeconds(2)), now.plusSeconds(3))
            val rebuilt = roundTrip(original)
            assertEquals(9, rebuilt.scoreCalculated)
            assertEquals(score, rebuilt.scoreFinal)
            assertTrue(rebuilt.isCordDecisionManual)
            assertEquals(original.finalScoreDecision.provenance, rebuilt.finalScoreDecision.provenance)
        }
    }

    @Test
    fun `exceptional correction retains its reason and date with no invented boundary`() {
        val original = impact(0f).withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "Observed miss", now.plusSeconds(2)), now.plusSeconds(3))
        val rebuilt = roundTrip(original)
        assertEquals("Observed miss", rebuilt.finalScoreDecision.provenance.reason)
        assertTrue(rebuilt.isMiss)
        assertFalse(rebuilt.isX)
    }

    @Test
    fun `multiple repositions replay invalidated manual decisions and intermediate revisions exactly`() {
        val original = moved()
        val rebuilt = roundTrip(original)
        assertEquals(original.repositions, rebuilt.repositions)
        assertEquals(2, rebuilt.repositions.size)
        assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT,
            rebuilt.repositions[0].invalidatedManualDecision!!.provenance.kind)
        assertEquals(ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
            rebuilt.repositions[1].invalidatedManualDecision!!.provenance.kind)
    }

    @Test
    fun `pure geometry reposition and a new judgment after it round trip`() {
        val moved = impact().reposition(NormalizedPoint(0.075f, 0f), now.plusSeconds(2))
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(9), now.plusSeconds(3)),
                now.plusSeconds(4)).copy(note = "Metadata after judgment", updatedAt = now.plusSeconds(5))
        assertEquals(moved, roundTrip(moved))
    }

    @Test
    fun `final X M and validated statistics survive reconstruction`() {
        val x = impact(0f, 1)
        val miss = impact(1.1f, 2)
        val nine = impact(0f, 3).withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed nine", now.plusSeconds(2)), now.plusSeconds(2))
        val source = session(listOf(x, miss, nine))
        val validated = source.withEnd(source.ends.single().validate(source.updatedAt), source.updatedAt)
            .complete(source.updatedAt.plusSeconds(1))
        val rebuilt = accepted(archive(validated)).sessions.single()
        assertEquals(validated, rebuilt)
        assertEquals(summarizeSessionScore(validated), summarizeSessionScore(rebuilt))
        val summary = summarizeSessionScore(rebuilt)
        assertEquals(19, summary.totalScore)
        assertEquals(1, summary.xCount)
        assertEquals(1, summary.missCount)
        assertEquals(1, summary.nineCount)
    }

    @Test
    fun `nanoseconds survive current and invalidated decisions traces parents and civil date`() {
        val source = session(listOf(moved()))
        val rebuilt = accepted(archive(source)).sessions.single()
        assertEquals(source, rebuilt)
        assertEquals(123456789, rebuilt.createdAt.nano)
        assertEquals(123456789, rebuilt.ends.single().impacts.single().repositions.last().changedAt.nano)
        assertEquals(987654321, rebuilt.date.localDateTime.nano)
    }

    @Test
    fun `autumn overlap preserves either explicitly chosen offset without using the default zone`() {
        listOf("+01:00", "+02:00").forEach { offset ->
            val source = session().copy(date = SessionDateTime.of(
                LocalDateTime.parse("2026-10-25T02:30:00.987654321"), ZoneId.of("Europe/Paris"), ZoneOffset.of(offset)))
            assertEquals(source, accepted(archive(source)).sessions.single())
        }
    }

    @Test
    fun `equivalent event offsets identify the same instant without precision loss`() {
        val snapshot = archive()
        val source = snapshot.sessions.single()
        val changed = snapshot.copy(sessions = listOf(source.copy(createdAt = "2026-10-25T02:00:00.123456789+02:00")))
        assertEquals(accepted(snapshot).sessions, accepted(changed).sessions)
    }

    @Test
    fun `missing decision or kind never becomes implicit geometric acceptance`() {
        issue(mapImpact(archive()) { it.copy(decision = null) }, ArchiveIssueCode.MISSING_PROVENANCE)
        issue(mapImpact(archive()) { it.copy(decision = it.decision!!.copy(kind = null)) }, ArchiveIssueCode.MISSING_PROVENANCE)
    }

    @Test
    fun `manual decision without selection date boundary or exceptional reason is refused`() {
        val manual = archive(session(listOf(judged())))
        issue(mapImpact(manual) { it.copy(decision = it.decision!!.copy(selected = null)) }, ArchiveIssueCode.MISSING_FIELD)
        issue(mapImpact(manual) { it.copy(decision = it.decision!!.copy(decidedAt = null)) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(mapImpact(manual) { it.copy(decision = it.decision!!.copy(boundary = null)) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(mapImpact(manual) { it.copy(decision = it.decision!!.copy(kind = ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
            boundary = null, reason = "  ")) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
    }

    @Test
    fun `contradictory geometry provenance is rejected rather than repaired`() {
        issue(mapImpact(archive()) { it.copy(decision = it.decision!!.copy(decidedAt = utc(2))) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(mapImpact(archive()) { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(10))) },
            ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE)
    }

    @Test
    fun `nonexistent boundary illegal side and forbidden X are contextually rejected`() {
        val snapshot = archive(session(listOf(judged())))
        issue(mapImpact(snapshot) { it.copy(decision = it.decision!!.copy(
            boundary = BoundaryReference(BoundaryType.SCORE_ZONES, 0.15f))) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(mapImpact(snapshot) { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(8))) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        val official = WorldArcheryIndoorTargetProfiles.compound40
        val indoor = archive(session(listOf(impact(profile = official)), official))
        issue(mapImpact(indoor) { it.copy(decision = DecisionSnapshot(ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
            BoundaryValue(10, true), utc(2), null, "No X allowed", null), updatedAt = utc(2)) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
    }

    @Test
    fun `every received redundant score assertion is recomputed and checked`() {
        val invalid = listOf(ScoreAssertions(distanceFromCenter = Float.NaN), ScoreAssertions(scoreCalculated = 8),
            ScoreAssertions(scoreFinal = 10), ScoreAssertions(geometricIsX = true), ScoreAssertions(isX = true),
            ScoreAssertions(isMiss = true), ScoreAssertions(isCordDecisionManual = true))
        invalid.forEach { assertions ->
            issue(mapImpact(archive()) { it.copy(decision = it.decision!!.copy(assertions = assertions)) },
                ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE)
        }
        assertEquals(session(), accepted(mapImpact(archive()) { it.copy(decision = it.decision!!.copy(assertions = null)) }).sessions.single())
    }

    @Test
    fun `naive event dates are retained unresolved without UTC inference`() {
        val source = mapImpact(archive()) { it.copy(createdAt = "2026-10-25T00:00:01.123456789") }
        val result = issue(source, ArchiveIssueCode.TEMPORAL_CONTEXT)
        assertEquals(source, result.source)
        issue(mapImpact(archive()) { it.copy(createdAt = null) }, ArchiveIssueCode.MISSING_FIELD)
    }

    @Test
    fun `missing civil zone or offset and incompatible IANA triplets remain unresolved`() {
        val snapshot = archive()
        val source = snapshot.sessions.single()
        listOf(source.date!!.copy(zoneId = null), source.date.copy(offset = null)).forEach { date ->
            issue(snapshot.copy(sessions = listOf(source.copy(date = date))), ArchiveIssueCode.MISSING_FIELD)
        }
        listOf(source.date.copy(offset = "+03:00"), source.date.copy(zoneId = "+01:00"),
            CivilDateSnapshot("2026-03-29T02:30:00", "Europe/Paris", "+01:00")).forEach { date ->
            val unresolved = snapshot.copy(sessions = listOf(source.copy(date = date)))
            assertEquals(unresolved, issue(unresolved, ArchiveIssueCode.CIVIL_CONTEXT_CONFLICT).source)
        }
    }

    @Test
    fun `children newer than parents and validation or completion before children are refused`() {
        val snapshot = archive(session(listOf(moved())))
        val source = snapshot.sessions.single()
        issue(snapshot.copy(sessions = listOf(source.copy(updatedAt = utc(2)))), ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(snapshot.copy(sessions = listOf(source.copy(ends = listOf(source.ends.single().copy(updatedAt = utc(2)))))),
            ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(snapshot.copy(sessions = listOf(source.copy(ends = listOf(source.ends.single().copy(
            status = EndStatus.VALIDATED, validatedAt = utc(2)))))), ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(snapshot.copy(sessions = listOf(source.copy(status = SessionStatus.COMPLETED, endedAt = utc(2)))),
            ArchiveIssueCode.INVALID_DOMAIN_VALUE)
    }

    @Test
    fun `reposition replay refuses broken spatial paths and final point mismatches`() {
        val snapshot = archive(session(listOf(moved())))
        issue(mapImpact(snapshot) { it.copy(repositions = it.repositions.reversed()) }, ArchiveIssueCode.HISTORY_CONFLICT)
        issue(mapImpact(snapshot) { it.copy(point = NormalizedPoint(0.4f, 0f)) }, ArchiveIssueCode.HISTORY_CONFLICT)
        issue(mapImpact(snapshot) { it.copy(repositions = it.repositions.mapIndexed { i, change ->
            if (i == 1) change.copy(previousPoint = NormalizedPoint(0.4f, 0f)) else change }) }, ArchiveIssueCode.HISTORY_CONFLICT)
    }

    @Test
    fun `trace context cannot change identity creation or input origin`() {
        val snapshot = archive(session(listOf(moved())))
        val changes: List<(RepositionSnapshot) -> RepositionSnapshot> = listOf(
            { it.copy(impactUuid = id(99)) }, { it.copy(impactCreatedAt = utc(0)) }, { it.copy(inputMode = InputMode.CORRECTED) })
        changes.forEach { change ->
            issue(mapImpact(snapshot) { it.copy(repositions = listOf(change(it.repositions.first())) + it.repositions.drop(1)) },
                ArchiveIssueCode.HISTORY_CONFLICT)
        }
    }

    @Test
    fun `trace chronology and manual invalidation reason cannot be omitted`() {
        val snapshot = archive(session(listOf(moved())))
        issue(mapImpact(snapshot) { it.copy(repositions = listOf(it.repositions.first().copy(changedAt = utc(1))) + it.repositions.drop(1)) },
            ArchiveIssueCode.HISTORY_CONFLICT)
        issue(mapImpact(snapshot) { it.copy(repositions = listOf(it.repositions.first().copy(reason = null)) + it.repositions.drop(1)) },
            ArchiveIssueCode.HISTORY_CONFLICT)
        issue(mapImpact(snapshot) { it.copy(repositions = listOf(it.repositions.first().copy(previousDecision = null)) + it.repositions.drop(1)) },
            ArchiveIssueCode.MISSING_PROVENANCE)
    }

    @Test
    fun `previous decision derived fields are also verified during replay`() {
        issue(mapImpact(archive(session(listOf(moved())))) { impact ->
            val trace = impact.repositions.first()
            impact.copy(repositions = listOf(trace.copy(previousDecision = trace.previousDecision!!.copy(
                assertions = ScoreAssertions(scoreCalculated = 99)))) + impact.repositions.drop(1))
        }, ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE)
    }

    @Test
    fun `decision after a reposition cannot be backdated to its former position`() {
        issue(mapImpact(archive(session(listOf(moved())))) { it.copy(decision = it.decision!!.copy(decidedAt = utc(2))) },
            ArchiveIssueCode.INVALID_DOMAIN_VALUE)
    }

    @Test
    fun `known traces cannot be removed rewritten or erased by dropping their impact`() {
        val snapshot = archive(session(listOf(moved())))
        val baseline = accepted(snapshot)
        issue(mapImpact(snapshot) { it.copy(repositions = emptyList()) }, ArchiveIssueCode.HISTORY_CONFLICT, baseline)
        issue(mapImpact(snapshot) { it.copy(repositions = listOf(it.repositions.first().copy(reason = "Rewritten reason")) +
            it.repositions.drop(1)) }, ArchiveIssueCode.HISTORY_CONFLICT, baseline)
        val source = snapshot.sessions.single()
        issue(snapshot.copy(sessions = listOf(source.copy(ends = listOf(source.ends.single().copy(impacts = emptyList()))))),
            ArchiveIssueCode.HISTORY_CONFLICT, baseline)
    }

    @Test
    fun `known custom identity conflicts even when both definitions are individually legal`() {
        val custom = custom()
        val original = archive(session(listOf(impact(profile = custom)), custom))
        issue(original.copy(profiles = listOf(TargetProfileSnapshot(custom.copy(name = "Divergent definition"), null))),
            ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, accepted(original))
    }

    @Test
    fun `different contents with equal newer or older timestamps all require intervention`() {
        val snapshot = archive()
        val known = accepted(snapshot)
        val source = snapshot.sessions.single()
        listOf(source.updatedAt, utc(50), utc(2)).forEach { time ->
            issue(snapshot.copy(sessions = listOf(source.copy(title = "Different content", updatedAt = time))),
                ArchiveIssueCode.DIVERGENT_REVISION, known)
        }
        assertEquals(known.sessions, accepted(snapshot, known).sessions)
    }

    @Test
    fun `changing aggregate identities does not bypass known impact history or ownership checks`() {
        val snapshot = archive(session(listOf(moved())))
        val known = accepted(snapshot)
        val source = snapshot.sessions.single()
        val changed = source.copy(uuid = id(70), ends = listOf(source.ends.single().copy(uuid = id(71))))
        issue(snapshot.copy(sessions = listOf(changed)), ArchiveIssueCode.DIVERGENT_REVISION, known)
        issue(mapImpact(snapshot.copy(sessions = listOf(changed))) { it.copy(repositions = emptyList()) },
            ArchiveIssueCode.HISTORY_CONFLICT, known)
    }

    @Test
    fun `UUID collisions across entity kinds and sessions and noncanonical UUIDs are rejected`() {
        val snapshot = archive()
        val source = snapshot.sessions.single()
        issue(snapshot.copy(sessions = listOf(source, source)), ArchiveIssueCode.DUPLICATE_UUID)
        issue(mapImpact(snapshot) { it.copy(uuid = source.uuid) }, ArchiveIssueCode.DUPLICATE_UUID)
        issue(snapshot.copy(sessions = listOf(source.copy(uuid = "not-a-uuid"))), ArchiveIssueCode.INVALID_UUID)
    }

    @Test
    fun `an impact cannot change its explicit target reference inside a session`() {
        issue(mapImpact(archive()) { it.copy(targetTypeUuid = WorldArcheryIndoorTargetProfiles.compound40.uuid) },
            ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT)
    }

    @Test
    fun `snapshot capture cannot discard the actual complete source target definition`() {
        assertFailsWith<IllegalArgumentException> {
            snapshotArchive(listOf(TargetProfileSnapshot(target.copy(name = "Same UUID other content"), null)), listOf(session()))
        }
    }

    @Test
    fun `multiple sessions ends and empty draft values round trip without losing list order`() {
        val first = session()
        val secondImpact = impact(index = 2)
        val secondEnd = End(id(3), 2, EndStatus.EDITING, listOf(secondImpact), null, now, secondImpact.updatedAt)
        val populated = first.copy(ends = listOf(secondEnd, first.ends.single()))
        val empty = Session(uuid = id(4), date = first.date, status = SessionStatus.DRAFT,
            title = "Empty draft", targetType = target, distanceM = 18, arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING, createdAt = now, updatedAt = now)
        val source = snapshotArchive(listOf(TargetProfileSnapshot(target, null)), listOf(empty, populated))
        assertEquals(listOf(empty, populated), accepted(source).sessions)
        assertEquals(source, accepted(source).toSnapshot())
    }

    @Test
    fun `missing profile edition label blank and duplicate identical profiles are rejected`() {
        val custom = custom()
        val source = archive(session(listOf(impact(profile = custom)), custom))
        issue(source.copy(profiles = listOf(TargetProfileSnapshot(custom, "  "))), ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(source.copy(profiles = source.profiles + source.profiles), ArchiveIssueCode.DUPLICATE_UUID)
    }

    @Test
    fun `nonconsecutive custom scores use actual values and boundaries during restoration`() {
        val custom = custom().copy(minScore = 5, zones = listOf(
            com.lmarchery.core.model.target.TargetZone(10, 0f, 0.1f, "yellow"),
            com.lmarchery.core.model.target.TargetZone(8, 0.1f, 0.3f, "red"),
            com.lmarchery.core.model.target.TargetZone(5, 0.3f, 1f, "blue")))
        val original = impact(profile = custom).withFinalScore(custom,
            ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(10), now.plusSeconds(2)), now.plusSeconds(2))
        val source = archive(session(listOf(original), custom))
        assertEquals(10, accepted(source).sessions.single().ends.single().impacts.single().scoreFinal)
        issue(mapImpact(source) { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(9))) },
            ArchiveIssueCode.INVALID_DOMAIN_VALUE)
        issue(mapImpact(source) { it.copy(decision = it.decision!!.copy(kind = ScoreDecisionKind.EXCEPTIONAL_CORRECTION,
            boundary = null, selected = BoundaryValue(6), reason = "Not a legal value")) }, ArchiveIssueCode.INVALID_DOMAIN_VALUE)
    }

    @Test
    fun `unknown zero offsets never become an assumed civil UTC offset`() {
        val source = archive()
        val session = source.sessions.single()
        listOf("-00", "-0000", "-00:00", "-000000", "-00:00:00").forEach { offset ->
            val date = CivilDateSnapshot("2026-10-25T00:00:00", "UTC", offset)
            issue(source.copy(sessions = listOf(session.copy(date = date))), ArchiveIssueCode.CIVIL_CONTEXT_CONFLICT)
        }
    }

    @Test
    fun `accepted collections own their values and snapshots cannot mutate domain proofs`() {
        val source = session()
        val profiles = mutableListOf(TargetProfileSnapshot(target, null))
        val sessions = mutableListOf(source)
        val result = accepted(ArchiveSnapshot(1, 1, profiles, snapshotArchive(profiles, sessions).sessions))
        profiles.clear()
        sessions.clear()
        assertEquals(1, result.profiles.size)
        assertEquals(source, result.sessions.single())
        assertFailsWith<UnsupportedOperationException> { (result.sessions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.profiles as MutableList).clear() }
    }

    private fun id(number: Int) = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"
    private fun utc(seconds: Long) = UtcTimestamps.format(now.plusSeconds(seconds))
    private fun custom() = target.copy(uuid = id(40), name = "Custom complete face", isBuiltIn = false)

    private fun impact(radius: Float = 0.105f, index: Int = 1, profile: TargetType = target) = ArrowImpact(
        uuid = id(10 + index), index = index, point = NormalizedPoint(radius, 0f), inputMode = InputMode.AUTOMATIC,
        note = "Synthetic impact", createdAt = now.plusSeconds(1), updatedAt = now.plusSeconds(1), targetType = profile)

    private fun judged() = impact().withFinalScore(target,
        ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(10), now.plusSeconds(2)), now.plusSeconds(2))

    private fun moved(): ArrowImpact = judged().copy(updatedAt = now.plusSeconds(3))
        .reposition(NormalizedPoint(0.25f, 0f), now.plusSeconds(4), "Fix first position")
        .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Observed nine", now.plusSeconds(5)),
            now.plusSeconds(6))
        .reposition(NormalizedPoint(1.1f, 0f), now.plusSeconds(7), "Fix second position")
        .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(BoundaryReference(BoundaryType.TARGET_EDGE, 1f),
            BoundaryValue(1), now.plusSeconds(8)), now.plusSeconds(9))

    private fun session(impacts: List<ArrowImpact> = listOf(impact()), profile: TargetType = target): Session {
        val updated = impacts.maxOfOrNull { it.updatedAt } ?: now
        val end = End(id(2), 1, EndStatus.EDITING, impacts, "Synthetic end", now, updated)
        return Session(uuid = id(1), date = SessionDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00.987654321"),
            ZoneId.of("Europe/Paris"), ZoneOffset.of("+01:00")), status = SessionStatus.IN_PROGRESS, startedAt = now,
            title = "Synthetic session", targetType = profile, distanceM = 18, arrowCountMode = ArrowCountMode.FREE,
            isIndoor = true, scoringMode = ScoringMode.COMPETITION, ends = listOf(end), createdAt = now, updatedAt = updated.plusSeconds(10))
    }

    private fun archive(source: Session = session()) = snapshotArchive(listOf(TargetProfileSnapshot(source.targetType,
        BuiltInTargetRegistry.find(source.targetType.uuid)?.regulatoryEdition)), listOf(source))

    private fun accepted(source: ArchiveSnapshot, known: DomainArchive? = null): DomainArchive =
        assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(source, known)).archive

    private fun issue(source: ArchiveSnapshot, code: ArchiveIssueCode, known: DomainArchive? = null): ArchiveReconstruction.NeedsIntervention {
        val result = assertIs<ArchiveReconstruction.NeedsIntervention>(reconstructArchive(source, known))
        assertEquals(code, result.issue.code, result.issue.toString())
        assertTrue(result.issue.path.isNotBlank())
        assertEquals(source, result.source)
        return result
    }

    private fun mapImpact(source: ArchiveSnapshot, transform: (ImpactSnapshot) -> ImpactSnapshot): ArchiveSnapshot {
        val session = source.sessions.single()
        val end = session.ends.single()
        return source.copy(sessions = listOf(session.copy(ends = listOf(end.copy(impacts = end.impacts.map(transform))))))
    }

    private fun roundTrip(impact: ArrowImpact): ArrowImpact {
        val source = session(listOf(impact))
        val snapshot = archive(source)
        val result = accepted(snapshot)
        assertEquals(source, result.sessions.single())
        assertEquals(snapshot, result.toSnapshot())
        return result.sessions.single().ends.single().impacts.single()
    }
}
