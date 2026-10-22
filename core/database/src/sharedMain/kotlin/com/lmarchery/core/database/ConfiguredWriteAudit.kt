package com.lmarchery.core.database

import androidx.room.PooledConnection
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
import com.lmarchery.core.scoring.configured.ConfiguredSession

/** Verify raw SQL values outside the permitted delta, including unchanged captures and profile editions.
 * Hashes include type, length and exact bytes (including embedded NUL), never SQLite quote()/text coercion.
 * Call only after bounded, strict V3 validation, on the same writer connection. */
internal class ConfiguredWriteAudit(before: DomainArchiveV3, proposed: ConfiguredSession) {
    private val old = before.sessions.singleOrNull { it.uuid == proposed.uuid }
    private val newIds = sessionIds(proposed) - (old?.let(::sessionIds) ?: emptySet())
    private val newProfiles = (listOf(proposed.initialConfiguration) + proposed.ends.map { it.configuration })
        .map { it.targetType.uuid }.toSet() - before.profiles.map { it.definition!!.uuid }.toSet()
    private val endIds = (old?.ends.orEmpty() + proposed.ends).map { it.uuid }.toSet()
    private val impactIds = (old?.ends.orEmpty() + proposed.ends).flatMap { it.end.impacts }.map { it.uuid }.toSet()
    private val exclusions = linkedMapOf(
        "entity_identities" to ("uuid" to newIds + newProfiles),
        "target_profiles" to ("uuid" to newProfiles), "target_zones" to ("profileUuid" to newProfiles),
        "sessions" to ("uuid" to setOf(proposed.uuid)), "ends" to ("sessionUuid" to setOf(proposed.uuid)),
        "impacts" to ("endUuid" to endIds), "score_decisions" to ("impactUuid" to impactIds),
        "impact_repositions" to ("impactUuid" to impactIds),
        "session_configurations" to ("sessionUuid" to if (old == null) setOf(proposed.uuid) else emptySet()),
        "end_configurations" to ("endUuid" to (endIds - old?.ends.orEmpty().map { it.uuid }.toSet())),
        "bows" to ("uuid" to emptySet()), "arrow_sets" to ("uuid" to emptySet()),
    )
    suspend fun fingerprint(connection: PooledConnection): Map<String, List<Byte>> = fingerprintStorage(connection, exclusions)
    private fun sessionIds(s: ConfiguredSession): Set<String> = buildSet {
        add(s.uuid); s.ends.forEach { add(it.uuid); it.end.impacts.forEach { add(it.uuid) } }
    }
}
