package com.lmarchery.core.scoring.archive

import java.util.Collections

data class ImportConflict(val uuid: String?, val code: String, val path: String, val detail: String)

/** Informational plan only. Room must recheck identities and this plan inside its writer transaction. */
data class ArchiveImportPlan(
    val sessionCount: Int,
    val profileCount: Int,
    val newSessionUuids: List<String>,
    val identicalSessionUuids: List<String>,
    val newProfileUuids: List<String>,
    val conflicts: List<ImportConflict>,
) {
    val canImport get() = conflicts.isEmpty() && newSessionUuids.isNotEmpty()
}

private data class ImportEntity(val kind: String, val parent: String?, val value: Any)
private fun DomainArchive.importEntities(): Map<String, ImportEntity> = buildMap {
    profiles.forEach { put(it.definition!!.uuid, ImportEntity("PROFILE", null, it)) }
    sessions.forEach { session ->
        put(session.uuid, ImportEntity("SESSION", null, session))
        session.ends.forEach { end ->
            put(end.uuid, ImportEntity("END", session.uuid, end))
            end.impacts.forEach { put(it.uuid, ImportEntity("IMPACT", end.uuid, it)) }
        }
    }
}

/** Strict whole-content equality, including parent, dates and provenance; root discovery order is irrelevant. */
fun planArchiveImport(received: DomainArchive, known: DomainArchive): ArchiveImportPlan {
    val old = known.importEntities()
    val conflicts = mutableListOf<ImportConflict>()
    if (received.sessions.isEmpty()) conflicts += ImportConflict(null, "EMPTY_ARCHIVE", "sessions", "No sessions to restore.")
    val referenced = received.sessions.map { it.targetType.uuid }.toSet()
    received.profiles.filter { it.definition!!.uuid !in referenced }.forEach {
        val id = it.definition!!.uuid
        conflicts += ImportConflict(id, "UNREFERENCED_PROFILE", "profiles[$id]", "Profile without a session is not imported separately.")
    }
    received.importEntities().toSortedMap().forEach { (id, entity) ->
        if (old[id] != null && old[id] != entity) conflicts += ImportConflict(id, "IDENTITY_CONFLICT", "entities[$id]",
            "Known identity has different content, entity type or parent; no overwrite allowed.")
    }
    val sessions = known.sessions.associateBy { it.uuid }
    fun <T> owned(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    return ArchiveImportPlan(received.sessions.size, received.profiles.size,
        owned(received.sessions.filter { it.uuid !in sessions }.map { it.uuid }.sorted()),
        owned(received.sessions.filter { sessions[it.uuid] == it }.map { it.uuid }.sorted()),
        owned(received.profiles.filter { it.definition!!.uuid !in old }.map { it.definition!!.uuid }.sorted()),
        owned(conflicts))
}
