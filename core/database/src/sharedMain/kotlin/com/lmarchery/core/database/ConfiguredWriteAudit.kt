package com.lmarchery.core.database

import androidx.room.PooledConnection
import androidx.sqlite.*
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3
import com.lmarchery.core.scoring.configured.ConfiguredSession
import java.nio.ByteBuffer
import java.security.MessageDigest

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
    suspend fun fingerprint(connection: PooledConnection): Map<String, List<Byte>> = exclusions.mapValues { (table, scope) ->
        connection.usePrepared("SELECT * FROM `$table` ORDER BY 1,2") { statement ->
            val digest = MessageDigest.getInstance("SHA-256")
            val count = statement.getColumnCount()
            val owner = (0 until count).single { statement.getColumnName(it) == scope.first }
            fun integer(n: Long) { digest.update(ByteBuffer.allocate(8).putLong(n).array()) }
            while (statement.step()) {
                if (statement.getText(owner) in scope.second) continue
                integer(count.toLong())
                for (i in 0 until count) {
                    val type = statement.getColumnType(i); integer(type.toLong())
                    when (type) {
                        SQLITE_DATA_NULL -> Unit
                        SQLITE_DATA_INTEGER -> integer(statement.getLong(i))
                        SQLITE_DATA_TEXT -> { val bytes = statement.getText(i).toByteArray(Charsets.UTF_8); integer(bytes.size.toLong()); digest.update(bytes) }
                        else -> throw CorruptStorage(StorageDiagnostic(table, "Unexpected SQL type in validated graph."))
                    }
                }
            }
            digest.digest().toList()
        }
    }
    private fun sessionIds(s: ConfiguredSession): Set<String> = buildSet {
        add(s.uuid); s.ends.forEach { add(it.uuid); it.end.impacts.forEach { add(it.uuid) } }
    }
}
