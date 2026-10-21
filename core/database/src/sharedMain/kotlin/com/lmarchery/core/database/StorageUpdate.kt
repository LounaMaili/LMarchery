package com.lmarchery.core.database

/** Explicit UPDATE/INSERT only. Existing historical rows are never updated or removed. */
internal suspend fun SessionDao.updateSessionRows(previous: SessionRows, proposed: SessionRows) {
    val oldEnds = previous.ends.associateBy { it.uuid }
    val oldImpacts = previous.impacts.associateBy { it.uuid }
    val stageEnds = proposed.ends.any { row -> oldEnds[row.uuid]?.let {
        it.position != row.position || it.index != row.index
    } == true }
    if (stageEnds) stageEndOrder(previous.session.uuid)
    val stagedImpactParents = proposed.impacts.filter { row -> oldImpacts[row.uuid]?.let {
        it.position != row.position || it.index != row.index
    } == true }.map { it.endUuid }.toSet()
    stagedImpactParents.forEach { stageImpactOrder(it) }

    if (previous.session != proposed.session) changed(updateSession(proposed.session), "sessions[${proposed.session.uuid}]")
    proposed.ends.forEach { row ->
        val old = oldEnds[row.uuid]
        if (old == null) insertEnd(row)
        else if (stageEnds || old != row) changed(updateEnd(row), "ends[${row.uuid}]")
    }
    proposed.impacts.forEach { row ->
        val old = oldImpacts[row.uuid]
        if (old == null) insertImpact(row)
        else if (row.endUuid in stagedImpactParents || old != row) changed(updateImpact(row), "impacts[${row.uuid}]")
    }
    syncLegacyConfigurations(previous, proposed)
    val oldDecisions = previous.decisions.associateBy { it.impactUuid to it.revision }
    proposed.decisions.forEach { row ->
        val old = oldDecisions[row.impactUuid to row.revision]
        if (old == null) insertDecisions(listOf(row))
        else if (old != row) {
            dataCheck(row.revision == -1, "impacts[${row.impactUuid}].decisions", "Historical decisions are append-only.")
            changed(updateDecision(row), "impacts[${row.impactUuid}].decision")
        }
    }
    val oldTraces = previous.repositions.associateBy { it.impactUuid to it.sequence }
    proposed.repositions.forEach { row ->
        val old = oldTraces[row.impactUuid to row.sequence]
        if (old == null) insertRepositions(listOf(row))
        else dataCheck(old == row, "impacts[${row.impactUuid}].repositions", "Reposition evidence is append-only.")
    }
}

private fun changed(count: Int, path: String) = dataCheck(count == 1, path, "Expected exactly one updated row; transaction rolled back.")
