package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.mixed.summarizeConfiguredEnds
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.*

class ArchiveV3ReconstructionTest {
    private val d = V3TestData
    private fun ArchiveV3Snapshot.session(change: (ConfiguredSessionSnapshot) -> ConfiguredSessionSnapshot) =
        copy(sessions = sessions!!.mapIndexed { i, s -> if (i == 0) change(s) else s })
    private fun ArchiveV3Snapshot.end(index: Int = 0, change: (ConfiguredEndSnapshot) -> ConfiguredEndSnapshot) =
        session { s -> s.copy(ends = s.ends!!.mapIndexed { i, e -> if (i == index) change(e) else e }) }
    private fun ArchiveV3Snapshot.impact(index: Int = 1, change: (ImpactSnapshot) -> ImpactSnapshot) =
        end { c -> c.copy(end = c.end!!.copy(impacts = c.end.impacts.mapIndexed { i, a -> if (i == index) change(a) else a })) }
    private fun rejected(source: ArchiveV3Snapshot, code: ArchiveV3IssueCode, path: String? = null,
        known: DomainArchiveV3? = null, limits: ArchiveV3Limits = ArchiveV3Limits()): ArchiveV3Issue {
        val result = assertIs<ArchiveV3Reconstruction.NeedsIntervention>(reconstructArchiveV3(source, known, limits))
        assertSame(source, result.source); assertEquals(code, result.issue.code)
        assertTrue(result.issue.path.isNotBlank()); if (path != null) assertEquals(path, result.issue.path)
        return result.issue
    }
    private fun roundTrip(source: DomainArchiveV3): DomainArchiveV3 = d.accepted(source.toSnapshot()).also {
        assertEquals(source, it); assertEquals(source.hashCode(), it.hashCode()); assertEquals(source.toSnapshot(), it.toSnapshot())
    }

