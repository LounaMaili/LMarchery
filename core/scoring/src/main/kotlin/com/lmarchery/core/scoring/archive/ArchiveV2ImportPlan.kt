package com.lmarchery.core.scoring.archive

import java.util.Collections

/** Per-category classification. Lists are owned, sorted and immutable. */
class ImportCategoryPlan internal constructor(new: List<String>, identical: List<String>, conflicting: List<String>) {
    val newUuids: List<String> = Collections.unmodifiableList(new.sorted())
    val identicalUuids: List<String> = Collections.unmodifiableList(identical.sorted())
    val conflictingUuids: List<String> = Collections.unmodifiableList(conflicting.sorted())
    override fun equals(other: Any?): Boolean = other is ImportCategoryPlan && newUuids == other.newUuids &&
        identicalUuids == other.identicalUuids && conflictingUuids == other.conflictingUuids
    override fun hashCode(): Int = arrayOf(newUuids, identicalUuids, conflictingUuids).contentHashCode()
}

/** V2 never projects to the V1 planner: equipment and unreferenced profiles are first-class roots. */
class ArchiveV2ImportPlan internal constructor(
    val sessions: ImportCategoryPlan,
    val profiles: ImportCategoryPlan,
    val bows: ImportCategoryPlan,
    val arrowSets: ImportCategoryPlan,
    conflicts: List<ImportConflict>,
) {
    val conflicts: List<ImportConflict> = Collections.unmodifiableList(conflicts.toList())
    val isEmptyArchive get() = categories.all { it.newUuids.isEmpty() && it.identicalUuids.isEmpty() && it.conflictingUuids.isEmpty() }
    val hasAdditions get() = categories.any { it.newUuids.isNotEmpty() }
    val canImport get() = conflicts.isEmpty()
    private val categories get() = listOf(sessions, profiles, bows, arrowSets)
    override fun equals(other: Any?): Boolean = other is ArchiveV2ImportPlan && categories == other.categories && conflicts == other.conflicts
    override fun hashCode(): Int = 31 * categories.hashCode() + conflicts.hashCode()
}

private data class V2ImportEntity(val kind: String, val parent: String?, val value: Any)
private fun DomainArchiveV2.entities(): Map<String, V2ImportEntity> = buildMap {
    profiles.forEach { put(it.definition!!.uuid, V2ImportEntity("PROFILE", null, it)) }
    sessions.forEach { session ->
        put(session.uuid, V2ImportEntity("SESSION", null, session))
        session.ends.forEach { end ->
            put(end.uuid, V2ImportEntity("END", session.uuid, end))
            end.impacts.forEach { put(it.uuid, V2ImportEntity("IMPACT", end.uuid, it)) }
        }
    }
    bows.forEach { put(it.uuid, V2ImportEntity("BOW", null, it)) }
    arrowSets.forEach { put(it.uuid, V2ImportEntity("ARROW_SET", null, it)) }
}

/** Pure whole-state comparison including descendants, parent ownership, edition and provenance. */
fun planArchiveImportV2(received: DomainArchiveV2, known: DomainArchiveV2): ArchiveV2ImportPlan {
    val old = known.entities()
    val incoming = received.entities()
    val conflicts = incoming.toSortedMap().mapNotNull { (id, entity) ->
        if (id in old && old[id] != entity) ImportConflict(id, "IDENTITY_CONFLICT", "entities[$id]",
            "Known identity has different content, type or parent; no overwrite allowed.") else null
    }
    val conflictingIds = conflicts.mapNotNull { it.uuid }.toSet()
    fun classify(ids: List<String>, blocked: (String) -> Boolean = { false }): ImportCategoryPlan {
        val failed = ids.filter { it in conflictingIds || blocked(it) }.toSet()
        return ImportCategoryPlan(ids.filter { it !in old && it !in failed },
            ids.filter { incoming[it] == old[it] && it !in failed }, ids.filter { it in failed })
    }
    val sessions = received.sessions.associateBy { it.uuid }
    return ArchiveV2ImportPlan(classify(sessions.keys.toList()) { id ->
        val s = sessions.getValue(id)
        s.targetType.uuid in conflictingIds || s.ends.any { e -> e.uuid in old && old[e.uuid] != incoming[e.uuid] ||
            e.impacts.any { it.uuid in old && old[it.uuid] != incoming[it.uuid] } } ||
            // A new root cannot adopt even an identical existing descendant.
            (id !in old && s.ends.any { e -> e.uuid in old || e.impacts.any { it.uuid in old } })
    }, classify(received.profiles.map { it.definition!!.uuid }), classify(received.bows.map { it.uuid }),
        classify(received.arrowSets.map { it.uuid }), conflicts)
}
