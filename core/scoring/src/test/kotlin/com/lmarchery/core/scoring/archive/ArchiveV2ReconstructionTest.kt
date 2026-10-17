package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ArchiveV2ReconstructionTest {
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private val target = ClassicTargetFixtures.classic40
    private fun id(n: Int) = "90000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun bow(n: Int = 100, power: Float? = Math.nextUp(35.5f)) = Bow(id(n), " Arc 東京 🏹 ", "custom / classique", power,
        "", " modèle ", "ligne\n\t<&>", now, now.plusNanos(1))
    private fun arrows(n: Int = 101) = ArrowSet(id(n), " Flèches 🎯 ", "500 / 1916", "28 1/2 pouces", "100 gr?",
        "", " ", "bois/carbone", "\n", now, now.plusNanos(2))
    private fun session() = Session(id(1), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")),
        SessionStatus.IN_PROGRESS, startedAt = now, title = "Synthetic V2", targetType = target, distanceM = 18,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
        bowUuid = id(100), arrowSetUuid = id(101), ends = listOf(End(id(2), 1, EndStatus.EDITING,
            listOf(ArrowImpact(id(3), 1, NormalizedPoint(.105f, -0.0f), inputMode = InputMode.MANUAL,
                createdAt = now.plusSeconds(2), updatedAt = now.plusSeconds(2), targetType = target)),
            createdAt = now.plusSeconds(1), updatedAt = now.plusSeconds(2))), createdAt = now, updatedAt = now.plusSeconds(2))
    private fun snapshot() = snapshotArchiveV2(listOf(TargetProfileSnapshot(target, null)), listOf(session()), listOf(bow()), listOf(arrows()))
    private fun accepted(value: ArchiveV2Snapshot = snapshot()) = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(value)).archive
    private fun refused(value: ArchiveV2Snapshot, code: ArchiveIssueCode, path: String) {
        val result = assertIs<ArchiveV2Reconstruction.NeedsIntervention>(reconstructArchiveV2(value))
        assertSame(value, result.source)
        assertEquals(code, result.issue.code)
        assertEquals(path, result.issue.path)
        assertTrue(result.issue.detail.isNotBlank())
    }

    @Test fun `complete V2 capture reconstructs every domain value without changing session semantics`() {
        val source = snapshot()
        val archive = accepted(source)
        assertEquals(source, archive.toSnapshot())
        assertEquals(listOf(session()), archive.sessions)
        assertEquals(listOf(bow()), archive.bows)
        assertEquals(listOf(arrows()), archive.arrowSets)
        assertEquals(9, archive.sessions.single().ends.single().impacts.single().scoreFinal)
        assertEquals(archive, accepted(archive.toSnapshot()))
        assertEquals(archive.hashCode(), accepted(archive.toSnapshot()).hashCode())
    }
    @Test fun `equipment-only V2 and explicitly empty catalogs are valid independent archives`() {
        val equipment = accepted(snapshot().copy(profiles = emptyList(), sessions = emptyList()))
        assertTrue(equipment.sessions.isEmpty()); assertTrue(equipment.profiles.isEmpty())
        assertEquals(listOf(bow()), equipment.bows)
        val empty = accepted(ArchiveV2Snapshot(2, 1, emptyList(), emptyList(), emptyList(), emptyList()))
        assertTrue(empty.bows.isEmpty()); assertTrue(empty.arrowSets.isEmpty())
    }
    @Test fun `missing root lists are never replaced by empty values`() {
        val source = snapshot()
        listOf("profiles" to source.copy(profiles = null), "sessions" to source.copy(sessions = null),
            "bows" to source.copy(bows = null), "arrowSets" to source.copy(arrowSets = null)).forEach { (path, value) ->
            refused(value, ArchiveIssueCode.MISSING_FIELD, path)
        }
    }
    @Test fun `schema and scoring versions remain distinct and strictly checked`() {
        listOf(0, 1, 3, 99).forEach { refused(snapshot().copy(schemaVersion = it), ArchiveIssueCode.UNSUPPORTED_VERSION, "schemaVersion") }
        listOf(0, 2, 99).forEach { refused(snapshot().copy(scoringContractVersion = it), ArchiveIssueCode.UNSUPPORTED_VERSION, "scoringContractVersion") }
        refused(snapshot().copy(schemaVersion = null), ArchiveIssueCode.MISSING_FIELD, "schemaVersion")
        refused(snapshot().copy(scoringContractVersion = null), ArchiveIssueCode.MISSING_FIELD, "scoringContractVersion")
        assertEquals(1, ArchiveContractVersions.SCHEMA)
        assertEquals(1, ArchiveContractVersions.SCORING)
    }
    @Test fun `required equipment fields have typed precise missing-field diagnostics`() {
        val source = snapshot(); val b = source.bows!!.single(); val a = source.arrowSets!!.single()
        listOf("uuid" to b.copy(uuid = null), "name" to b.copy(name = null), "createdAt" to b.copy(createdAt = null),
            "updatedAt" to b.copy(updatedAt = null)).forEach { (field, value) ->
            refused(source.copy(bows = listOf(value)), ArchiveIssueCode.MISSING_FIELD, "bows[0].$field")
        }
        listOf("uuid" to a.copy(uuid = null), "name" to a.copy(name = null), "createdAt" to a.copy(createdAt = null),
            "updatedAt" to a.copy(updatedAt = null)).forEach { (field, value) ->
            refused(source.copy(arrowSets = listOf(value)), ArchiveIssueCode.MISSING_FIELD, "arrowSets[0].$field")
        }
    }
    @Test fun `blank names and invalid canonical UUIDs are refused for each material kind`() {
        val source = snapshot(); val b = source.bows!!.single(); val a = source.arrowSets!!.single()
        listOf("", " \t\n").forEach {
            refused(source.copy(bows = listOf(b.copy(name = it))), ArchiveIssueCode.INVALID_DOMAIN_VALUE, "bows[0].name")
            refused(source.copy(arrowSets = listOf(a.copy(name = it))), ArchiveIssueCode.INVALID_DOMAIN_VALUE, "arrowSets[0].name")
        }
        listOf("", "bad", "1-1-4-8-1", id(100).replace("4000", "1000"), id(100).replace("8000", "0000"),
            "AAAAAAAA-0000-4000-8000-000000000001").forEach {
            refused(source.copy(bows = listOf(b.copy(uuid = it))), ArchiveIssueCode.INVALID_UUID, "bows[0].uuid")
            refused(source.copy(arrowSets = listOf(a.copy(uuid = it))), ArchiveIssueCode.INVALID_UUID, "arrowSets[0].uuid")
        }
    }
    @Test fun `bow identity cannot collide with profile session end or impact`() {
        val source = snapshot(); val b = source.bows!!.single()
        listOf(target.uuid, id(1), id(2), id(3)).forEach {
            refused(source.copy(bows = listOf(b.copy(uuid = it))), ArchiveIssueCode.DUPLICATE_UUID, "bows[0].uuid")
        }
    }
    @Test fun `arrow identity cannot collide with profile impact or bow and duplicates are not deduplicated`() {
        val source = snapshot(); val b = source.bows!!.single(); val a = source.arrowSets!!.single()
        listOf(target.uuid, id(3), b.uuid).forEach {
            refused(source.copy(arrowSets = listOf(a.copy(uuid = it))), ArchiveIssueCode.DUPLICATE_UUID, "arrowSets[0].uuid")
        }
        refused(source.copy(bows = listOf(b, b)), ArchiveIssueCode.DUPLICATE_UUID, "bows[1].uuid")
        refused(source.copy(arrowSets = listOf(a, a)), ArchiveIssueCode.DUPLICATE_UUID, "arrowSets[1].uuid")
    }
    @Test fun `published profile UUID remains reserved even in equipment-only archives`() {
        val source = snapshot()
        refused(source.copy(profiles = emptyList(), sessions = emptyList(), bows = listOf(source.bows!!.single().copy(uuid = target.uuid))),
            ArchiveIssueCode.DUPLICATE_UUID, "bows[0].uuid")
    }
    @Test fun `known profile contradictions missing profiles and duplicate session identities remain rejected`() {
        val source = snapshot()
        refused(source.copy(profiles = listOf(TargetProfileSnapshot(target.copy(name = "Tampered"), null))),
            ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, "profiles[0]")
        refused(source.copy(profiles = emptyList()), ArchiveIssueCode.UNKNOWN_PROFILE, "sessions[0].targetTypeUuid")
        refused(source.copy(sessions = source.sessions!! + source.sessions), ArchiveIssueCode.DUPLICATE_UUID, "sessions[1].uuid")
    }
    @Test fun `power remains optional but supplied zero negative and nonfinite values are invalid`() {
        val source = snapshot(); val b = source.bows!!.single()
        listOf(0f, -0.0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            refused(source.copy(bows = listOf(b.copy(drawWeightLbs = it))), ArchiveIssueCode.INVALID_DOMAIN_VALUE, "bows[0].drawWeightLbs")
        }
        assertNull(accepted(source.copy(bows = listOf(b.copy(drawWeightLbs = null)))).bows.single().drawWeightLbs)
    }
    @Test fun `positive Float extrema and adjacent values retain their exact raw bits`() {
        val source = snapshot()
        listOf(Float.MIN_VALUE, Math.nextUp(Float.MIN_VALUE), Math.nextDown(35.5f), 35.5f, Math.nextUp(35.5f), Float.MAX_VALUE).forEach {
            val archive = accepted(source.copy(bows = listOf(source.bows!!.single().copy(drawWeightLbs = it))))
            assertEquals(it.toRawBits(), archive.bows.single().drawWeightLbs!!.toRawBits())
            assertEquals(it.toRawBits(), archive.toSnapshot().bows!!.single().drawWeightLbs!!.toRawBits())
        }
    }
    @Test fun `malformed naive unknown-offset and impossible dates have exact temporal paths`() {
        val source = snapshot(); val b = source.bows!!.single(); val a = source.arrowSets!!.single()
        listOf("", "2026-10-25T00:00:00", "2026-02-30T00:00:00Z", "2026-10-25T00:00:00-00:00", "not-time").forEach {
            refused(source.copy(bows = listOf(b.copy(createdAt = it))), ArchiveIssueCode.TEMPORAL_CONTEXT, "bows[0].createdAt")
            refused(source.copy(arrowSets = listOf(a.copy(updatedAt = it))), ArchiveIssueCode.TEMPORAL_CONTEXT, "arrowSets[0].updatedAt")
        }
        refused(source.copy(bows = listOf(b.copy(updatedAt = UtcTimestamps.format(now.minusNanos(1))))),
            ArchiveIssueCode.TEMPORAL_CONTEXT, "bows[0].updatedAt")
        refused(source.copy(arrowSets = listOf(a.copy(updatedAt = UtcTimestamps.format(now.minusNanos(1))))),
            ArchiveIssueCode.TEMPORAL_CONTEXT, "arrowSets[0].updatedAt")
    }
    @Test fun `explicit offsets become the same Instant without losing nanoseconds or editing transfer source`() {
        val source = snapshot(); val b = source.bows!!.single()
        val received = source.copy(bows = listOf(b.copy(createdAt = "2026-10-25T02:00:00.123456789+02:00")))
        assertEquals(now, accepted(received).bows.single().createdAt)
        assertEquals(123456790, accepted(received).bows.single().updatedAt.nano)
        assertEquals("2026-10-25T02:00:00.123456789+02:00", received.bows!!.single().createdAt)
        assertEquals(UtcTimestamps.format(now), accepted(received).toSnapshot().bows!!.single().createdAt)
        assertEquals(now, accepted(source.copy(bows = listOf(b.copy(updatedAt = b.createdAt)))).bows.single().updatedAt)
    }
    @Test fun `opaque unresolved references are preserved without association existence or type checks`() {
        val source = snapshot(); val old = source.sessions!!.single()
        val received = source.copy(sessions = listOf(old.copy(bowUuid = id(999), arrowSetUuid = bow().uuid)))
        val restored = accepted(received).sessions.single()
        assertEquals(id(999), restored.bowUuid)
        assertEquals(bow().uuid, restored.arrowSetUuid)
        val absent = accepted(received.copy(bows = emptyList(), arrowSets = emptyList())).sessions.single()
        assertEquals(restored, absent)
    }
    @Test fun `all optional strings remain verbatim and do not infer units`() {
        val archive = accepted()
        assertEquals("", archive.bows.single().brand)
        assertEquals(" modèle ", archive.bows.single().model)
        assertEquals("500 / 1916", archive.arrowSets.single().spine)
        assertEquals("28 1/2 pouces", archive.arrowSets.single().length)
        assertEquals("100 gr?", archive.arrowSets.single().pointWeight)
        assertEquals(" ", archive.arrowSets.single().vanes)
        assertEquals("", archive.arrowSets.single().nock)
    }
    @Test fun `validated archive owns nonmodifiable collections independently of untrusted lists`() {
        val source = snapshot(); val bows = source.bows!!.toMutableList(); val arrows = source.arrowSets!!.toMutableList()
        val profiles = source.profiles!!.toMutableList(); val sessions = source.sessions!!.toMutableList()
        val archive = accepted(source.copy(profiles = profiles, sessions = sessions, bows = bows, arrowSets = arrows))
        bows.clear(); arrows.clear(); profiles.clear(); sessions.clear()
        assertEquals(1, archive.bows.size); assertEquals(1, archive.arrowSets.size)
        assertEquals(1, archive.profiles.size); assertEquals(1, archive.sessions.size)
        assertFailsWith<UnsupportedOperationException> { (archive.bows as MutableList<Bow>).clear() }
        assertFailsWith<UnsupportedOperationException> { (archive.arrowSets as MutableList<ArrowSet>).clear() }
    }
    @Test fun `late invalid equipment or contradictory geometry never exposes a partial accepted archive`() {
        val source = snapshot(); val a = source.arrowSets!!.single()
        refused(source.copy(arrowSets = listOf(a, a.copy(uuid = id(102), name = " "))),
            ArchiveIssueCode.INVALID_DOMAIN_VALUE, "arrowSets[1].name")
        val session = source.sessions!!.single(); val end = session.ends.single(); val arrow = end.impacts.single()
        val bad = arrow.copy(decision = arrow.decision!!.copy(assertions = arrow.decision.assertions!!.copy(scoreFinal = 99)))
        refused(source.copy(sessions = listOf(session.copy(ends = listOf(end.copy(impacts = listOf(bad)))))),
            ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE, "sessions[0].ends[0].impacts[0].decision.assertions")
    }
}