    @Test fun `mixed aggregate full business round trip keeps all values and provenance`() {
        val original = d.mixed(); val archive = d.accepted(); val restored = roundTrip(archive)
        assertEquals(original, archive.sessions.single()); assertEquals(original, restored.sessions.single())
        assertEquals(3, restored.toSnapshot().schemaVersion); assertEquals(1, restored.toSnapshot().scoringContractVersion)
    }
    @Test fun `classic compound 18 and 70 metre configurations remain exact and independent`() {
        val s = d.accepted().sessions.single()
        assertEquals(WA.recurve40, s.ends[0].configuration.targetType); assertEquals(18, s.ends[0].configuration.distanceM)
        assertEquals(WA.compound40, s.ends[2].configuration.targetType); assertEquals(70, s.ends[2].configuration.distanceM)
        assertEquals(9, s.ends[2].end.impacts.single().scoreCalculated)
        assertEquals(1L, summarizeConfiguredSession(s).totals.recorded.xCount)
        assertEquals(summarizeConfiguredEnds(configuredSessionScoringContexts(s)), summarizeConfiguredSession(s))
    }
    @Test fun `FIXED three six custom five and FREE remain separate without sport policy`() {
        val s = roundTrip(d.accepted()).sessions.single()
        assertEquals(listOf(3, 3, 6, 6, null, 5), s.ends.map { it.configuration.arrowCountPerEnd })
        assertEquals(ArrowCountMode.FREE, s.ends[4].configuration.arrowCountMode)
        assertTrue(s.ends[5].end.impacts.isEmpty()); assertEquals(EndStatus.VALIDATED, s.ends[5].end.status)
        assertEquals(29L, summarizeConfiguredSession(s).totals.validated.score)
        assertEquals(19L, summarizeConfiguredSession(s).totals.provisional.score)
    }
    @Test fun `empty editable and validated ends all keep an explicit owner configuration and origin`() {
        val a = roundTrip(d.accepted())
        assertTrue(a.sessions.single().ends.all { it.sessionUuid == a.sessions.single().uuid })
        assertEquals(6, a.sessions.single().ends.size)
        assertEquals(3, a.sessions.single().ends.count { it.end.impacts.isEmpty() })
    }
    @Test fun `in progress and completed snapshots retain provisional statuses without retroactive validation`() {
        for (completed in listOf(false, true)) {
            val s = roundTrip(d.accepted(d.snapshot(completed))).sessions.single()
            assertEquals(if (completed) SessionStatus.COMPLETED else SessionStatus.IN_PROGRESS, s.status)
            assertEquals(19L, summarizeConfiguredSession(s).totals.provisional.score)
            if (completed) assertFailsWith<IllegalArgumentException> { s.createNextEnd(d.id(999), s.updatedAt) }
        }
    }
    @Test fun `initial inheritance explicit equal choice and historical origins are distinguished`() {
        val s = d.accepted().sessions.single()
        assertEquals(InitialConfigurationOrigin.CREATION, s.initialConfigurationOrigin)
        assertEquals(EndConfigurationOrigin.Initial, s.ends[0].origin)
        assertEquals(EndConfigurationOrigin.Inherited(d.id(10)), s.ends[1].origin)
        assertEquals(EndConfigurationOrigin.Explicit, s.ends[2].origin)
        assertEquals(EndConfigurationOrigin.Inherited(d.id(12)), s.ends[3].origin)
        val sameChoice = d.mixed().createNextEnd(d.id(16), d.now.plusSeconds(12),
            NextEndConfigurationChoice.Replace(d.mixed().ends.last().configuration))
        val archive = d.accepted(snapshotArchiveV3(d.profiles(), listOf(sameChoice), listOf(d.bow()), listOf(d.arrows())))
        assertEquals(EndConfigurationOrigin.Explicit, roundTrip(archive).sessions.single().ends.last().origin)
    }
    @Test fun `unreferenced complete profile and edition remain in archive`() {
        val a = roundTrip(d.accepted())
        assertEquals(d.profiles(), a.profiles)
        assertTrue(a.sessions.flatMap { it.ends }.none { it.configuration.targetType == WA.compound60 })
        assertEquals(WA.RULEBOOK_VERSION, a.profiles.single { it.definition == WA.compound60 }.regulatoryEdition)
    }
    @Test fun `equipment preserves every field units optional empty strings Float bits and nanoseconds`() {
        val a = roundTrip(d.accepted())
        assertEquals(d.bow(), a.bows.single()); assertEquals(d.arrows(), a.arrowSets.single())
        assertEquals(d.bow().drawWeightLbs!!.toBits(), a.bows.single().drawWeightLbs!!.toBits())
        assertEquals(d.now.plusNanos(1), a.bows.single().updatedAt)
        assertEquals("28 1/2 pouces", a.arrowSets.single().length); assertEquals("", a.arrowSets.single().nock)
        assertEquals(" ", a.arrowSets.single().vanes); assertNull(a.arrowSets.single().material)
    }
    @Test fun `explicit empty catalogs and opaque references are preserved without repair`() {
        val a = d.accepted(d.snapshot().copy(bows = emptyList(), arrowSets = emptyList()))
        val restored = roundTrip(a)
        assertTrue(restored.bows.isEmpty()); assertTrue(restored.arrowSets.isEmpty())
        assertEquals(d.id(700), restored.sessions.single().metadata.bowUuid)
        assertEquals(d.id(701), restored.sessions.single().metadata.arrowSetUuid)
    }
    @Test fun `references may be absent unknown or point at another existing kind without implicit resolution`() {
        for (ref in listOf(null, d.id(999), d.id(1), d.id(701))) {
            val a = d.accepted(d.snapshot().session { it.copy(metadata = it.metadata!!.copy(bowUuid = ref, arrowSetUuid = ref)) })
            assertEquals(ref, roundTrip(a).sessions.single().metadata.bowUuid)
        }
    }
    @Test fun `opaque references still require canonical UUIDs when provided`() {
        rejected(d.snapshot().session { it.copy(metadata = it.metadata!!.copy(bowUuid = "not-an-identity")) },
            ArchiveV3IssueCode.INVALID_UUID, "sessions[0].metadata.bowUuid")
    }
    @Test fun `material only profiles only and fully empty V3 are valid explicit complete content`() {
        val original = d.snapshot()
        for (s in listOf(original.copy(sessions = emptyList()), original.copy(sessions = emptyList(), bows = emptyList(), arrowSets = emptyList()),
            ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), emptyList(), emptyList()))) roundTrip(d.accepted(s))
    }
    @Test fun `versions must be explicit and supported with no fallback`() {
        for (version in listOf(null, -1, 0, 1, 2, 4, Int.MAX_VALUE))
            rejected(d.small().copy(schemaVersion = version), if (version == null) ArchiveV3IssueCode.MISSING_FIELD else ArchiveV3IssueCode.UNSUPPORTED_VERSION, "schemaVersion")
        for (version in listOf(null, 0, 2, 3)) rejected(d.small().copy(scoringContractVersion = version),
            if (version == null) ArchiveV3IssueCode.MISSING_FIELD else ArchiveV3IssueCode.UNSUPPORTED_VERSION, "scoringContractVersion")
        assertEquals(1, ArchiveContractVersions.SCHEMA); assertEquals(2, ArchiveContractVersions.SCHEMA_V2); assertEquals(1, ArchiveContractVersions.SCORING)
    }
    @Test fun `all root lists are mandatory and null is never an empty catalog`() {
        val s = d.small()
        for ((field, bad) in listOf("profiles" to s.copy(profiles = null), "sessions" to s.copy(sessions = null),
            "bows" to s.copy(bows = null), "arrowSets" to s.copy(arrowSets = null)))
            rejected(bad, ArchiveV3IssueCode.MISSING_FIELD, field)
    }
    @Test fun `required session fields have no implicit value`() {
        val s = d.small(); val base = s.sessions!!.single()
        for ((field, bad) in listOf("uuid" to base.copy(uuid = null), "metadata" to base.copy(metadata = null),
            "initialConfiguration" to base.copy(initialConfiguration = null), "status" to base.copy(status = null),
            "ends" to base.copy(ends = null), "createdAt" to base.copy(createdAt = null), "updatedAt" to base.copy(updatedAt = null)))
            rejected(s.copy(sessions = listOf(bad)), ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].$field")
        rejected(s.session { it.copy(initialConfigurationOrigin = null) }, ArchiveV3IssueCode.MISSING_PROVENANCE, "sessions[0].initialConfigurationOrigin")
    }
    @Test fun `required configuration fields have no current profile default`() {
        val s = d.small(); val c = s.sessions!!.single().initialConfiguration!!
        for ((field, bad) in listOf("targetTypeUuid" to c.copy(targetTypeUuid = null), "distanceM" to c.copy(distanceM = null),
            "arrowCountMode" to c.copy(arrowCountMode = null), "isIndoor" to c.copy(isIndoor = null), "scoringMode" to c.copy(scoringMode = null)))
            rejected(s.session { it.copy(initialConfiguration = bad) }, ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].initialConfiguration.$field")
    }
    @Test fun `missing end or configuration or owner is rejected even for an empty end`() {
        val s = d.snapshot()
        for ((field, change) in listOf<Pair<String, (ConfiguredEndSnapshot) -> ConfiguredEndSnapshot>>(
            "end" to { it.copy(end = null) }, "configuration" to { it.copy(configuration = null) }, "sessionUuid" to { it.copy(sessionUuid = null) }))
            rejected(s.end(1, change), ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].ends[1].$field")
    }
    @Test fun `metadata title date and civil components are mandatory`() {
        val s = d.small()
        rejected(s.session { it.copy(metadata = it.metadata!!.copy(title = null)) }, ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].metadata.title")
        rejected(s.session { it.copy(metadata = it.metadata!!.copy(date = null)) }, ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].metadata.date")
        for (civil in listOf(CivilDateSnapshot(null, "Europe/Paris", "+02:00"), CivilDateSnapshot("2026-10-25T02:30", null, "+02:00"),
            CivilDateSnapshot("2026-10-25T02:30", "Europe/Paris", null))) {
            val issue = rejected(s.session { it.copy(metadata = it.metadata!!.copy(date = civil)) }, ArchiveV3IssueCode.MISSING_FIELD)
            assertTrue(issue.path.startsWith("sessions[0].metadata.date."))
        }
    }
    @Test fun `blank titles and nonpositive planned counts are not normalized`() {
        val s = d.small()
        rejected(s.session { it.copy(metadata = it.metadata!!.copy(title = " \n")) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].metadata.title")
        rejected(s.session { it.copy(metadata = it.metadata!!.copy(plannedEndCount = 0)) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].metadata.plannedEndCount")
    }
    @Test fun `nonpositive distances and inconsistent fixed free counts are rejected`() {
        val s = d.small(); val c = s.sessions!!.single().initialConfiguration!!
        for (distance in listOf(0, -1)) rejected(s.session { it.copy(initialConfiguration = c.copy(distanceM = distance)) },
            ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].initialConfiguration.distanceM")
        for (count in listOf(null, 0, -1)) rejected(s.session { it.copy(initialConfiguration = c.copy(arrowCountPerEnd = count)) },
            ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].initialConfiguration.arrowCountPerEnd")
        rejected(s.session { it.copy(initialConfiguration = c.copy(arrowCountMode = ArrowCountMode.FREE)) },
            ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].initialConfiguration.arrowCountPerEnd")
    }
    @Test fun `distance indoor and scoring mode remain explicit custom choices rather than target defaults`() {
        val s = d.small().session { it.copy(initialConfiguration = it.initialConfiguration!!.copy(distanceM = Int.MAX_VALUE, isIndoor = false,
            scoringMode = ScoringMode.MANUAL_CORDON), ends = emptyList()) }
        val a = roundTrip(d.accepted(s)); val c = a.sessions.single().initialConfiguration
        assertEquals(Int.MAX_VALUE, c.distanceM); assertFalse(c.isIndoor); assertEquals(ScoringMode.MANUAL_CORDON, c.scoringMode)
    }
    @Test fun `unknown or missing complete profiles are never loaded from current registry`() {
        rejected(d.snapshot().copy(profiles = emptyList()), ArchiveV3IssueCode.UNKNOWN_PROFILE, "sessions[0].initialConfiguration.targetTypeUuid")
        val s = d.small(); rejected(s.copy(profiles = listOf(s.profiles!!.single().copy(definition = null))), ArchiveV3IssueCode.UNKNOWN_PROFILE, "profiles[0]")
    }
    @Test fun `published definitions and editions cannot be changed even when not referenced`() {
        val s = d.snapshot(); val i = s.profiles!!.lastIndex
        for (bad in listOf(s.profiles[i].copy(regulatoryEdition = null), s.profiles[i].copy(definition = s.profiles[i].definition!!.copy(name = "forged")),
            s.profiles[i].copy(definition = s.profiles[i].definition!!.copy(isBuiltIn = false))))
            rejected(s.copy(profiles = s.profiles.toMutableList().also { it[i] = bad }), ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, "profiles[$i]")
    }
    @Test fun `configuration edition must exactly match archive profile metadata`() {
        val s = d.snapshot()
        rejected(s.end(1) { it.copy(configuration = it.configuration!!.copy(regulatoryEdition = null)) },
            ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, "sessions[0].ends[1].configuration.regulatoryEdition")
    }
    @Test fun `duplicate identical profiles and contradictory definitions are both rejected`() {
        val s = d.small(); val p = s.profiles!!.single()
        rejected(s.copy(profiles = listOf(p, p)), ArchiveV3IssueCode.DUPLICATE_UUID, "profiles[1].definition.uuid")
        rejected(s.copy(profiles = listOf(p, p.copy(regulatoryEdition = "different"))), ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, "profiles[1]")
    }
    @Test fun `UUID validation requires canonical lowercase v4 with RFC variant for all definitions`() {
        val s = d.small()
        for (id in listOf("", "not-uuid", d.id(1).uppercase(), "c0000000-0000-1000-8000-000000000001", "c0000000-0000-4000-0000-000000000001"))
            rejected(s.session { it.copy(uuid = id) }, ArchiveV3IssueCode.INVALID_UUID, "sessions[0].uuid")
    }
    @Test fun `all fifteen category pairs reject a global UUID collision`() {
        val s = d.small()
        val ids = listOf(s.profiles!!.single().definition!!.uuid, s.bows!!.single().uuid!!, s.arrowSets!!.single().uuid!!,
            s.sessions!!.single().uuid!!, s.sessions.single().ends!!.single().end!!.uuid, s.sessions.single().ends!!.single().end!!.impacts.single().uuid)
        fun replace(category: Int, id: String): ArchiveV3Snapshot = when (category) {
            1 -> s.copy(bows = listOf(s.bows.single().copy(uuid = id)))
            2 -> s.copy(arrowSets = listOf(s.arrowSets.single().copy(uuid = id)))
            3 -> s.session { it.copy(uuid = id, ends = it.ends!!.map { e -> e.copy(sessionUuid = id) }) }
            4 -> s.end { it.copy(end = it.end!!.copy(uuid = id)) }
            else -> s.impact(0) { it.copy(uuid = id) }
        }
        for (first in 0..4) for (second in first + 1..5) rejected(replace(second, ids[first]), ArchiveV3IssueCode.DUPLICATE_UUID)
    }
    @Test fun `duplicate sessions ends and impacts are not deduplicated or counted twice`() {
        val s = d.small(); val session = s.sessions!!.single(); val end = session.ends!!.single()
        rejected(s.copy(sessions = listOf(session, session)), ArchiveV3IssueCode.DUPLICATE_UUID, "sessions[1].uuid")
        rejected(s.session { it.copy(ends = listOf(end, end.copy(end = end.end!!.copy(index = 2)))) },
            ArchiveV3IssueCode.DUPLICATE_UUID, "sessions[0].ends[1].end.uuid")
        rejected(s.end { it.copy(end = it.end!!.copy(impacts = listOf(it.end.impacts.single(), it.end.impacts.single().copy(index = 2)))) },
            ArchiveV3IssueCode.DUPLICATE_UUID, "sessions[0].ends[0].end.impacts[1].uuid")
    }
    @Test fun `indexes are positive and unique within the actual owner`() {
        val s = d.small()
        rejected(s.end { it.copy(end = it.end!!.copy(index = 0)) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.index")
        rejected(s.impact(0) { it.copy(index = 0) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.impacts[0].index")
        rejected(s.end { it.copy(end = it.end!!.copy(impacts = it.end.impacts + it.end.impacts.single().copy(uuid = d.id(101)))) },
            ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.impacts[1].index")
        rejected(d.snapshot().end(1) { it.copy(end = it.end!!.copy(index = 1)) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[1].end.index")
    }
    @Test fun `foreign end owner is rejected even on an empty end`() {
        rejected(d.snapshot().end(1) { it.copy(sessionUuid = d.id(999)) }, ArchiveV3IssueCode.WRONG_OWNER, "sessions[0].ends[1].sessionUuid")
    }
    @Test fun `impact is checked against effective end profile rather than initial profile`() {
        rejected(d.snapshot().end(2) { c -> c.copy(end = c.end!!.copy(impacts = c.end.impacts.map { it.copy(targetTypeUuid = WA.recurve40.uuid) })) },
            ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, "sessions[0].ends[2].end.impacts[0].targetTypeUuid")
    }
    @Test fun `inheritance must name the immediately previous end not a future self or unknown owner`() {
        val s = d.snapshot()
        for (previous in listOf(d.id(11), d.id(12), d.id(999))) rejected(s.end(1) {
            it.copy(origin = it.origin!!.copy(previousEndUuid = previous)) }, ArchiveV3IssueCode.INHERITANCE_CONFLICT, "sessions[0].ends[1].origin")
    }
    @Test fun `inherited configuration must retain distance mode count indoor profile and edition`() {
        val s = d.snapshot(); val c = s.sessions!!.single().ends!![1].configuration!!
        for (bad in listOf(c.copy(distanceM = 70), c.copy(arrowCountPerEnd = 6), c.copy(isIndoor = false), c.copy(scoringMode = ScoringMode.TRAINING)))
            rejected(s.end(1) { it.copy(configuration = bad) }, ArchiveV3IssueCode.INHERITANCE_CONFLICT, "sessions[0].ends[1].origin")
    }
    @Test fun `missing origin and kind are rejected without invented confirmation`() {
        val s = d.small()
        rejected(s.end { it.copy(origin = null) }, ArchiveV3IssueCode.MISSING_PROVENANCE, "sessions[0].ends[0].origin")
        rejected(s.end { it.copy(origin = it.origin!!.copy(kind = null)) }, ArchiveV3IssueCode.MISSING_PROVENANCE, "sessions[0].ends[0].origin.kind")
    }
    @Test fun `only inherited origin may contain previous end UUID and it is mandatory there`() {
        val s = d.small()
        rejected(s.end { it.copy(origin = it.origin!!.copy(previousEndUuid = d.id(9))) }, ArchiveV3IssueCode.INHERITANCE_CONFLICT,
            "sessions[0].ends[0].origin.previousEndUuid")
        rejected(s.end { it.copy(origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.INHERITED, null)) },
            ArchiveV3IssueCode.MISSING_FIELD, "sessions[0].ends[0].origin.previousEndUuid")
    }
    @Test fun `initial and legacy origins cannot assert contradictory capture values`() {
        val s = d.small()
        rejected(s.end { it.copy(configuration = it.configuration!!.copy(distanceM = 70)) }, ArchiveV3IssueCode.INHERITANCE_CONFLICT,
            "sessions[0].ends[0].origin")
        rejected(s.end { it.copy(origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.LEGACY_DERIVED, null)) },
            ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].ends[0].origin")
    }
    @Test fun `manual decisions X M scoreFinal and both motivated invalidations survive round trip`() {
        val a = roundTrip(d.accepted()); val s = a.sessions.single(); val arrow = s.ends[0].end.impacts[1]
        assertEquals(8, arrow.scoreCalculated); assertEquals(9, arrow.scoreFinal); assertTrue(arrow.finalScoreDecision.provenance.isManual)
        assertFalse(arrow.isCordDecisionManual) // Exceptional correction is manual, but is not a boundary judgment.
        assertEquals(2, arrow.repositions.size); assertTrue(arrow.repositions.all { !it.reason.isNullOrBlank() })
        assertEquals(1, arrow.repositions[0].previousDecision.scoreFinal)
        assertEquals(10, arrow.repositions[1].previousDecision.scoreFinal)
        assertEquals(1L, summarizeConfiguredSession(s).totals.recorded.xCount)
        assertEquals(1L, summarizeConfiguredSession(s).totals.recorded.missCount)
        assertEquals(2L, summarizeConfiguredSession(s).totals.recorded.nineCount)
    }
    @Test fun `decision provenance is mandatory and geometric acceptance is not a guessed manual score`() {
        rejected(d.snapshot().impact { it.copy(decision = null) }, ArchiveV3IssueCode.MISSING_PROVENANCE, "sessions[0].ends[0].end.impacts[1].decision")
        rejected(d.snapshot().impact { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(99))) },
            ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.impacts[1].decision")
    }
    @Test fun `contradictory score and final X M assertions are rejected not repaired`() {
        val s = d.snapshot()
        for (bad in listOf(ScoreAssertions(scoreFinal = 99), ScoreAssertions(isX = true), ScoreAssertions(isMiss = true)))
            rejected(s.impact { it.copy(decision = it.decision!!.copy(assertions = bad)) }, ArchiveV3IssueCode.CONTRADICTORY_DERIVED_VALUE,
                "sessions[0].ends[0].end.impacts[1].decision.assertions")
    }
    @Test fun `trace continuity owner provenance and manual invalidation reason are checked by historical replay`() {
        val s = d.snapshot()
        val bad = listOf<(RepositionSnapshot) -> RepositionSnapshot>(
            { it.copy(previousPoint = NormalizedPoint(.9f, 0f)) }, { it.copy(impactUuid = d.id(999)) },
            { it.copy(inputMode = InputMode.AUTOMATIC) }, { it.copy(reason = " ") })
        for (change in bad) rejected(s.impact { it.copy(repositions = it.repositions.toMutableList().also { r -> r[1] = change(r[1]) }) },
            ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[0].end.impacts[1].repositions[1]")
    }
    @Test fun `current point must be reached by the last trace`() {
        rejected(d.snapshot().impact { it.copy(point = NormalizedPoint(.35f, 0f)) }, ArchiveV3IssueCode.HISTORY_CONFLICT,
            "sessions[0].ends[0].end.impacts[1]")
    }
    @Test fun `manual decision and reposition timestamps cannot be backdated`() {
        rejected(d.snapshot().impact { it.copy(decision = it.decision!!.copy(decidedAt = d.now.toString())) },
            ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.impacts[1].decision")
        rejected(d.snapshot().impact { it.copy(repositions = it.repositions.toMutableList().also { r -> r[1] = r[1].copy(changedAt = d.now.toString()) }) },
            ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[0].end.impacts[1].repositions[1]")
    }
    @Test fun `UTC offsets nanoseconds negative zero and civil overlap context are preserved`() {
        val s = d.small().session { it.copy(createdAt = d.now.atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toString()) }
        val a = roundTrip(d.accepted(s)); val session = a.sessions.single()
        assertEquals(d.now, session.createdAt); assertEquals(d.now.nano, session.createdAt.nano)
        assertEquals("+02:00", session.metadata.date.offset.id); assertEquals(d.metadata().date, session.metadata.date)
        assertEquals((-0.0f).toBits(), session.ends.single().end.impacts.single().point.y.toBits())
        assertEquals(d.now.toString(), a.toSnapshot().sessions!!.single().createdAt)
    }
    @Test fun `civil repeated hour keeps chosen offset and gap inconsistent zone or unknown offset fails`() {
        val s = d.small(); val m = s.sessions!!.single().metadata!!
        for (offset in listOf("+01:00", "+02:00")) {
            val a = d.accepted(s.session { it.copy(metadata = m.copy(date = m.date!!.copy(offset = offset))) })
            assertEquals(offset, a.sessions.single().metadata.date.offset.id)
        }
        for (date in listOf(CivilDateSnapshot("2026-03-29T02:30", "Europe/Paris", "+01:00"),
            CivilDateSnapshot("2026-10-25T02:30", "Europe/Paris", "+03:00"),
            CivilDateSnapshot("2026-10-25T02:30", "unknown-zone", "+02:00"),
            CivilDateSnapshot("2026-10-25T02:30", "Europe/Paris", "-00:00")))
            rejected(s.session { it.copy(metadata = m.copy(date = date)) }, ArchiveV3IssueCode.CIVIL_CONTEXT_CONFLICT, "sessions[0].metadata.date")
    }
    @Test fun `naive malformed unknown-offset or out of range instants are rejected`() {
        for (date in listOf("2026-10-25T00:30:00", "2026-02-31T00:00:00Z", "2026-10-25T00:30:00-00:00", "not-a-date"))
            rejected(d.small().session { it.copy(createdAt = date) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].createdAt")
    }
    @Test fun `session event chronology and coverage of children are enforced`() {
        val s = d.snapshot()
        rejected(s.session { it.copy(updatedAt = d.now.minusNanos(1).toString()) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].updatedAt")
        rejected(s.session { it.copy(startedAt = d.now.minusNanos(1).toString()) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].startedAt")
        rejected(s.session { it.copy(updatedAt = d.now.plusSeconds(10).toString()) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].updatedAt")
        rejected(s.session { it.copy(status = SessionStatus.COMPLETED, endedAt = d.now.plusSeconds(1).toString()) },
            ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].endedAt")
        rejected(s.session { it.copy(status = SessionStatus.DRAFT) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].status")
    }
    @Test fun `end cannot predate session or validate before latest impact revision`() {
        rejected(d.small().end { it.copy(end = it.end!!.copy(createdAt = d.now.minusNanos(1).toString())) },
            ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].ends[0].end.createdAt")
        rejected(d.snapshot().end { it.copy(end = it.end!!.copy(validatedAt = d.now.toString())) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE,
            "sessions[0].ends[0].end")
    }
    @Test fun `system timezone never supplies missing temporal context or changes the result`() {
        val original = TimeZone.getDefault()
        try {
            val a = d.accepted()
            for (zone in listOf("UTC", "Pacific/Honolulu", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone)); assertEquals(a, d.accepted(a.toSnapshot()))
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun `capture and reconstructed domain own all lists and are immutable after source mutation`() {
        val s = d.snapshot(); val raw = s.sessions!!.toMutableList(); val input = s.copy(sessions = raw)
        val a = d.accepted(input); val before = a.toSnapshot(); val hash = a.hashCode()
        raw.clear(); assertEquals(before, a.toSnapshot()); assertEquals(hash, a.hashCode())
        for (list in listOf(a.profiles, a.sessions, a.bows, a.arrowSets, a.sessions.single().ends))
            assertFailsWith<UnsupportedOperationException> { (list as MutableList).clear() }
        val transfer = a.toSnapshot(); (transfer.sessions as MutableList).clear()
        assertEquals(before, a.toSnapshot())
    }
    @Test fun `source ordering is preserved not silently normalized`() {
        val s = d.snapshot().session { it.copy(ends = it.ends!!.reversed()) }
        val a = roundTrip(d.accepted(s))
        assertEquals(s.sessions!!.single().ends!!.map { it.end!!.uuid }, a.sessions.single().ends.map { it.uuid })
        assertEquals(48L, summarizeConfiguredSession(a.sessions.single()).totals.recorded.score)
    }
    @Test fun `no raw public configured constructor or parameterized copy is exposed`() {
        assertTrue(ConfiguredSession::class.java.declaredConstructors.none { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic })
        assertTrue(ConfiguredSession::class.java.methods.filter { it.name == "copy" }.all { it.parameterCount == 0 })
        assertTrue(ConfiguredSession.Companion::class.java.methods.filter { it.name.startsWith("rehydrate") }.all { it.isSynthetic })
    }
    @Test fun `internal rehydration still runs complete owner temporal and origin validation`() {
        val s = d.mixed()
        fun restore(ends: List<ConfiguredEnd>, updated: java.time.Instant = s.updatedAt) = ConfiguredSession.rehydrate(s.uuid, s.metadata,
            s.initialConfiguration, s.initialConfigurationOrigin, s.status, s.startedAt, s.endedAt, ends, s.createdAt, updated)
        assertFailsWith<IllegalArgumentException> { restore(s.ends, d.now) }
        assertFailsWith<IllegalArgumentException> { restore(s.ends.map { ConfiguredEnd(d.id(999), it.end, it.configuration, it.origin) }) }
        assertFailsWith<IllegalArgumentException> { restore(s.ends.map { ConfiguredEnd(s.uuid, it.end, it.configuration, EndConfigurationOrigin.Initial) }) }
        val mutable = s.ends.toMutableList(); val restored = restore(mutable); mutable.clear(); assertEquals(s, restored)
    }
    @Test fun `collection budgets reject early without truncation or partial result`() {
        val s = d.small()
        rejected(s, ArchiveV3IssueCode.LIMIT, "profiles[0].definition.zones", limits = ArchiveV3Limits(maxCollectionElements = 1))
        rejected(s, ArchiveV3IssueCode.LIMIT, "bows", limits = ArchiveV3Limits(maxTotalElements = 2))
    }
    @Test fun `text budget applies to nested metadata equipment configurations and trace reasons`() {
        val s = d.snapshot()
        val long = "z".repeat(16_385)
        rejected(s.session { it.copy(metadata = it.metadata!!.copy(notes = long)) }, ArchiveV3IssueCode.LIMIT, "sessions[0].metadata.notes")
        rejected(s.copy(bows = s.bows!!.map { it.copy(name = long) }), ArchiveV3IssueCode.LIMIT, "bows[0].name")
        rejected(s.impact { it.copy(repositions = it.repositions.toMutableList().also { r -> r[0] = r[0].copy(reason = long) }) },
            ArchiveV3IssueCode.LIMIT, "sessions[0].ends[0].end.impacts[1].repositions[0].reason")
    }
    @Test fun `budget parameters cannot disable validation with zero or negative values`() {
        assertFailsWith<IllegalArgumentException> { ArchiveV3Limits(maxCollectionElements = 0) }
        assertFailsWith<IllegalArgumentException> { ArchiveV3Limits(maxTotalElements = -1) }
        assertFailsWith<IllegalArgumentException> { ArchiveV3Limits(maxTextLength = 0) }
    }
    @Test fun `late invalid equipment rejects the whole content without a partially accepted aggregate`() {
        val s = d.snapshot()
        for (power in listOf(0f, -0.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) rejected(s.copy(bows = listOf(s.bows!!.single().copy(drawWeightLbs = power))),
            ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "bows[0].drawWeightLbs")
        rejected(s.copy(arrowSets = listOf(s.arrowSets!!.single().copy(name = " "))), ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "arrowSets[0].name")
    }
    @Test fun `identical known content is accepted but received roots never authorize deleting absent roots`() {
        val a = d.accepted(); assertEquals(a, d.accepted(a.toSnapshot(), a))
        val empty = ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(d.accepted(empty, a).sessions.isEmpty()); assertEquals(1, a.sessions.size)
    }
    @Test fun `known empty end configuration cannot be replaced by an explicit different value`() {
        val a = d.accepted(); val changed = a.toSnapshot().end(1) {
            it.copy(configuration = it.configuration!!.copy(distanceM = 70), origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT, null)) }
        d.accepted(changed) // Independently coherent declared history is not authority over known history.
        rejected(changed, ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].ends[1].configuration", a)
    }
    @Test fun `known origin cannot be rewritten even when its configuration and score stay equal`() {
        val a = d.accepted(); val changed = a.toSnapshot().end(1) { it.copy(origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT, null)) }
        rejected(changed, ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[1].origin", a)
    }
    @Test fun `known initial value cannot be replaced even without altering any impact`() {
        val a = d.accepted(); val changed = a.toSnapshot().session { s ->
            s.copy(initialConfiguration = s.initialConfiguration!!.copy(distanceM = 70), ends = s.ends!!.map { e ->
                if (e.origin!!.kind == EndConfigurationOriginKind.INITIAL) e.copy(origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT, null)) else e }) }
        rejected(changed, ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "sessions[0].initialConfiguration", a)
    }
    @Test fun `known impacts and ends cannot be removed even without carrying traces`() {
        val a = d.accepted()
        rejected(a.toSnapshot().impact { it }.end { it.copy(end = it.end!!.copy(impacts = it.end.impacts.filterIndexed { i, _ -> i != 1 })) },
            ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[0].end.impacts", a)
        rejected(a.toSnapshot().session { it.copy(ends = it.ends!!.filterIndexed { i, _ -> i != 1 }) },
            ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends", a)
    }
    @Test fun `known reposition evidence cannot be omitted even when independent reconstruction is coherent`() {
        val a = d.accepted(); val changed = a.toSnapshot().impact { it.copy(repositions = emptyList()) }
        d.accepted(changed)
        rejected(changed, ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[0].end.impacts[1].repositions", a)
    }
    @Test fun `known descendant cannot be adopted by a different session identity`() {
        val a = d.accepted(); val changed = a.toSnapshot().session { it.copy(uuid = d.id(999), ends = it.ends!!.map { e -> e.copy(sessionUuid = d.id(999)) }) }
        rejected(changed, ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0].ends[0]", a)
    }
    @Test fun `known completed aggregate cannot receive even metadata changes`() {
        val a = d.accepted(d.snapshot(true)); val changed = a.toSnapshot().session { it.copy(metadata = it.metadata!!.copy(notes = "changed")) }
        rejected(changed, ArchiveV3IssueCode.HISTORY_CONFLICT, "sessions[0]", a)
    }
    @Test fun `newer timestamps do not choose a winner or authorize a divergent archive revision`() {
        val a = d.accepted(); val changed = a.toSnapshot().session { it.copy(metadata = it.metadata!!.copy(notes = "newer"), updatedAt = d.now.plusSeconds(90).toString()) }
        rejected(changed, ArchiveV3IssueCode.DIVERGENT_REVISION, "sessions[0]", a)
        val bowChanged = a.toSnapshot().copy(bows = a.toSnapshot().bows!!.map { it.copy(name = "New name") })
        rejected(bowChanged, ArchiveV3IssueCode.DIVERGENT_REVISION, "bows[0]", a)
    }
    @Test fun `V2 conversion preserves complete catalogs unused profiles dates editions and homogeneous historical capture`() {
        val v2 = d.v2(); val before = v2.toSnapshot()
        val a = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(v2)).archive
        val restored = roundTrip(a)
        assertEquals(v2.profiles, restored.profiles); assertEquals(v2.bows, restored.bows); assertEquals(v2.arrowSets, restored.arrowSets)
        val old = v2.sessions.single(); val s = restored.sessions.single()
        assertEquals(old.ends, s.ends.map { it.end }); assertEquals(old.date, s.metadata.date)
        assertEquals(old.createdAt, s.createdAt); assertEquals(old.endedAt, s.endedAt)
        assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, s.initialConfigurationOrigin)
        assertTrue(s.ends.all { it.origin == EndConfigurationOrigin.LegacyDerived })
        assertEquals(WA.RULEBOOK_VERSION, s.initialConfiguration.regulatoryEdition)
        assertEquals(before, v2.toSnapshot())
    }
    @Test fun `V2 explicitly empty equipment remains complete and distinct from V1 missing material`() {
        val a = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(d.v2(true))).archive
        assertTrue(roundTrip(a).bows.isEmpty()); assertEquals(d.id(700), a.sessions.single().metadata.bowUuid)
        val partial = convertArchiveV1ToConfiguredContent(d.v1())
        assertEquals(EquipmentAvailability.NOT_PROVIDED, partial.equipmentAvailability)
        assertFalse(DomainArchiveV3::class.java.isInstance(partial))
        assertTrue(partial::class.java.methods.none { it.name == "toSnapshot" })
    }
    @Test fun `V1 conversion stays partial immutable and retains every known profile and opaque reference`() {
        val source = d.v1(); val before = source.toSnapshot(); val partial = convertArchiveV1ToConfiguredContent(source)
        assertEquals(source.profiles, partial.profiles); assertEquals(source.sessions.single().ends, partial.sessions.single().ends.map { it.end })
        assertEquals(source.sessions.single().bowUuid, partial.sessions.single().metadata.bowUuid)
        assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, partial.sessions.single().initialConfigurationOrigin)
        assertFailsWith<UnsupportedOperationException> { (partial.sessions as MutableList).clear() }
        assertEquals(before, source.toSnapshot())
    }

    @Test fun `known custom profile identity cannot be redefined even without geometric score changes`() {
        val a = d.accepted(d.small()); val before = a.toSnapshot()
        val changed = before.copy(profiles = before.profiles!!.map { it.copy(definition = it.definition!!.copy(name = "redefined")) })
        d.accepted(changed)
        rejected(changed, ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, "profiles[0]", a)
    }
    @Test fun `manual and geometry X distinctions must be legal for the actual profile`() {
        val s = d.small()
        rejected(s.impact(0) { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(9, true))) },
            ArchiveV3IssueCode.CONTRADICTORY_DERIVED_VALUE, "sessions[0].ends[0].end.impacts[0].decision.selected")
        rejected(d.snapshot().impact { it.copy(decision = it.decision!!.copy(selected = BoundaryValue(9, true))) },
            ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].ends[0].end.impacts[1].decision")
    }
    @Test fun `all finite legal power bits survive including extreme and adjacent values`() {
        val s = d.small()
        for (power in listOf(Float.MIN_VALUE, Float.MAX_VALUE, 1f, Float.fromBits(1f.toBits() + 1))) {
            val a = roundTrip(d.accepted(s.copy(bows = s.bows!!.map { it.copy(drawWeightLbs = power) })))
            assertEquals(power.toBits(), a.bows.single().drawWeightLbs!!.toBits())
        }
    }
    @Test fun `malformed Unicode is rejected without replacement characters or partial archive`() {
        val s = d.small()
        for (text in listOf("bad\uD800", "\uDC00bad", "\uD800x"))
            rejected(s.session { it.copy(metadata = it.metadata!!.copy(notes = text)) }, ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "sessions[0].metadata.notes")
    }


    @Test fun `new capture cannot assert a draft or prestart position in the session timeline`() {
        val s = d.small()
        rejected(s.session { it.copy(status = SessionStatus.DRAFT, startedAt = null) }, ArchiveV3IssueCode.TEMPORAL_CONTEXT,
            "sessions[0].ends[0].origin")
        rejected(s.session { it.copy(startedAt = d.now.plusSeconds(1).toString(), updatedAt = d.now.plusSeconds(1).toString()) },
            ArchiveV3IssueCode.TEMPORAL_CONTEXT, "sessions[0].ends[0].end.createdAt")
    }
    @Test fun `historical draft and sparse indexes are preserved without invented start events`() {
        val old = d.legacySession(false)
        val historical = Session(old.uuid, old.date, SessionStatus.DRAFT, title = old.title, targetType = old.targetType,
            distanceM = old.distanceM, arrowCountMode = old.arrowCountMode, arrowCountPerEnd = old.arrowCountPerEnd,
            isIndoor = old.isIndoor, scoringMode = old.scoringMode,
            ends = listOf(old.ends.single().copy(index = 7)), createdAt = old.createdAt, updatedAt = old.updatedAt)
        val source = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(d.profiles(), listOf(historical),
            emptyList(), emptyList()))).archive
        val a = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(source)).archive
        val restored = roundTrip(a).sessions.single()
        assertEquals(SessionStatus.DRAFT, restored.status); assertNull(restored.startedAt)
        assertEquals(7, restored.ends.single().end.index); assertEquals(EndConfigurationOrigin.LegacyDerived, restored.ends.single().origin)
    }


    @Test fun `capture cannot substitute a same UUID definition or edition even when geometry is unchanged`() {
        val a = d.accepted(d.small()); val profile = a.profiles.single()
        for (other in listOf(profile.copy(definition = profile.definition!!.copy(name = "silently replaced")),
            profile.copy(regulatoryEdition = "different edition")))
            assertFailsWith<IllegalArgumentException> { snapshotArchiveV3(listOf(other), a.sessions, a.bows, a.arrowSets) }
    }
    @Test fun `capture requires exact initial and effective profiles including empty configurations`() {
        val mixed = d.mixed(); val initial = ConfiguredSession.create(d.id(2), d.metadata(), d.config(), d.now)
        assertFailsWith<IllegalArgumentException> { snapshotArchiveV3(emptyList(), listOf(initial), emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { snapshotArchiveV3(d.profiles().filterNot { it.definition == WA.compound40 },
            listOf(mixed), emptyList(), emptyList()) }
    }

}
