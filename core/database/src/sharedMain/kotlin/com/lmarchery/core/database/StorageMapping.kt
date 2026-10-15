package com.lmarchery.core.database

import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.model.session.InputMode
import com.lmarchery.core.model.session.ScoringMode
import com.lmarchery.core.model.session.SessionStatus
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.target.TargetZone
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.*
import java.time.DateTimeException
import java.time.Instant

internal class CorruptStorage(val diagnostic: StorageDiagnostic) : RuntimeException(diagnostic.detail)

internal fun dataCheck(condition: Boolean, path: String, detail: String) {
    if (!condition) throw CorruptStorage(StorageDiagnostic(path, detail))
}

internal inline fun <T> decode(path: String, block: () -> T): T = try { block() }
catch (error: IllegalArgumentException) { throw CorruptStorage(StorageDiagnostic(path, error.message ?: "Invalid stored value.")) }
catch (error: DateTimeException) { throw CorruptStorage(StorageDiagnostic(path, error.message ?: "Invalid stored timestamp.")) }

private fun sqlTime(text: String): SqlInstant = UtcTimestamps.parse(text).let { SqlInstant(it.epochSecond, it.nano) }

private fun SqlInstant.text(): String {
    val seconds = requireNotNull(epochSecond) { "Missing epochSecond." }
    val nanos = requireNotNull(nano) { "Missing nano." }
    require(nanos in 0..999999999) { "Stored nano is out of range; normalization is forbidden." }
    return UtcTimestamps.format(Instant.ofEpochSecond(seconds, nanos.toLong()))
}

private fun Int.flag(): Boolean {
    require(this == 0 || this == 1) { "Stored boolean must be exactly 0 or 1." }
    return this == 1
}
private fun Boolean.flag(): Int = if (this) 1 else 0

private fun positions(actual: List<Int>, count: Int, path: String) {
    dataCheck(actual.size == count && actual.withIndex().all { it.index == it.value }, path,
        "Stored child count or ordered positions are inconsistent; children must not disappear silently.")
}

internal suspend fun SessionDao.profileSnapshot(uuid: String): TargetProfileSnapshot {
    val row = profile(uuid) ?: throw CorruptStorage(StorageDiagnostic("profiles[$uuid]", "Referenced profile is missing."))
    val zones = zones(uuid)
    positions(zones.map { it.position }, row.zoneCount, "profiles[$uuid].zones")
    return decode("profiles[$uuid]") {
        TargetProfileSnapshot(TargetType(row.uuid, row.name, row.discipline, row.diameterCm, row.maxScore, row.minScore,
            row.defaultArrowCount, row.defaultDistanceM, row.isBuiltIn.flag(), row.xRingEnabled.flag(), row.xRingLabel,
            Float.fromBits(row.innerTenRadiusBits), zones.map { TargetZone(it.score, Float.fromBits(it.innerRadiusBits),
                Float.fromBits(it.outerRadiusBits), it.colorName, it.colorHex) }), row.regulatoryEdition)
    }
}

