package com.lmarchery.core.scoring.archive.v4

import com.lmarchery.core.scoring.archive.v3.ArchiveV3ImportPlan

import com.lmarchery.core.scoring.archive.ImportCategoryPlan
import com.lmarchery.core.scoring.archive.ImportConflict
import java.util.Collections

typealias ArchiveV4ImportPlan = ArchiveV3ImportPlan

private data class V4ImportEntity(val kind: String, val parent: String?, val value: Any)
private fun DomainArchiveV4.importEntities(): Map<String, V4ImportEntity> = buildMap {
    profiles.forEach { put(it.definition!!.uuid, V4ImportEntity("PROFILE", null, it)) }
    sessions.forEach { session ->
        put(session.uuid, V4ImportEntity("SESSION", null, session))
        session.ends.forEach { end ->
            put(end.uuid, V4ImportEntity("END", session.uuid, end))
            end.end.impacts.forEach { put(it.uuid, V4ImportEntity("IMPACT", end.uuid, it)) }
        }
    }
    bows.forEach { put(it.uuid, V4ImportEntity("BOW", null, it)) }
    arrowSets.forEach { put(it.uuid, V4ImportEntity("ARROW_SET", null, it)) }
}

/** No transition authorization, merge, inference of equipment or winner by updatedAt. Any conflict blocks the whole archive. */
fun planArchiveImportV4(received: DomainArchiveV4, known: DomainArchiveV4): ArchiveV3ImportPlan {
    val incoming = received.importEntities()
    val old = known.importEntities()
    val conflicts = incoming.toSortedMap().mapNotNull { (uuid, entity) ->
        if (uuid in old && old[uuid] != entity) ImportConflict(uuid, "IDENTITY_CONFLICT", "entities[$uuid]",
            "Known UUID has different content, entity type or owner; restoration never overwrites.") else null
    }.toMutableList()
    val conflictingIds = conflicts.mapNotNull { it.uuid }.toSet()
    val blockedSessions = received.sessions.filter { s ->
        s.uuid in conflictingIds || (listOf(s.initialConfiguration) + s.ends.map { it.configuration }).any { it.targetType.uuid in conflictingIds } ||
            s.ends.any { e -> e.uuid in conflictingIds || e.end.impacts.any { it.uuid in conflictingIds } }
    }.map { it.uuid }.toSet()
    (blockedSessions - conflictingIds).sorted().forEach { uuid -> conflicts += ImportConflict(uuid, "DEPENDENCY_CONFLICT",
        "sessions[$uuid]", "A required profile or descendant is conflicting; the complete session cannot be restored.") }
    fun classify(ids: List<String>, blocked: Set<String> = emptySet()): ImportCategoryPlan {
        val failed = ids.filter { it in conflictingIds || it in blocked }.toSet()
        return ImportCategoryPlan(ids.filter { it !in old && it !in failed },
            ids.filter { incoming[it] == old[it] && it !in failed }, ids.filter { it in failed })
    }
    return ArchiveV3ImportPlan(classify(received.sessions.map { it.uuid }, blockedSessions),
        classify(received.profiles.map { it.definition!!.uuid }), classify(received.sessions.flatMap { it.ends.map { e -> e.uuid } }),
        classify(received.sessions.flatMap { it.ends.flatMap { e -> e.end.impacts.map { a -> a.uuid } } }),
        classify(received.bows.map { it.uuid }), classify(received.arrowSets.map { it.uuid }), conflicts.sortedBy { it.path })
}
