package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.scoring.archive.ImportCategoryPlan
import com.lmarchery.core.scoring.archive.ImportConflict
import java.util.Collections

/** Exact content/type/owner comparison, including empty ends, configuration provenance and all six UUID kinds. */
class ArchiveV3ImportPlan internal constructor(
    val sessions: ImportCategoryPlan,
    val profiles: ImportCategoryPlan,
    val ends: ImportCategoryPlan,
    val impacts: ImportCategoryPlan,
    val bows: ImportCategoryPlan,
    val arrowSets: ImportCategoryPlan,
    conflicts: List<ImportConflict>,
) {
    val conflicts: List<ImportConflict> = Collections.unmodifiableList(conflicts.toList())
    private val categories get() = listOf(sessions, profiles, ends, impacts, bows, arrowSets)
    val isEmptyArchive get() = categories.all { it.newUuids.isEmpty() && it.identicalUuids.isEmpty() && it.conflictingUuids.isEmpty() }
    val hasAdditions get() = categories.any { it.newUuids.isNotEmpty() }
    val canImport get() = conflicts.isEmpty()
    override fun equals(other: Any?): Boolean = other is ArchiveV3ImportPlan && categories == other.categories && conflicts == other.conflicts
    override fun hashCode(): Int = 31 * categories.hashCode() + conflicts.hashCode()
}

private data class V3ImportEntity(val kind: String, val parent: String?, val value: Any)
private fun DomainArchiveV3.importEntities(): Map<String, V3ImportEntity> = buildMap {
    profiles.forEach { put(it.definition!!.uuid, V3ImportEntity("PROFILE", null, it)) }
    sessions.forEach { session ->
        put(session.uuid, V3ImportEntity("SESSION", null, session))
        session.ends.forEach { end ->
            put(end.uuid, V3ImportEntity("END", session.uuid, end))
            end.end.impacts.forEach { put(it.uuid, V3ImportEntity("IMPACT", end.uuid, it)) }
        }
    }
    bows.forEach { put(it.uuid, V3ImportEntity("BOW", null, it)) }
    arrowSets.forEach { put(it.uuid, V3ImportEntity("ARROW_SET", null, it)) }
}

/** No transition authorization, merge, inference of equipment or winner by updatedAt. Any conflict blocks the whole archive. */
fun planArchiveImportV3(received: DomainArchiveV3, known: DomainArchiveV3): ArchiveV3ImportPlan {
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
