package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.time.UtcTimestamps
import java.util.Collections

/** An independently validated archive, not permission to overwrite an existing aggregate. */
class DomainArchive internal constructor(profiles: List<TargetProfileSnapshot>, sessions: List<Session>) : SessionContent {
    override val profiles: List<TargetProfileSnapshot> = Collections.unmodifiableList(profiles.toList())
    override val sessions: List<Session> = Collections.unmodifiableList(sessions.toList())

    override fun toSnapshot(): ArchiveSnapshot = snapshotArchive(profiles, sessions)
}

/** Explicit profile metadata is required; no edition is invented for a custom/historical definition. */
fun snapshotArchive(profiles: List<TargetProfileSnapshot>, sessions: List<Session>): ArchiveSnapshot {
    require(sessions.all { s -> s.ends.all { it.cancellations.isEmpty() } }) { "Archive V1/V2 cannot represent cancellation evidence. Use archive V4." }
    sessions.forEach { session ->
        require(profiles.singleOrNull { it.definition?.uuid == session.targetType.uuid }?.definition == session.targetType) {
            "Snapshot profiles must contain the session's exact complete target definition."
        }
    }
    return ArchiveSnapshot(
        schemaVersion = ArchiveContractVersions.SCHEMA,
        scoringContractVersion = ArchiveContractVersions.SCORING,
        profiles = profiles.toList(),
        sessions = sessions.map { session ->
            SessionSnapshot(session.uuid,
                CivilDateSnapshot(session.date.localDateTime.toString(), session.date.zoneId.id, session.date.offset.id),
                session.status, session.startedAt?.let(UtcTimestamps::format), session.endedAt?.let(UtcTimestamps::format),
                session.plannedEndCount, session.title, session.targetType.uuid, session.distanceM, session.arrowCountMode,
                session.arrowCountPerEnd, session.isIndoor, session.scoringMode, session.bowUuid, session.arrowSetUuid,
                session.locationName, session.notes, session.ends.map(End::toSnapshot),
                UtcTimestamps.format(session.createdAt), UtcTimestamps.format(session.updatedAt))
        },
    )
}

internal fun End.toSnapshot(): EndSnapshot {
    require(cancellations.isEmpty()) { "Published end snapshots cannot omit cancellation evidence. Use archive V4." }
    return toPayloadSnapshot()
}

/** V4 carries terminal participation evidence separately; this internal payload is never an old archive export. */
internal fun End.toPayloadSnapshot(): EndSnapshot = EndSnapshot(uuid, index, status, notes,
    UtcTimestamps.format(createdAt), UtcTimestamps.format(updatedAt), validatedAt?.let(UtcTimestamps::format),
    impacts.map(ArrowImpact::toSnapshot))

private fun ArrowImpact.toSnapshot(): ImpactSnapshot = ImpactSnapshot(uuid, index, targetType.uuid, point,
    inputMode, note, UtcTimestamps.format(createdAt), UtcTimestamps.format(updatedAt), finalScoreDecision.toSnapshot(),
    repositions.map { change ->
        RepositionSnapshot(change.impactUuid, UtcTimestamps.format(change.impactCreatedAt), change.inputMode,
            change.previousPoint, change.previousDecision.toSnapshot(), UtcTimestamps.format(change.previousUpdatedAt),
            change.newPoint, UtcTimestamps.format(change.changedAt), change.reason)
    })

private fun FinalScoreDecision.toSnapshot(): DecisionSnapshot = DecisionSnapshot(provenance.kind, value,
    provenance.decidedAt?.let(UtcTimestamps::format), provenance.boundary, provenance.reason,
    ScoreAssertions(distanceFromCenter, scoreCalculated, scoreFinal, geometricValue.isX, isX, isMiss, isCordDecisionManual))
