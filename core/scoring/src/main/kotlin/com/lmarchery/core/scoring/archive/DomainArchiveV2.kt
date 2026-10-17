package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.time.UtcTimestamps
import java.util.Collections

/** Validated V2 content, deliberately not a DomainArchive: V1 APIs must not discard its equipment. */
class DomainArchiveV2 internal constructor(
    sessionContent: DomainArchive,
    bows: List<Bow>,
    arrowSets: List<ArrowSet>,
) {
    val profiles: List<TargetProfileSnapshot> = sessionContent.profiles
    val sessions: List<Session> = sessionContent.sessions
    val bows: List<Bow> = Collections.unmodifiableList(bows.toList())
    val arrowSets: List<ArrowSet> = Collections.unmodifiableList(arrowSets.toList())

    fun toSnapshot(): ArchiveV2Snapshot = snapshotArchiveV2(profiles, sessions, bows, arrowSets)

    override fun equals(other: Any?): Boolean = other is DomainArchiveV2 && profiles == other.profiles &&
        sessions == other.sessions && bows == other.bows && arrowSets == other.arrowSets
    override fun hashCode(): Int = arrayOf(profiles, sessions, bows, arrowSets).contentHashCode()
}

/** Explicit capture only; reconstructArchiveV2 must validate this untrusted transfer value. */
fun snapshotArchiveV2(
    profiles: List<TargetProfileSnapshot>,
    sessions: List<Session>,
    bows: List<Bow>,
    arrowSets: List<ArrowSet>,
): ArchiveV2Snapshot {
    val sessionContent = snapshotArchive(profiles, sessions)
    return ArchiveV2Snapshot(ArchiveContractVersions.SCHEMA_V2, ArchiveContractVersions.SCORING,
        sessionContent.profiles, sessionContent.sessions,
        bows.map { BowSnapshot(it.uuid, it.name, it.type, it.drawWeightLbs, it.brand, it.model, it.notes,
            UtcTimestamps.format(it.createdAt), UtcTimestamps.format(it.updatedAt)) },
        arrowSets.map { ArrowSetSnapshot(it.uuid, it.name, it.spine, it.length, it.pointWeight, it.nock, it.vanes,
            it.material, it.notes, UtcTimestamps.format(it.createdAt), UtcTimestamps.format(it.updatedAt)) })
}
