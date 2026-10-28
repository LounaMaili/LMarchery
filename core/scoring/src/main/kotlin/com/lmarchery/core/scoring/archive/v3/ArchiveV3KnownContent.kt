package com.lmarchery.core.scoring.archive.v3

import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.SessionStatus

/** Conservative archive comparison, separate from authorized local expected/proposed transitions. */
internal fun compareKnownV3(known: DomainArchiveV3, received: DomainArchiveV3) {
    val oldEntities = entries(known)
    val incoming = entries(received)
    incoming.forEach { (id, entity) ->
        val old = oldEntities[id] ?: return@forEach
        if (old.kind != entity.kind || old.parent != entity.parent)
            v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, entity.path, "A known identity cannot change kind or owner.")
        if (entity.kind == "profile" && entity.value != old.value)
            v3Fail(ArchiveV3IssueCode.PROFILE_IDENTITY_CONFLICT, entity.path, "A known immutable definition or edition changed.")
    }
    received.sessions.forEachIndexed { i, next ->
        val previous = known.sessions.singleOrNull { it.uuid == next.uuid } ?: return@forEachIndexed
        val p = "sessions[$i]"
        if (next.initialConfiguration != previous.initialConfiguration)
            v3Fail(ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "$p.initialConfiguration", "A known initial configuration cannot be replaced.")
        if (next.initialConfigurationOrigin != previous.initialConfigurationOrigin)
            v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$p.initialConfigurationOrigin", "Known capture provenance cannot be rewritten.")
        previous.ends.forEach { oldEnd ->
            val index = next.ends.indexOfFirst { it.uuid == oldEnd.uuid }
            if (index < 0) v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$p.ends", "A known session cannot omit one of its ends.")
            val end = next.ends[index]; val e = "$p.ends[$index]"
            if (end.configuration != oldEnd.configuration)
                v3Fail(ArchiveV3IssueCode.CONFIGURATION_CONFLICT, "$e.configuration", "A captured configuration cannot be changed, even on an empty end.")
            if (end.origin != oldEnd.origin)
                v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$e.origin", "Known capture provenance cannot be rewritten.")
            oldEnd.end.impacts.forEach { oldImpact ->
                val j = end.end.impacts.indexOfFirst { it.uuid == oldImpact.uuid }
                if (j < 0) v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$e.end.impacts", "No known impact can be removed or reparented.")
                val impact = end.end.impacts[j]
                if (impact.repositions.size < oldImpact.repositions.size || impact.repositions.take(oldImpact.repositions.size) != oldImpact.repositions)
                    v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$e.end.impacts[$j].repositions", "Known traces were omitted or rewritten.")
            }
            if (oldEnd.end.status == EndStatus.VALIDATED && oldEnd.end != end.end)
                v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, "$e.end", "A known validated end cannot be edited.")
        }
        if (previous.status == SessionStatus.COMPLETED && previous != next)
            v3Fail(ArchiveV3IssueCode.HISTORY_CONFLICT, p, "A known completed aggregate cannot be revised.")
    }
    incoming.forEach { (id, entity) ->
        val old = oldEntities[id]
        if (old != null && old.value != entity.value)
            v3Fail(ArchiveV3IssueCode.DIVERGENT_REVISION, entity.path,
                "Different known content requires intervention; updatedAt is not proof of causal descent.")
    }
    // Roots absent from this received content are not deletion instructions. No storage is touched.
}

private data class Entry(val kind: String, val parent: String?, val value: Any, val path: String)
private fun entries(archive: DomainArchiveV3): Map<String, Entry> = buildMap {
    archive.profiles.forEachIndexed { i, p -> put(p.definition!!.uuid, Entry("profile", null, p, "profiles[$i]")) }
    archive.bows.forEachIndexed { i, b -> put(b.uuid, Entry("bow", null, b, "bows[$i]")) }
    archive.arrowSets.forEachIndexed { i, a -> put(a.uuid, Entry("arrowSet", null, a, "arrowSets[$i]")) }
    archive.sessions.forEachIndexed { i, s ->
        val p = "sessions[$i]"; put(s.uuid, Entry("session", null, s, p))
        s.ends.forEachIndexed { j, e ->
            val ep = "$p.ends[$j]"; put(e.uuid, Entry("end", s.uuid, e, ep))
            e.end.impacts.forEachIndexed { k, a -> put(a.uuid, Entry("impact", e.uuid, a, "$ep.end.impacts[$k]")) }
        }
    }
}
