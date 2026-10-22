package com.lmarchery.core.database

import androidx.room.PooledConnection
import androidx.sqlite.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** Bounded, typed graph must be validated first. Exact SQL lexemes/types, including NUL, are preserved. */
private val storageFingerprintKeys = linkedMapOf(
    "entity_identities" to "uuid", "target_profiles" to "uuid", "target_zones" to "profileUuid",
    "sessions" to "uuid", "ends" to "sessionUuid", "impacts" to "endUuid", "score_decisions" to "impactUuid",
    "impact_repositions" to "impactUuid", "session_configurations" to "sessionUuid", "end_configurations" to "endUuid",
    "bows" to "uuid", "arrow_sets" to "uuid",
)

internal suspend fun fingerprintStorage(connection: PooledConnection, exclusions: Map<String, Pair<String, Set<String>>> = emptyMap()): Map<String, List<Byte>> =
    storageFingerprintKeys.mapValues { (table, owner) -> exclusions[table] ?: (owner to emptySet()) }.mapValues { (table, scope) ->
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

internal fun ownFingerprint(value: Map<String, List<Byte>>): Map<String, List<Byte>> =
    Collections.unmodifiableMap(value.mapValues { Collections.unmodifiableList(it.value.toList()) })
