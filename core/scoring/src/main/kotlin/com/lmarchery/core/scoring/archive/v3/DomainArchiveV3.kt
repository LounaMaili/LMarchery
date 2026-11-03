package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.*
import java.util.Collections

/** Complete independently validated content. No V1/V2 inheritance, projection, codec or store API. */
class DomainArchiveV3 internal constructor(
    profiles: List<TargetProfileSnapshot>, sessions: List<ConfiguredSession>, bows: List<Bow>, arrowSets: List<ArrowSet>,
) {
    val profiles: List<TargetProfileSnapshot> = Collections.unmodifiableList(profiles.toList())
    val sessions: List<ConfiguredSession> = Collections.unmodifiableList(sessions.toList())
    val bows: List<Bow> = Collections.unmodifiableList(bows.toList())
    val arrowSets: List<ArrowSet> = Collections.unmodifiableList(arrowSets.toList())
    fun toSnapshot(): ArchiveV3Snapshot = snapshotArchiveV3(profiles, sessions, bows, arrowSets)
    override fun equals(other: Any?): Boolean = other is DomainArchiveV3 && profiles == other.profiles &&
        sessions == other.sessions && bows == other.bows && arrowSets == other.arrowSets
    override fun hashCode(): Int = arrayOf(profiles, sessions, bows, arrowSets).contentHashCode()
}

/** Capture, not proof of validity. No current preset, choice date or UUID is invented. */
fun snapshotArchiveV3(profiles: List<TargetProfileSnapshot>, sessions: List<ConfiguredSession>,
    bows: List<Bow>, arrowSets: List<ArrowSet>): ArchiveV3Snapshot {
    sessions.forEachIndexed { index, session ->
        (listOf(session.initialConfiguration) + session.ends.map { it.configuration }).forEach { configuration ->
            require(profiles.singleOrNull { it.definition?.uuid == configuration.targetType.uuid } ==
                TargetProfileSnapshot(configuration.targetType, configuration.regulatoryEdition)) {
                "sessions[$index]: capture requires every exact complete source profile and edition, including the initial value."
            }
        }
    }
    // Only identical shared profile/equipment representations; no configured session is projected into V2.
    val shared = snapshotArchiveV2(profiles, emptyList(), bows, arrowSets)
    return ArchiveV3Snapshot(ArchiveV3Contract.SCHEMA, ArchiveV3Contract.SCORING, shared.profiles,
        sessions.map { s ->
            val m = s.metadata
            ConfiguredSessionSnapshot(s.uuid, ConfiguredMetadataSnapshot(
                CivilDateSnapshot(m.date.localDateTime.toString(), m.date.zoneId.id, m.date.offset.id), m.title,
                m.plannedEndCount, m.bowUuid, m.arrowSetUuid, m.locationName, m.notes),
                s.initialConfiguration.snapshot(), s.initialConfigurationOrigin, s.status,
                s.startedAt?.let(UtcTimestamps::format), s.endedAt?.let(UtcTimestamps::format),
                s.ends.map { captured -> ConfiguredEndSnapshot(captured.sessionUuid, captured.end.toSnapshot(),
                    captured.configuration.snapshot(), captured.origin.snapshot()) },
                UtcTimestamps.format(s.createdAt), UtcTimestamps.format(s.updatedAt))
        }, shared.bows, shared.arrowSets)
}

private fun EndConfiguration.snapshot() = EndConfigurationSnapshot(targetType.uuid, regulatoryEdition,
    distanceM, arrowCountMode, arrowCountPerEnd, isIndoor, scoringMode)

private fun EndConfigurationOrigin.snapshot(): EndConfigurationOriginSnapshot = when (this) {
    EndConfigurationOrigin.Initial -> EndConfigurationOriginSnapshot(EndConfigurationOriginKind.INITIAL, null)
    is EndConfigurationOrigin.Inherited -> EndConfigurationOriginSnapshot(EndConfigurationOriginKind.INHERITED, previousEndUuid)
    EndConfigurationOrigin.Explicit -> EndConfigurationOriginSnapshot(EndConfigurationOriginKind.EXPLICIT, null)
    EndConfigurationOrigin.LegacyDerived -> EndConfigurationOriginSnapshot(EndConfigurationOriginKind.LEGACY_DERIVED, null)
}

enum class EquipmentAvailability { NOT_PROVIDED }

/** Incomplete historical content, deliberately not DomainArchiveV3 and without a V3 snapshot/export. */
class LegacyV1ConfiguredContent internal constructor(profiles: List<TargetProfileSnapshot>, sessions: List<ConfiguredSession>) {
    val profiles: List<TargetProfileSnapshot> = Collections.unmodifiableList(profiles.toList())
    val sessions: List<ConfiguredSession> = Collections.unmodifiableList(sessions.toList())
    val equipmentAvailability = EquipmentAvailability.NOT_PROVIDED
}

fun convertArchiveV1ToConfiguredContent(source: DomainArchive): LegacyV1ConfiguredContent =
    LegacyV1ConfiguredContent(source.profiles, source.sessions.map { s -> ConfiguredSession.fromLegacyArchive(source, s.uuid) })

/** A validated V2 is already complete with explicitly supplied catalogs, even when empty. */
fun convertArchiveV2ToV3(source: DomainArchiveV2, limits: ArchiveV3Limits = ArchiveV3Limits()): ArchiveV3Reconstruction {
    val sessions = source.sessions.map { s ->
        ConfiguredSession.fromValidatedLegacySession(s, source.profiles.single { it.definition == s.targetType })
    }
    return reconstructArchiveV3(snapshotArchiveV3(source.profiles, sessions, source.bows, source.arrowSets), limits = limits)
}
