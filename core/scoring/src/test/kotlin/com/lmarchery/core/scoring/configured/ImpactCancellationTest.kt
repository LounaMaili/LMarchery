package com.lmarchery.core.scoring.configured

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ImpactCancellationTest {
    private val at = Instant.parse("2026-11-15T12:00:00.123456789Z")
    private fun id(n: Int) = "cf000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private val profile = BuiltInTargetRegistry.all.first()
    private fun configuration(mode: ArrowCountMode = ArrowCountMode.FIXED, count: Int? = 3) =
        EndConfiguration(profile.definition, profile.regulatoryEdition, 18, mode, count, true, ScoringMode.TRAINING)
    private fun active(c: EndConfiguration = configuration()) = ConfiguredSession.create(id(1),
        ConfiguredSessionMetadata(SessionDateTime.fromInstant(at, ZoneId.of("Europe/Paris")), "Synthetic cancellation"), c, at)
        .start(at).createNextEnd(id(2), at)
    private fun arrow(index: Int, radius: Float = 0f) = scoreArrowImpact(id(10 + index), index, profile.definition,
        NormalizedPoint(radius, -0.0f), ScoringMode.TRAINING, createdAt = at)
    private fun populated(c: EndConfiguration = configuration()): ConfiguredSession {
        var s = active(c)
        for (i in 1..3) s = s.withImpact(id(2), arrow(i, if (i == 3) 1.1f else 0f))
        return s
    }
    private fun archive(s: ConfiguredSession) = assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(
        snapshotArchiveV4(listOf(TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), listOf(s), emptyList(), emptyList()))).archive

    @Test fun `cancellation preserves exact impact and is terminal unique and immutable`() {
        val old = populated(); val next = old.cancelImpact(id(2), id(12), at.plusSeconds(1), " \n🏹 ")
        val before = old.ends.single().end; val end = next.ends.single().end
        assertEquals(before.impacts, end.impacts); assertEquals(listOf(1, 3), end.activeImpacts.map { it.index })
        assertEquals(" \n🏹 ", end.cancellations.single().comment)
        assertEquals(before.uuid, end.cancellations.single().endUuid)
        assertEquals(ImpactCancellationReason.SAISIE_PAR_ERREUR, end.cancellations.single().reason)
        assertFailsWith<IllegalArgumentException> { next.cancelImpact(id(2), id(12), at.plusSeconds(2)) }
        assertFailsWith<IllegalArgumentException> { end.copy(cancellations = emptyList()) }
        assertFailsWith<IllegalArgumentException> { end.copy(cancellations = end.cancellations + end.cancellations) }
        assertFailsWith<IllegalArgumentException> { next.withImpact(id(2), arrow(2).copy(note = "changed", updatedAt = at.plusSeconds(2))) }
        assertFailsWith<UnsupportedOperationException> { (end.cancellations as MutableList).clear() }
        assertTrue(old.ends.single().end.cancellations.isEmpty())
    }

    @Test fun `manual decisions and reposition chains are preserved without accepting geometry`() {
        val old = populated().decideImpact(id(2), id(11), ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "manual", at), at)
            .repositionImpact(id(2), id(11), NormalizedPoint(.25f, 0f), at.plusSeconds(1), "move")
            .decideImpact(id(2), id(11), ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "second", at.plusSeconds(2)), at.plusSeconds(2))
        val impact = old.ends.single().end.impacts.first()
        val next = old.cancelImpact(id(2), impact.uuid, at.plusSeconds(3))
        assertEquals(impact, next.ends.single().end.impacts.first())
        assertEquals(1, impact.repositions.size)
        assertEquals(9, impact.scoreFinal)
    }

    @Test fun `validated end completed session wrong owner and backdating are refused`() {
        val s = populated()
        assertFailsWith<IllegalArgumentException> { s.validateEnd(id(2), at).cancelImpact(id(2), id(11), at) }
        assertFailsWith<IllegalArgumentException> { s.complete(at).cancelImpact(id(2), id(11), at) }
        assertFailsWith<IllegalArgumentException> { s.cancelImpact(id(999), id(11), at) }
        assertFailsWith<IllegalArgumentException> { s.cancelImpact(id(2), id(999), at) }
        assertFailsWith<IllegalArgumentException> { s.cancelImpact(id(2), id(11), at.minusNanos(1)) }
    }

    @Test fun `snapshot validity cannot authorize a cancellation predating the complete expected revision`() {
        val s = populated()
        val expected = s.withMetadata(s.metadata.copy(notes = "later metadata"), at.plusSeconds(10))
        val end = s.ends.single().end.cancelImpact(id(11), at.plusSeconds(1))
        val received = ConfiguredSession.rehydrate(expected.uuid, expected.metadata, expected.initialConfiguration,
            expected.initialConfigurationOrigin, expected.status, expected.startedAt, expected.endedAt,
            listOf(ConfiguredEnd(expected.uuid, end, expected.initialConfiguration, EndConfigurationOrigin.Initial)),
            expected.createdAt, expected.updatedAt)
        assertFailsWith<ConfiguredSessionException> { validateConfiguredSessionUpdate(expected, received) }
        fun historical(e: End) = Session(uuid = expected.uuid, date = expected.metadata.date, status = expected.status,
            startedAt = expected.startedAt, title = expected.metadata.title, targetType = profile.definition,
            distanceM = 18, arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true,
            scoringMode = ScoringMode.TRAINING, ends = listOf(e), createdAt = at, updatedAt = expected.updatedAt)
        val old = historical(s.ends.single().end)
        assertFailsWith<IllegalArgumentException> { validateSessionUpdate(old, historical(end)) }
        assertFailsWith<IllegalArgumentException> { old.copy(ends = listOf(end)) }
        validateConfiguredSessionUpdate(expected, expected.cancelImpact(id(2), id(11), at.plusSeconds(10)))
    }

    @Test fun `fixed capacity counts active arrows while historical indexes never change or repeat`() {
        var s = populated().cancelImpact(id(2), id(12), at)
        val e = s.ends.single().end
        assertEquals(4, e.nextImpactIndex()); assertEquals(2, e.activeImpacts.size)
        s = s.withImpact(id(2), arrow(e.nextImpactIndex()))
        assertEquals(listOf(1, 2, 3, 4), s.ends.single().end.impacts.map { it.index })
        assertEquals(3, s.ends.single().end.activeImpacts.size)
        assertEquals(30L, summarizeConfiguredSession(s).totals.recorded.recordedCapacityMaximum)
        val huge = End(id(3), 1, EndStatus.EDITING, listOf(arrow(Int.MAX_VALUE)), createdAt = at, updatedAt = at)
        assertFailsWith<ArithmeticException> { huge.nextImpactIndex() }
    }

    @Test fun `miss remains active and free denominator ignores cancellation not miss`() {
        val s = populated(configuration(ArrowCountMode.FREE, null)).cancelImpact(id(2), id(12), at)
        val totals = summarizeConfiguredSession(s).totals.recorded
        assertEquals(2L, totals.impactCount); assertEquals(1L, totals.missCount)
        assertEquals(20L, totals.recordedCapacityMaximum); assertEquals(10L, totals.score)
        assertEquals(10, calculateEndScore(s.ends.single().end))
        val empty = s.cancelImpact(id(2), id(11), at).cancelImpact(id(2), id(13), at)
        assertNull(summarizeConfiguredSession(empty).totals.recorded.percentageOfRecordedCapacity)
    }

    @Test fun `v4 round trip retains all values while published snapshots refuse loss`() {
        val s = populated().cancelImpact(id(2), id(12), at.plusNanos(2), "")
        val a = archive(s)
        assertEquals(a, assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(a.toSnapshot())).archive)
        assertFailsWith<IllegalArgumentException> { snapshotArchiveV3(a.profiles, a.sessions, a.bows, a.arrowSets) }
        val legacy = a.sessions.single()
        assertFalse(planArchiveImportV4(archive(populated()), a).canImport)
        assertFalse(planArchiveImportV4(a, archive(populated())).canImport)
        assertTrue(planArchiveImportV4(a, a).canImport)
        assertEquals(legacy, a.sessions.single())
    }

    @Test fun `missing duplicate foreign and chronologically invalid evidence are rejected entirely`() {
        val source = archive(populated().cancelImpact(id(2), id(12), at)).toSnapshot()
        val proof = source.cancellations!!.single()
        for (bad in listOf(source.copy(cancellations = null), source.copy(cancellations = listOf(proof, proof)),
            source.copy(cancellations = listOf(proof.copy(endUuid = id(999)))),
            source.copy(cancellations = listOf(proof.copy(cancelledAt = at.plusSeconds(5).toString()))),
            source.copy(cancellations = listOf(proof.copy(comment = "\uD800"))))) {
            val r = assertIs<ArchiveV4Reconstruction.NeedsIntervention>(reconstructArchiveV4(bad))
            assertTrue(r.issue.path.isNotBlank())
        }
    }

    @Test fun `evidence cannot be rewritten and other historical arrows cannot be renumbered`() {
        val end = populated().cancelImpact(id(2), id(12), at).ends.single().end
        val old = end.impacts[1]
        val changed = ImpactCancellation.forImpact(end.uuid, old, at, comment = "rewritten")
        assertFailsWith<IllegalArgumentException> { end.copy(cancellations = listOf(changed)) }
        assertFailsWith<IllegalArgumentException> { end.withImpact(end.impacts.first().copy(index = 7)) }
        val twice = end.cancelImpact(id(11), at.plusSeconds(1))
        val source = archive(populated().withEnd(twice)).toSnapshot()
        assertIs<ArchiveV4Reconstruction.NeedsIntervention>(reconstructArchiveV4(source.copy(cancellations = source.cancellations!!.reversed())))
    }

    @Test fun `a declared historical draft cannot contain an invented cancellation`() {
        val source = archive(populated().cancelImpact(id(2), id(12), at)).toSnapshot()
        val old = source.sessions!!.single()
        val fake = old.copy(status = SessionStatus.DRAFT, startedAt = null, initialConfigurationOrigin = InitialConfigurationOrigin.LEGACY_DERIVED,
            ends = old.ends!!.map { it.copy(origin = EndConfigurationOriginSnapshot(EndConfigurationOriginKind.LEGACY_DERIVED, null)) })
        assertIs<ArchiveV4Reconstruction.NeedsIntervention>(reconstructArchiveV4(source.copy(sessions = listOf(fake))))
    }

    @Test fun `X nine maximum and miss counts use only active impacts while original scores never change`() {
        val target = com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures.classic40
        val c = EndConfiguration(target, null, 70, ArrowCountMode.FREE, null, false, ScoringMode.TRAINING)
        var s = active(c)
        for ((i, radius) in listOf(0f, .15f, 1.1f).withIndex()) s = s.withImpact(id(2),
            scoreArrowImpact(id(100 + i), i + 1, target, NormalizedPoint(radius, 0f), ScoringMode.TRAINING, createdAt = at))
        val before = s.ends.single().end.impacts
        val all = summarizeConfiguredSession(s).totals.recorded
        assertEquals(1L, all.xCount); assertEquals(1L, all.maxScoreCount); assertEquals(1L, all.nineCount); assertEquals(1L, all.missCount)
        s = s.cancelImpact(id(2), id(100), at).cancelImpact(id(2), id(102), at)
        val remaining = summarizeConfiguredSession(s).totals.recorded
        assertEquals(0L, remaining.xCount); assertEquals(0L, remaining.maxScoreCount); assertEquals(0L, remaining.missCount)
        assertEquals(1L, remaining.nineCount); assertEquals(9.0, remaining.averagePerArrow)
        assertEquals(before, s.ends.single().end.impacts)
    }
}
