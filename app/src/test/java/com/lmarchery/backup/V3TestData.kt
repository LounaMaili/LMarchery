package com.lmarchery.backup

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.*
import java.time.*
import kotlin.test.assertIs

/** Fully synthetic data for Android JVM coordinators; not device/SAF-provider tests. */
internal object V3TestData {
    val now = Instant.parse("2026-10-23T01:02:03.123456789Z")
    fun id(n: Int) = "42000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    val classic = EndConfiguration(ClassicTargetFixtures.classic40, null, 18, ArrowCountMode.FIXED, 3, true, ScoringMode.COMPETITION)
    val compound = EndConfiguration(WorldArcheryIndoorTargetProfiles.compound40, "2026-01-27", 70, ArrowCountMode.FIXED, 6, false, ScoringMode.TRAINING)
    fun mixed(completed: Boolean = false): ConfiguredSession {
        var s = ConfiguredSession.create(id(1), ConfiguredSessionMetadata(
            SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), " Synthétique 🏹 V3 ",
            bowUuid = V2TestData.bow().uuid, arrowSetUuid = id(999), notes = " é\n "), classic, now).start(now)
            .createNextEnd(id(2), now.plusSeconds(1))
        val impact = ArrowImpact(id(10), 1, NormalizedPoint(.105f, -0.0f), inputMode = InputMode.MANUAL,
            createdAt = now.plusSeconds(2), updatedAt = now.plusSeconds(2), targetType = classic.targetType)
        val boundary = classic.targetType.scoreBoundaries().first { it.reference.type == BoundaryType.SCORE_ZONES }
        s = s.withImpact(id(2), impact).decideImpact(id(2), id(10),
            ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner, now.plusSeconds(3)), now.plusSeconds(3))
            .repositionImpact(id(2), id(10), NormalizedPoint(.25f, 0f), now.plusSeconds(4), " Motif déplacement ")
            .decideImpact(id(2), id(10), ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), " Motif score ", now.plusSeconds(5)), now.plusSeconds(5))
        s = s.withImpact(id(2), ArrowImpact(id(11), 2, NormalizedPoint(0f, 0f), inputMode = InputMode.MANUAL,
            createdAt = now.plusSeconds(6), updatedAt = now.plusSeconds(6), targetType = classic.targetType))
            .withImpact(id(2), ArrowImpact(id(12), 3, NormalizedPoint(1.1f, 0f), inputMode = InputMode.MANUAL,
                createdAt = now.plusSeconds(7), updatedAt = now.plusSeconds(7), targetType = classic.targetType))
            .validateEnd(id(2), now.plusSeconds(8)).createNextEnd(id(3), now.plusSeconds(9), NextEndConfigurationChoice.Replace(compound))
            .withImpact(id(3), ArrowImpact(id(13), 1, NormalizedPoint(.075f, 0f), inputMode = InputMode.AUTOMATIC,
                createdAt = now.plusSeconds(10), updatedAt = now.plusSeconds(10), targetType = compound.targetType))
            .createNextEnd(id(4), now.plusSeconds(11))
        return if (completed) s.complete(now.plusSeconds(12)) else s
    }
    fun accepted(snapshot: ArchiveV3Snapshot) = assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(snapshot)).archive
    fun archive(completed: Boolean = false): DomainArchiveV3 = accepted(snapshotArchiveV3(listOf(
        TargetProfileSnapshot(classic.targetType, null), TargetProfileSnapshot(compound.targetType, compound.regulatoryEdition),
        TargetProfileSnapshot(WorldArcheryIndoorTargetProfiles.recurve60, "2026-01-27")).sortedBy { it.definition!!.uuid },
        listOf(mixed(completed)), listOf(V2TestData.bow()), listOf(V2TestData.arrows())))
    fun empty() = accepted(ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), emptyList(), emptyList()))
}
