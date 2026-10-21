package com.lmarchery.core.database

import com.lmarchery.core.scoring.archive.ArchiveSnapshot

/** Legacy columns remain authoritative ONLY for a tagged homogeneous row. They never describe a mixed end. */
internal suspend fun StorageReadQueries.legacyConfiguration(row: SessionRow): SessionConfigurationRow {
    val profile = profile(row.profileUuid) ?: throw CorruptStorage(StorageDiagnostic("profiles[${row.profileUuid}]", "Stored profile is missing."))
    return SessionConfigurationRow(row.uuid, LEGACY_HOMOGENEOUS, "LEGACY_DERIVED",
        StoredEndConfiguration(row.profileUuid, profile.regulatoryEdition, row.distanceM, row.arrowCountMode,
            row.arrowCountPerEnd, row.isIndoor, row.scoringMode))
}

internal suspend fun SessionDao.requireLegacyArchive() {
    val incompatible = nonLegacyConfigurationUuids().firstOrNull()
    dataCheck(incompatible == null, "session_configurations[$incompatible].representation",
        "V1/V2 cannot represent configurable sessions; no incomplete archive or import is permitted.")
    val orphan = orphanConfigurationPaths().firstOrNull()
    dataCheck(orphan == null, orphan ?: "configurations", "Configuration has no physical owner or predecessor.")
}

internal suspend fun requireLegacyCapture(queries: StorageReadQueries, configurations: ConfigurationReadQueries, row: SessionRow) {
    val stored = configurations.sessionConfiguration(row.uuid)
    dataCheck(stored != null, "session_configurations[${row.uuid}]", "Initial configuration is missing; no fallback to legacy columns.")
    dataCheck(stored!!.representation == LEGACY_HOMOGENEOUS, "session_configurations[${row.uuid}].representation",
        "Historical APIs cannot read or replace a configurable session.")
    dataCheck(row.archiveSchemaVersion == 1 && row.scoringContractVersion == 1, "sessions[${row.uuid}].versions",
        "Unsupported historical row representation or scoring contract.")
    val expected = queries.legacyConfiguration(row)
    dataCheck(stored == expected, "session_configurations[${row.uuid}]", "Derived initial configuration contradicts its exact legacy source/profile edition.")
    queries.ends(row.uuid).forEach { end ->
        dataCheck(configurations.endConfiguration(end.uuid) == EndConfigurationRow(end.uuid, row.uuid,
            "LEGACY_DERIVED", null, expected.configuration), "end_configurations[${end.uuid}]",
            "Derived end capture is missing or contradicts its owner or complete historical configuration.")
    }
}

internal suspend fun SessionDao.sessionSnapshot(row: SessionRow): ArchiveSnapshot {
    requireLegacyCapture(this, this, row)
    return legacySessionSnapshot(row)
}

/** Keep the pre-existing homogeneous revision contract. This is a redundant legacy projection,
 * not permission to edit any captured CONFIGURED end. No-op revisions issue no capture UPDATE. */
internal suspend fun SessionDao.syncLegacyConfigurations(previous: SessionRows, proposed: SessionRows) {
    val initial = legacyConfiguration(proposed.session)
    val old = sessionConfiguration(previous.session.uuid)
    dataCheck(old?.representation == LEGACY_HOMOGENEOUS, "session_configurations[${previous.session.uuid}]",
        "Legacy writer cannot modify a configurable session or invent a missing capture.")
    if (old != initial) dataCheck(updateSessionConfiguration(initial) == 1, "session_configurations[${initial.sessionUuid}]", "Capture update failed.")
    val oldEndUuids = previous.ends.map { it.uuid }.toSet()
    proposed.ends.forEach { end ->
        val next = EndConfigurationRow(end.uuid, proposed.session.uuid, "LEGACY_DERIVED", null, initial.configuration)
        if (end.uuid !in oldEndUuids) insertEndConfiguration(next)
        else if (endConfiguration(end.uuid) != next) dataCheck(updateEndConfiguration(next) == 1,
            "end_configurations[${end.uuid}]", "Capture update failed.")
    }
}
