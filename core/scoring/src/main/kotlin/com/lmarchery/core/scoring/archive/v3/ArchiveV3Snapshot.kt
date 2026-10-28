package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.InitialConfigurationOrigin

/** Archive schema only. Neither Room 2 nor the published scoring contract is upgraded. */
object ArchiveV3Contract {
    const val SCHEMA = 3
    const val SCORING = 1
}

/** Untrusted transfer. Every constructor argument is explicit; null required values are rejected.
 * Optional null is a semantic value. The future codec must separately require EVERY property key.
 * Lists may be mutable: callers must not mutate a transfer while reconstruction is running. */
data class ArchiveV3Snapshot(
    val schemaVersion: Int?,
    val scoringContractVersion: Int?,
    val profiles: List<TargetProfileSnapshot>?,
    val sessions: List<ConfiguredSessionSnapshot>?,
    val bows: List<BowSnapshot>?,
    val arrowSets: List<ArrowSetSnapshot>?,
)

data class ConfiguredSessionSnapshot(
    val uuid: String?,
    val metadata: ConfiguredMetadataSnapshot?,
    val initialConfiguration: EndConfigurationSnapshot?,
    val initialConfigurationOrigin: InitialConfigurationOrigin?,
    val status: SessionStatus?,
    val startedAt: String?,
    val endedAt: String?,
    val ends: List<ConfiguredEndSnapshot>?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class ConfiguredMetadataSnapshot(
    val date: CivilDateSnapshot?,
    val title: String?,
    val plannedEndCount: Int?,
    val bowUuid: String?,
    val arrowSetUuid: String?,
    val locationName: String?,
    val notes: String?,
)

/** Definition is resolved only from this archive, never from current target defaults. */
data class EndConfigurationSnapshot(
    val targetTypeUuid: String?,
    val regulatoryEdition: String?,
    val distanceM: Int?,
    val arrowCountMode: ArrowCountMode?,
    val arrowCountPerEnd: Int?,
    val isIndoor: Boolean?,
    val scoringMode: ScoringMode?,
)

data class ConfiguredEndSnapshot(
    val sessionUuid: String?,
    val end: EndSnapshot?,
    val configuration: EndConfigurationSnapshot?,
    val origin: EndConfigurationOriginSnapshot?,
)

enum class EndConfigurationOriginKind { INITIAL, INHERITED, EXPLICIT, LEGACY_DERIVED }

data class EndConfigurationOriginSnapshot(
    val kind: EndConfigurationOriginKind?,
    val previousEndUuid: String?,
)
