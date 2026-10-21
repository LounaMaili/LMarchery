package com.lmarchery.core.export

import com.lmarchery.core.scoring.archive.v3.*

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.scoring.scoreBoundaries
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertIs

/** Synthetic, fixed identities and time; never device or user data. */
internal object V3CodecFixtures {
    fun golden(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    fun historical(): DomainArchive = assertIs<ArchiveJsonRead.Accepted>(ArchiveJsonCodec().decode(golden("v1-rich-golden.json"))).archive
    fun rich(): DomainArchiveV3 {
        val old = historical()
        val profiles = (profiles() + old.profiles).distinctBy { it.definition!!.uuid }
        val legacy = convertArchiveV1ToConfiguredContent(old).sessions
        val draft = ConfiguredSession.create(id(2), metadata(), config(), now)
        val active = ConfiguredSession.create(id(3), metadata(), config(), now).start(now)
            .createNextEnd(id(20), now)
        return accepted(snapshotArchiveV3(profiles, listOf(mixed(true), draft, active) + legacy,
            listOf(bow(), bow().let { Bow(id(702), "Non associé", drawWeightLbs = Float.MAX_VALUE,
                createdAt = it.createdAt, updatedAt = it.updatedAt) }), listOf(arrows())))
    }
    val now: Instant = Instant.parse("2026-10-25T00:30:00.123456789Z")
    fun id(n: Int) = "c0000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    fun config(target: TargetType = WA.recurve40, distance: Int = 18, count: Int? = 3,
        edition: String? = BuiltInTargetRegistry.find(target.uuid)?.regulatoryEdition) = EndConfiguration(target,
        edition, distance, if (count == null) ArrowCountMode.FREE else ArrowCountMode.FIXED, count, true, ScoringMode.COMPETITION)
    fun metadata() = ConfiguredSessionMetadata(SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")),
        "  Séance synthétique 🏹\n ", 12, id(700), id(701), "", " Notes Unicode é  ")
    fun bow() = Bow(id(700), " Arc 🏹 ", "custom", Float.fromBits(0x3f800001), "", " modèle ", "", now, now.plusNanos(1))
    fun arrows() = ArrowSet(id(701), " Flèches ", "500 / 1916", "28 1/2 pouces", "100 gr?", "", " ", null, "\n", now, now.plusNanos(2))
    fun profiles() = listOf(WA.recurve40, WA.compound40, ClassicTargetFixtures.classic40, WA.compound60).map {
        TargetProfileSnapshot(it, BuiltInTargetRegistry.find(it.uuid)!!.regulatoryEdition)
    }
    fun arrow(n: Int, index: Int, c: EndConfiguration, radius: Float, at: Instant = now) =
        scoreArrowImpact(id(n), index, c.targetType, NormalizedPoint(radius, -0.0f), c.scoringMode, createdAt = at, note = "")
    fun mixed(completed: Boolean = false): ConfiguredSession {
        val c = config()
        val edge = c.targetType.scoreBoundaries().single { it.inner.score == 1 && it.outer.score == 0 }
        val boundary = c.targetType.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
        val traced = arrow(101, 2, c, 1.1f).withFinalScore(c.targetType,
            ScoreDecisionRequest.JudgeBoundary(edge.reference, edge.inner, now.plusSeconds(1)), now.plusSeconds(1))
            .reposition(NormalizedPoint(.105f, -0.0f), now.plusSeconds(2), "Correction après jugement M/1")
            .withFinalScore(c.targetType, ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner,
                now.plusSeconds(3)), now.plusSeconds(3))
            .reposition(NormalizedPoint(.25f, -0.0f), now.plusSeconds(4), "Correction après jugement 10/9")
            .withFinalScore(c.targetType, ScoreDecisionRequest.ExceptionalCorrection(
                com.lmarchery.core.model.scoring.BoundaryValue(9), " Neuf observé ", now.plusSeconds(5)), now.plusSeconds(5))
        var s = ConfiguredSession.create(id(1), metadata(), c, now).start(now).createNextEnd(id(10), now)
            .withImpact(id(10), arrow(100, 1, c, .025f)).withImpact(id(10), arrow(102, 3, c, .075f))
            .withImpact(id(10), traced).validateEnd(id(10), now.plusSeconds(6))
            .createNextEnd(id(11), now.plusSeconds(7))
        val compound = config(WA.compound40, 70, 6)
        s = s.createNextEnd(id(12), now.plusSeconds(8), NextEndConfigurationChoice.Replace(compound))
            .withImpact(id(12), arrow(110, 1, compound, .075f, now.plusSeconds(8)))
            .createNextEnd(id(13), now.plusSeconds(9))
        val free = config(ClassicTargetFixtures.classic40, 18, null)
        s = s.createNextEnd(id(14), now.plusSeconds(10), NextEndConfigurationChoice.Replace(free))
            .withImpact(id(14), arrow(120, 1, free, 0f, now.plusSeconds(10)))
            .withImpact(id(14), arrow(121, 2, free, 1.1f, now.plusSeconds(10)))
            .createNextEnd(id(15), now.plusSeconds(11), NextEndConfigurationChoice.Replace(config(ClassicTargetFixtures.classic40, 70, 5)))
            .validateEnd(id(15), now.plusSeconds(11))
        return if (completed) s.complete(now.plusSeconds(20)) else s
    }
    fun snapshot(completed: Boolean = false) = snapshotArchiveV3(profiles(), listOf(mixed(completed)), listOf(bow()), listOf(arrows()))
    fun accepted(snapshot: ArchiveV3Snapshot = snapshot(), known: DomainArchiveV3? = null) =
        assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(snapshot, known)).archive
    fun small(): ArchiveV3Snapshot {
        val target = WA.recurve40.copy(uuid = id(900), isBuiltIn = false)
        val c = config(target, edition = " source locale ")
        val s = ConfiguredSession.create(id(1), metadata(), c, now).start(now).createNextEnd(id(10), now)
            .withImpact(id(10), arrow(100, 1, c, .15f))
        return snapshotArchiveV3(listOf(TargetProfileSnapshot(target, c.regulatoryEdition)), listOf(s), listOf(bow()), listOf(arrows()))
    }
    fun legacySession(completed: Boolean = true): Session {
        val end = mixed().ends.first().end
        val m = metadata(); val c = config()
        val s = Session(id(1), m.date, SessionStatus.IN_PROGRESS, now, title = m.title, plannedEndCount = m.plannedEndCount,
            targetType = c.targetType, distanceM = c.distanceM, arrowCountMode = c.arrowCountMode, arrowCountPerEnd = c.arrowCountPerEnd,
            isIndoor = c.isIndoor, scoringMode = c.scoringMode, bowUuid = m.bowUuid, arrowSetUuid = m.arrowSetUuid,
            locationName = m.locationName, notes = m.notes, ends = listOf(end), createdAt = now, updatedAt = now.plusSeconds(12))
        return if (completed) s.complete(now.plusSeconds(20)) else s
    }
    fun v2(emptyCatalog: Boolean = false) = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(
        snapshotArchiveV2(profiles(), listOf(legacySession()), if (emptyCatalog) emptyList() else listOf(bow()),
            if (emptyCatalog) emptyList() else listOf(arrows())))).archive
    fun v1() = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(profiles(), listOf(legacySession())))).archive
}