/** Queries run inside the caller's consistent Room transaction. Never constructs opaque domain traces. */
internal suspend fun SessionDao.sessionSnapshot(row: SessionRow): ArchiveSnapshot {
    val profile = profileSnapshot(row.profileUuid)
    val ends = ends(row.uuid)
    positions(ends.map { it.position }, row.endCount, "sessions[${row.uuid}].ends")
    val endSnapshots = ends.map { end ->
        val impacts = impacts(end.uuid)
        positions(impacts.map { it.position }, end.impactCount, "ends[${end.uuid}].impacts")
        val impactSnapshots = impacts.map { impact ->
            val traces = repositions(impact.uuid)
            val decisions = decisions(impact.uuid)
            positions(traces.map { it.sequence }, impact.repositionCount, "impacts[${impact.uuid}].repositions")
            dataCheck(decisions.size == traces.size + 1 && decisions.withIndex().all { it.value.revision == it.index - 1 },
                "impacts[${impact.uuid}].decisions", "Current and all previous decisions must be present exactly once.")
            decode("impacts[${impact.uuid}]") {
                ImpactSnapshot(impact.uuid, impact.index, impact.profileUuid,
                    NormalizedPoint(Float.fromBits(impact.xBits), Float.fromBits(impact.yBits)),
                    enumValueOf<InputMode>(impact.inputMode), impact.note, impact.createdAt.text(), impact.updatedAt.text(),
                    decisions.first().snapshot(), traces.mapIndexed { index, trace ->
                        RepositionSnapshot(trace.impactUuid, trace.impactCreatedAt.text(), enumValueOf<InputMode>(trace.inputMode),
                            NormalizedPoint(Float.fromBits(trace.previousXBits), Float.fromBits(trace.previousYBits)),
                            decisions[index + 1].snapshot(), trace.previousUpdatedAt.text(),
                            NormalizedPoint(Float.fromBits(trace.newXBits), Float.fromBits(trace.newYBits)),
                            trace.changedAt.text(), trace.reason)
                    })
            }
        }
        decode("ends[${end.uuid}]") {
            EndSnapshot(end.uuid, end.index, enumValueOf<EndStatus>(end.status), end.notes,
                end.createdAt.text(), end.updatedAt.text(), end.validatedAt?.text(), impactSnapshots)
        }
    }
    val session = decode("sessions[${row.uuid}]") {
        SessionSnapshot(row.uuid, CivilDateSnapshot(row.localDateTime, row.zoneId, row.offset),
            enumValueOf<SessionStatus>(row.status), row.startedAt?.text(), row.endedAt?.text(), row.plannedEndCount,
            row.title, row.profileUuid, row.distanceM, enumValueOf<ArrowCountMode>(row.arrowCountMode), row.arrowCountPerEnd,
            row.isIndoor.flag(), enumValueOf<ScoringMode>(row.scoringMode), row.bowUuid, row.arrowSetUuid,
            row.locationName, row.notes, endSnapshots, row.createdAt.text(), row.updatedAt.text())
    }
    val snapshot = ArchiveSnapshot(row.archiveSchemaVersion, row.scoringContractVersion, listOf(profile), listOf(session))
    val expected = snapshot.identities()
    val actual = expected.chunked(900).flatMap { identities(it.map { identity -> identity.uuid }) }.associateBy { it.uuid }
    dataCheck(expected.all { actual[it.uuid] == it }, "sessions[${row.uuid}].identities", "Identity registry is missing or inconsistent.")
    return snapshot
}

private fun DecisionRow.snapshot(): DecisionSnapshot {
    require((boundaryType == null) == (boundaryRadiusBits == null)) { "Incomplete boundary reference." }
    val boundary = boundaryType?.let { BoundaryReference(enumValueOf<BoundaryType>(it), Float.fromBits(requireNotNull(boundaryRadiusBits))) }
    return DecisionSnapshot(enumValueOf<ScoreDecisionKind>(kind), BoundaryValue(selectedScore, selectedIsX.flag()),
        decidedAt?.text(), boundary, reason, ScoreAssertions(Float.fromBits(assertions.distanceBits), assertions.calculated,
            assertions.final, assertions.geometricIsX.flag(), assertions.isX.flag(), assertions.isMiss.flag(), assertions.isCordDecisionManual.flag()))
}

internal fun ArchiveSnapshot.identities(): List<IdentityRow> = buildList {
    profiles.forEach { add(IdentityRow(requireNotNull(it.definition).uuid, "PROFILE")) }
    sessions.forEach { session ->
        add(IdentityRow(session.uuid, "SESSION"))
        session.ends.forEach { end ->
            add(IdentityRow(end.uuid, "END"))
            end.impacts.forEach { add(IdentityRow(it.uuid, "IMPACT")) }
        }
    }
}

