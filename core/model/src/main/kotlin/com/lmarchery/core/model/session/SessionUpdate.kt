package com.lmarchery.core.model.session

/**
 * Checks a local revision against its complete previous state, independently of storage.
 * Snapshot validity alone cannot establish identity, ownership or preservation of history.
 * No deletion, reparenting or target-profile change is supported by this contract.
 */
fun validateSessionUpdate(expected: Session, proposed: Session) {
    require(proposed.uuid == expected.uuid) { "A session revision must preserve its UUID." }
    require(proposed.targetType == expected.targetType) { "A persisted session cannot change target profile." }
    proposed.requireNotOlderThan(expected)

    if (expected.startedAt != null) {
        require(proposed.startedAt == expected.startedAt) { "An existing session start must be preserved." }
    }
    when (expected.status) {
        SessionStatus.DRAFT -> if (proposed.status != SessionStatus.DRAFT) {
            // A caller may save several valid forward operations in one revision.
            val started = expected.start(requireNotNull(proposed.startedAt))
            if (proposed.status == SessionStatus.COMPLETED) started.complete(requireNotNull(proposed.endedAt))
        }
        SessionStatus.IN_PROGRESS -> {
            require(proposed.status != SessionStatus.DRAFT) { "An IN_PROGRESS session cannot return to DRAFT." }
            if (proposed.status == SessionStatus.COMPLETED) expected.complete(requireNotNull(proposed.endedAt))
        }
        SessionStatus.COMPLETED -> Unit // requireNotOlderThan enforces closure and unchanged ends.
    }

    val nextEnds = proposed.ends.associateBy { it.uuid }
    expected.ends.forEach { oldEnd ->
        val nextEnd = requireNotNull(nextEnds[oldEnd.uuid]) { "A session update cannot remove an existing end." }
        val nextImpacts = nextEnd.impacts.associateBy { it.uuid }
        oldEnd.impacts.forEach { oldImpact ->
            require(nextImpacts.containsKey(oldImpact.uuid)) {
                "A session update cannot remove an impact or move it to another end."
            }
        }
    }
}
