package com.lmarchery.backup

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.feature.sessions.IndoorProfile
import java.time.*
import kotlin.test.assertIs

/** Synthetic only; never populated from a device or a user's SAF document. */
internal object V2TestData {
    val now = Instant.parse("2026-10-18T02:03:04.123456789Z")
    fun id(n: Int) = "60000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    val target = IndoorProfile.RECURVE_40.target
    fun bow() = Bow(id(10), " Arc 🏹 ", drawWeightLbs = Float.fromBits(0x42000001), brand = "", notes = " é\n ", createdAt = now, updatedAt = now)
    fun arrows() = ArrowSet(id(11), " Flèches ", spine = "500 ?", length = "28 pouces", pointWeight = "", createdAt = now, updatedAt = now)
    fun session(): Session {
        val impact = ArrowImpact(id(3), 1, NormalizedPoint(.105f, -0.0f), inputMode = InputMode.MANUAL,
            createdAt = now, updatedAt = now, targetType = target)
        val end = End(id(2), 1, EndStatus.EDITING, listOf(impact), createdAt = now, updatedAt = now)
        return Session(id(1), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic Android V2", targetType = target, distanceM = 18, arrowCountMode = ArrowCountMode.FIXED,
            arrowCountPerEnd = 3, isIndoor = true, scoringMode = ScoringMode.TRAINING,
            bowUuid = id(10), arrowSetUuid = id(11), createdAt = now, updatedAt = now).start(now).withEnd(end)
    }
    fun archive(sessions: Boolean = true, profiles: Boolean = true, equipment: Boolean = true): DomainArchiveV2 =
        assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(
            if (sessions || profiles) listOf(TargetProfileSnapshot(target, "2026-01-27")) else emptyList(),
            if (sessions) listOf(session()) else emptyList(), if (equipment) listOf(bow()) else emptyList(),
            if (equipment) listOf(arrows()) else emptyList()))).archive
}
