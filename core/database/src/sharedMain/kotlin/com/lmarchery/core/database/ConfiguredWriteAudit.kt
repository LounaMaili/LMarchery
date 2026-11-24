package com.lmarchery.core.database

import androidx.room.PooledConnection
import com.lmarchery.core.scoring.archive.ArchiveGraph
import com.lmarchery.core.scoring.configured.ConfiguredSession

/** Verify raw SQL values outside the permitted delta, including unchanged captures and profile editions.
 * Hashes include type, length and exact bytes (including embedded NUL), never SQLite quote()/text coercion.
 * Call only after bounded, strict V3 validation, on the same writer connection. */
internal class ConfiguredWriteAudit(before: ArchiveGraph, proposed: ConfiguredSession, legacyProjectionRevision: Boolean = false, adoptingPlace: Boolean = false) {
    private val old = before.sessions.singleOrNull { it.uuid == proposed.uuid }
    init {
        require(!legacyProjectionRevision || (old != null &&
            old.initialConfigurationOrigin == com.lmarchery.core.scoring.configured.InitialConfigurationOrigin.LEGACY_DERIVED &&
            proposed.initialConfigurationOrigin == old.initialConfigurationOrigin &&
            (old.ends + proposed.ends).all { it.origin == com.lmarchery.core.scoring.configured.EndConfigurationOrigin.LegacyDerived })) {
            "Only the validated historical path may revise redundant homogeneous projections."
        }
    }
    private val newIds = sessionIds(proposed) - (old?.let(::sessionIds) ?: emptySet())
    private val newProfiles = (listOf(proposed.initialConfiguration) + proposed.ends.map { it.configuration })
        .map { it.targetType.uuid }.toSet() - before.profiles.map { it.definition!!.uuid }.toSet()
    private val endIds = (old?.ends.orEmpty() + proposed.ends).map { it.uuid }.toSet()
    private val impactIds = (old?.ends.orEmpty() + proposed.ends).flatMap { it.end.impacts }.map { it.uuid }.toSet()
    private val exclusions = linkedMapOf(
        "session_places" to ("sessionUuid" to if (adoptingPlace) setOf(proposed.uuid) else emptySet()),
        "entity_identities" to ("uuid" to newIds + newProfiles),
        "target_profiles" to ("uuid" to newProfiles), "target_zones" to ("profileUuid" to newProfiles),
        "sessions" to ("uuid" to setOf(proposed.uuid)), "ends" to ("sessionUuid" to setOf(proposed.uuid)),
        "impacts" to ("endUuid" to endIds), "score_decisions" to ("impactUuid" to impactIds),
        "impact_repositions" to ("impactUuid" to impactIds),
        "impact_cancellations" to ("impactUuid" to (proposed.ends.flatMap { it.end.cancellations }.map { it.impactUuid }.toSet() -
            old?.ends.orEmpty().flatMap { it.end.cancellations }.map { it.impactUuid }.toSet())),
        "session_configurations" to ("sessionUuid" to if (old == null || legacyProjectionRevision) setOf(proposed.uuid) else emptySet()),
        "end_configurations" to ("endUuid" to if (legacyProjectionRevision) endIds else (endIds - old?.ends.orEmpty().map { it.uuid }.toSet())),
        "bows" to ("uuid" to emptySet()), "arrow_sets" to ("uuid" to emptySet()),
    )
    suspend fun fingerprint(connection: PooledConnection): Map<String, List<Byte>> = fingerprintStorage(connection, exclusions)
    private fun sessionIds(s: ConfiguredSession): Set<String> = buildSet {
        add(s.uuid); s.ends.forEach { add(it.uuid); it.end.impacts.forEach { add(it.uuid) } }
    }
}
