package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import java.time.ZoneId
import kotlin.test.*

internal object ConfiguredWriteData {
    const val SEED = 6000
    val classic = EndConfiguration(ClassicTargetFixtures.classic40, null, 18, ArrowCountMode.FREE, null, true, ScoringMode.COMPETITION)
    // Resolve the published exact edition rather than guessing it from discipline or diameter.
    val compound: EndConfiguration get() {
        val profile = requireNotNull(com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry.find(WorldArcheryIndoorTargetProfiles.compound40.uuid))
        return EndConfiguration(profile.definition, profile.regulatoryEdition, 70, ArrowCountMode.FIXED, 6, false, ScoringMode.TRAINING)
    }
    fun draft(seed: Int = SEED, configuration: EndConfiguration = classic) = ConfiguredSession.create(f.id(seed),
        ConfiguredSessionMetadata(SessionDateTime.fromInstant(f.now, ZoneId.of("Europe/Paris")), " Synthetic configurable 🏹 ",
            bowUuid = f.id(800), arrowSetUuid = f.id(9999), locationName = "", notes = " \n"), configuration, f.now)
    fun oneEnd(seed: Int = SEED, configuration: EndConfiguration = classic) = draft(seed, configuration).start(f.now)
        .createNextEnd(f.id(seed + 1), f.now.plusSeconds(1))
    fun rich(seed: Int = SEED) = oneEnd(seed).withEnd(f.session(seed - 1).ends.single())
    fun arrow(s: ConfiguredSession, n: Int, radius: Float, seconds: Long = 12): ArrowImpact {
        val end = s.ends.last()
        return ArrowImpact(f.id(SEED + 100 + n), n, NormalizedPoint(radius, -0.0f), inputMode = InputMode.MANUAL,
            createdAt = f.now.plusSeconds(seconds), updatedAt = f.now.plusSeconds(seconds), targetType = end.configuration.targetType)
    }
    fun snapshot(s: ConfiguredSession): ArchiveV3Snapshot {
        val profiles = (listOf(s.initialConfiguration) + s.ends.map { it.configuration })
            .map { TargetProfileSnapshot(it.targetType, it.regulatoryEdition) }.distinctBy { it.definition!!.uuid }
        return snapshotArchiveV3(profiles, listOf(s), emptyList(), emptyList())
    }
    fun reconstructed(snapshot: ArchiveV3Snapshot): ConfiguredSession =
        assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(snapshot)).archive.sessions.single()
    suspend fun stored(store: RoomSessionStore, uuid: String = f.id(SEED)) = store.fullV3().sessions.single { it.uuid == uuid }
    fun conflict(result: ConfiguredWriteResult, kind: ConfiguredWriteConflict) = assertEquals(kind,
        assertIs<ConfiguredWriteResult.Conflict>(result).kind)
}