internal suspend fun SessionDao.writeProfile(value: TargetProfileSnapshot) {
    val target = requireNotNull(value.definition)
    insertProfile(ProfileRow(target.uuid, target.name, target.discipline, target.diameterCm, target.maxScore, target.minScore,
        target.defaultArrowCount, target.defaultDistanceM, target.isBuiltIn.flag(), target.xRingEnabled.flag(),
        target.xRingLabel, target.innerTenRadiusNormalized.toRawBits(), value.regulatoryEdition, target.zones.size))
    insertZones(target.zones.mapIndexed { position, zone -> ZoneRow(target.uuid, position, zone.score,
        zone.innerRadiusNormalized.toRawBits(), zone.outerRadiusNormalized.toRawBits(), zone.colorName, zone.colorHex) })
}

internal suspend fun SessionDao.writeSession(snapshot: ArchiveSnapshot) {
    val session = snapshot.sessions.single()
    val date = requireNotNull(session.date)
    insertSession(SessionRow(session.uuid, session.targetTypeUuid, requireNotNull(snapshot.schemaVersion),
        requireNotNull(snapshot.scoringContractVersion), requireNotNull(date.localDateTime), requireNotNull(date.zoneId),
        requireNotNull(date.offset), session.status.name, session.startedAt?.let(::sqlTime), session.endedAt?.let(::sqlTime),
        session.plannedEndCount, session.title, session.distanceM, session.arrowCountMode.name, session.arrowCountPerEnd,
        session.isIndoor.flag(), session.scoringMode.name, session.bowUuid, session.arrowSetUuid, session.locationName,
        session.notes, sqlTime(requireNotNull(session.createdAt)), sqlTime(requireNotNull(session.updatedAt)), session.ends.size))
    session.ends.forEachIndexed { position, end ->
        insertEnd(EndRow(end.uuid, session.uuid, position, end.index, end.status.name, end.notes,
            sqlTime(requireNotNull(end.createdAt)), sqlTime(requireNotNull(end.updatedAt)), end.validatedAt?.let(::sqlTime), end.impacts.size))
        end.impacts.forEachIndexed { impactPosition, impact ->
            insertImpact(ImpactRow(impact.uuid, end.uuid, impact.targetTypeUuid, impactPosition, impact.index,
                impact.point.x.toRawBits(), impact.point.y.toRawBits(), impact.inputMode.name, impact.note,
                sqlTime(requireNotNull(impact.createdAt)), sqlTime(requireNotNull(impact.updatedAt)), impact.repositions.size))
            insertDecisions(listOf(requireNotNull(impact.decision).row(impact.uuid, -1)) +
                impact.repositions.mapIndexed { index, trace -> requireNotNull(trace.previousDecision).row(impact.uuid, index) })
            insertRepositions(impact.repositions.mapIndexed { index, trace ->
                RepositionRow(trace.impactUuid, index, sqlTime(requireNotNull(trace.impactCreatedAt)), trace.inputMode.name,
                    trace.previousPoint.x.toRawBits(), trace.previousPoint.y.toRawBits(), sqlTime(requireNotNull(trace.previousUpdatedAt)),
                    trace.newPoint.x.toRawBits(), trace.newPoint.y.toRawBits(), sqlTime(requireNotNull(trace.changedAt)), trace.reason)
            })
        }
    }
}

private fun DecisionSnapshot.row(uuid: String, revision: Int): DecisionRow {
    val selected = requireNotNull(selected)
    val assertions = requireNotNull(assertions)
    return DecisionRow(uuid, revision, requireNotNull(kind).name, selected.score, selected.isX.flag(), decidedAt?.let(::sqlTime),
        boundary?.type?.name, boundary?.radiusNormalized?.toRawBits(), reason,
        SqlScoreAssertions(requireNotNull(assertions.distanceFromCenter).toRawBits(), requireNotNull(assertions.scoreCalculated),
            requireNotNull(assertions.scoreFinal), requireNotNull(assertions.geometricIsX).flag(), requireNotNull(assertions.isX).flag(),
            requireNotNull(assertions.isMiss).flag(), requireNotNull(assertions.isCordDecisionManual).flag()))
}
