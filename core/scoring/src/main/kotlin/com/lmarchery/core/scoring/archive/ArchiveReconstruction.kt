package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.model.scoring.ScoreDecisionProvenance
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.ArrowImpact
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.Session
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

enum class ArchiveIssueCode {
    MISSING_FIELD, UNSUPPORTED_VERSION, INVALID_UUID, DUPLICATE_UUID,
    UNKNOWN_PROFILE, PROFILE_IDENTITY_CONFLICT, MISSING_PROVENANCE,
    TEMPORAL_CONTEXT, CIVIL_CONTEXT_CONFLICT, INVALID_DOMAIN_VALUE,
    CONTRADICTORY_DERIVED_VALUE, HISTORY_CONFLICT, DIVERGENT_REVISION,
}

data class ArchiveIssue(val code: ArchiveIssueCode, val path: String, val detail: String)

sealed interface ArchiveReconstruction {
    data class Accepted(val archive: DomainArchive) : ArchiveReconstruction

    /** No partially valid Session escapes. Retain the received values for explicit intervention. */
    data class NeedsIntervention(val source: ArchiveSnapshot, val issue: ArchiveIssue) : ArchiveReconstruction
}

/**
 * All-or-nothing structural reconstruction, without I/O or automatic conflict resolution.
 * Supply the complete known baseline when assessing revisions; null means an independent restore,
 * which cannot prove that an unknown earlier history has not been omitted.
 */
fun reconstructArchive(snapshot: ArchiveSnapshot, known: DomainArchive? = null): ArchiveReconstruction = try {
    ArchiveReconstruction.Accepted(Reconstructor().reconstruct(snapshot, known))
} catch (error: UnresolvedArchive) {
    ArchiveReconstruction.NeedsIntervention(snapshot, error.issue)
}

private class UnresolvedArchive(val issue: ArchiveIssue) : RuntimeException(issue.detail)

private fun unresolved(code: ArchiveIssueCode, path: String, detail: String): Nothing =
    throw UnresolvedArchive(ArchiveIssue(code, path, detail))

private fun <T : Any> field(value: T?, path: String): T = value
    ?: unresolved(ArchiveIssueCode.MISSING_FIELD, path, "Required field is absent.")

private inline fun <T> checked(path: String, code: ArchiveIssueCode = ArchiveIssueCode.INVALID_DOMAIN_VALUE, block: () -> T): T =
    try { block() } catch (error: IllegalArgumentException) {
        unresolved(code, path, error.message ?: "Invalid value.")
    } catch (error: DateTimeException) {
        unresolved(code, path, error.message ?: "Invalid date-time.")
    }

private fun timestamp(text: String?, path: String): Instant = checked(path, ArchiveIssueCode.TEMPORAL_CONTEXT) {
    UtcTimestamps.parse(field(text, path))
}

private class Reconstructor {
    private val identities = mutableSetOf<String>()

    fun reconstruct(snapshot: ArchiveSnapshot, known: DomainArchive?): DomainArchive {
        version(snapshot.schemaVersion, ArchiveContractVersions.SCHEMA, "schemaVersion")
        version(snapshot.scoringContractVersion, ArchiveContractVersions.SCORING, "scoringContractVersion")
        val profiles = linkedMapOf<String, TargetProfileSnapshot>()
        snapshot.profiles.forEachIndexed { index, profile ->
            val path = "profiles[$index]"
            val definition = profile.definition ?: unresolved(ArchiveIssueCode.UNKNOWN_PROFILE, path,
                "A complete target definition is required; an identity alone is insufficient.")
            val old = profiles[definition.uuid]
            if (old != null && old != profile) unresolved(ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, path,
                "Different profile definitions or editions share the same UUID.")
            identity(definition.uuid, "$path.definition.uuid")
            val canonical = BuiltInTargetRegistry.find(definition.uuid)
            if (canonical != null) {
                if (canonical.definition != definition || canonical.regulatoryEdition != profile.regulatoryEdition) {
                    unresolved(ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, path,
                        "Published target identity requires its exact complete definition and regulatory edition.")
                }
            } else if (definition.isBuiltIn) {
                unresolved(ArchiveIssueCode.UNKNOWN_PROFILE, path, "Unrecognized built-in identity; no substitution is allowed.")
            }
            if (profile.regulatoryEdition != null && profile.regulatoryEdition.isBlank()) {
                unresolved(ArchiveIssueCode.INVALID_DOMAIN_VALUE, path, "A supplied regulatory edition must not be blank.")
            }
            profiles[definition.uuid] = profile
        }
        val targets = profiles.mapValues { field(it.value.definition, "profiles") }
        val sessions = snapshot.sessions.mapIndexed { index, session -> session(session, targets, "sessions[$index]") }
        val archive = DomainArchive(profiles.values.toList(), sessions)
        if (known != null) compareKnown(known, archive)
        return archive
    }

    private fun version(value: Int?, supported: Int, path: String) {
        if (field(value, path) != supported) unresolved(ArchiveIssueCode.UNSUPPORTED_VERSION, path,
            "Only version $supported is supported for $path. No guessed migration or fallback is available.")
    }

    private fun uuid(value: String, path: String) = checked(path, ArchiveIssueCode.INVALID_UUID) {
        val parsed = UUID.fromString(value)
        require(parsed.version() == 4 && parsed.variant() == 2 && parsed.toString() == value) {
            "A canonical lowercase RFC 4122 UUID v4 is required."
        }
    }

    private fun identity(value: String, path: String) {
        uuid(value, path)
        if (!identities.add(value)) unresolved(ArchiveIssueCode.DUPLICATE_UUID, path,
            "An entity UUID must occur only once in the archive, including across sessions and entity kinds.")
    }

    private fun target(uuid: String, targets: Map<String, TargetType>, path: String): TargetType = targets[uuid]
        ?: unresolved(ArchiveIssueCode.UNKNOWN_PROFILE, path, "Referenced target has no complete definition in this archive.")

    private fun civil(value: CivilDateSnapshot?, path: String): SessionDateTime {
        val date = field(value, path)
        val local = field(date.localDateTime, "$path.localDateTime")
        val zone = field(date.zoneId, "$path.zoneId")
        val offset = field(date.offset, "$path.offset")
        return checked(path, ArchiveIssueCode.CIVIL_CONTEXT_CONFLICT) {
            require(!Regex("-00(?::?00){0,2}").matches(offset)) { "Unknown civil offset." }
            SessionDateTime.of(LocalDateTime.parse(local), ZoneId.of(zone), ZoneOffset.of(offset))
        }
    }

    private fun session(value: SessionSnapshot, targets: Map<String, TargetType>, path: String): Session {
        identity(value.uuid, "$path.uuid")
        value.bowUuid?.let { uuid(it, "$path.bowUuid") }
        value.arrowSetUuid?.let { uuid(it, "$path.arrowSetUuid") }
        val target = target(value.targetTypeUuid, targets, "$path.targetTypeUuid")
        val date = civil(value.date, "$path.date")
        val ends = value.ends.mapIndexed { index, end -> end(end, target, "$path.ends[$index]") }
        return checked(path) {
            Session(value.uuid, date, value.status,
                value.startedAt?.let { timestamp(it, "$path.startedAt") },
                value.endedAt?.let { timestamp(it, "$path.endedAt") }, value.plannedEndCount,
                value.title, target, value.distanceM, value.arrowCountMode, value.arrowCountPerEnd,
                value.isIndoor, value.scoringMode, value.bowUuid, value.arrowSetUuid, value.locationName, value.notes,
                ends, timestamp(value.createdAt, "$path.createdAt"), timestamp(value.updatedAt, "$path.updatedAt"))
        }
    }

    fun end(value: EndSnapshot, target: TargetType, path: String): End {
        identity(value.uuid, "$path.uuid")
        val impacts = value.impacts.mapIndexed { index, impact -> impact(impact, target, "$path.impacts[$index]") }
        return checked(path) {
            End(value.uuid, value.index, value.status, impacts, value.notes,
                timestamp(value.createdAt, "$path.createdAt"), timestamp(value.updatedAt, "$path.updatedAt"),
                value.validatedAt?.let { timestamp(it, "$path.validatedAt") })
        }
    }

    private fun impact(value: ImpactSnapshot, target: TargetType, path: String): ArrowImpact {
        identity(value.uuid, "$path.uuid")
        if (value.targetTypeUuid != target.uuid) unresolved(ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, path,
            "Impact target identity differs from its session's profile.")
        val created = timestamp(value.createdAt, "$path.createdAt")
        val firstPoint = value.repositions.firstOrNull()?.previousPoint ?: value.point
        var replay = checked(path) { ArrowImpact(uuid = value.uuid, index = value.index, point = firstPoint,
            inputMode = value.inputMode, note = value.note, createdAt = created, updatedAt = created, targetType = target) }
        value.repositions.forEachIndexed { index, change ->
            val tracePath = "$path.repositions[$index]"
            if (change.impactUuid != value.uuid || timestamp(change.impactCreatedAt, "$tracePath.impactCreatedAt") != created ||
                change.inputMode != value.inputMode || change.previousPoint != replay.point) {
                unresolved(ArchiveIssueCode.HISTORY_CONFLICT, tracePath, "Reposition context or spatial path is inconsistent.")
            }
            replay = applyDecision(replay, change.previousDecision,
                timestamp(change.previousUpdatedAt, "$tracePath.previousUpdatedAt"), "$tracePath.previousDecision")
            // Only the existing domain operation can issue opaque evidence. No raw trace factory is exposed.
            replay = checked(tracePath, ArchiveIssueCode.HISTORY_CONFLICT) {
                replay.reposition(change.newPoint, timestamp(change.changedAt, "$tracePath.changedAt"), change.reason)
            }
        }
        if (replay.point != value.point) unresolved(ArchiveIssueCode.HISTORY_CONFLICT, path,
            "The last reposition does not reach the received current point.")
        return applyDecision(replay, value.decision, timestamp(value.updatedAt, "$path.updatedAt"), "$path.decision")
    }

    private fun applyDecision(impact: ArrowImpact, snapshot: DecisionSnapshot?, revision: Instant, path: String): ArrowImpact {
        val value = snapshot ?: unresolved(ArchiveIssueCode.MISSING_PROVENANCE, path, "Decision provenance is required, even for geometry.")
        val kind = value.kind ?: unresolved(ArchiveIssueCode.MISSING_PROVENANCE, "$path.kind", "Decision kind is required.")
        val selected = field(value.selected, "$path.selected")
        val provenance = checked(path) { ScoreDecisionProvenance(kind,
            value.decidedAt?.let { timestamp(it, "$path.decidedAt") }, value.boundary, value.reason) }
        val request = when (kind) {
            ScoreDecisionKind.GEOMETRY -> ScoreDecisionRequest.AcceptGeometry
            ScoreDecisionKind.BOUNDARY_JUDGMENT -> ScoreDecisionRequest.JudgeBoundary(
                field(provenance.boundary, "$path.boundary"), selected, field(provenance.decidedAt, "$path.decidedAt"))
            ScoreDecisionKind.EXCEPTIONAL_CORRECTION -> ScoreDecisionRequest.ExceptionalCorrection(
                selected, field(provenance.reason, "$path.reason"), field(provenance.decidedAt, "$path.decidedAt"))
        }
        val decision = checked(path) { FinalScoreDecision.resolve(impact.targetType, impact.point, request) }
        if (selected != decision.value) unresolved(ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE, "$path.selected",
            "Stored final value contradicts the contextual decision.")
        val revised = checked(path) {
            if (decision == impact.finalScoreDecision) impact.copy(updatedAt = revision)
            else impact.withFinalScore(impact.targetType, request, revision)
        }
        value.assertions?.let { received ->
            checked("$path.assertions", ArchiveIssueCode.CONTRADICTORY_DERIVED_VALUE) {
                require(received.distanceFromCenter == null || received.distanceFromCenter == decision.distanceFromCenter) { "Distance mismatch." }
                require(received.scoreCalculated == null || received.scoreCalculated == decision.scoreCalculated) { "Geometric score mismatch." }
                require(received.scoreFinal == null || received.scoreFinal == decision.scoreFinal) { "Final score mismatch." }
                require(received.geometricIsX == null || received.geometricIsX == decision.geometricValue.isX) { "Geometric X mismatch." }
                require(received.isX == null || received.isX == decision.isX) { "Final X mismatch." }
                require(received.isMiss == null || received.isMiss == decision.isMiss) { "Final M mismatch." }
                require(received.isCordDecisionManual == null || received.isCordDecisionManual == decision.isCordDecisionManual) { "Manual flag mismatch." }
            }
        }
        return revised
    }
}

/** No last-writer-wins: every differing known identity requires a separate, explicit resolution. */
private fun compareKnown(known: DomainArchive, received: DomainArchive) {
    val profiles = known.profiles.associateBy { it.definition!!.uuid }
    received.profiles.forEach { profile ->
        val uuid = profile.definition!!.uuid
        if (profiles[uuid] != null && profiles[uuid] != profile) unresolved(ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT,
            "profiles[$uuid]", "A known immutable profile identity has different content or edition.")
    }
    // Check evidence before generic divergence, including removal and reparenting across aggregate levels.
    val oldImpacts = known.sessions.flatMap { it.ends }.flatMap { it.impacts }.associateBy { it.uuid }
    received.sessions.forEach { session ->
        val impacts = session.ends.flatMap { it.impacts }
        val previous = known.sessions.singleOrNull { it.uuid == session.uuid }
        previous?.ends?.flatMap { it.impacts }?.filter { it.repositions.isNotEmpty() }?.forEach { old ->
            if (impacts.none { it.uuid == old.uuid }) unresolved(ArchiveIssueCode.HISTORY_CONFLICT,
                "sessions[${session.uuid}]", "Received session removes an impact carrying known reposition evidence.")
        }
        impacts.forEach impactLoop@{ impact ->
            val old = oldImpacts[impact.uuid] ?: return@impactLoop
            if (impact.repositions.size < old.repositions.size ||
                impact.repositions.take(old.repositions.size) != old.repositions) {
                unresolved(ArchiveIssueCode.HISTORY_CONFLICT, "impacts[${impact.uuid}].repositions",
                    "Received revision discards or rewrites known reposition evidence.")
            }
        }
    }
    val oldEntities = entities(known)
    entities(received).forEach { (uuid, entity) ->
        if (oldEntities[uuid] != null && oldEntities[uuid] != entity) unresolved(ArchiveIssueCode.DIVERGENT_REVISION,
            "entities[$uuid]", "Known identity has divergent content or ownership. updatedAt does not prove causal descent.")
    }
}

private data class Entity(val kind: String, val parent: String?, val value: Any)

private fun entities(archive: DomainArchive): Map<String, Entity> = buildMap {
    archive.profiles.forEach { put(it.definition!!.uuid, Entity("profile", null, it)) }
    archive.sessions.forEach { session ->
        put(session.uuid, Entity("session", null, session))
        session.ends.forEach { end ->
            put(end.uuid, Entity("end", session.uuid, end))
            end.impacts.forEach { put(it.uuid, Entity("impact", end.uuid, it)) }
        }
    }
}

/** Shared internal component validation; no synthetic homogeneous session or public V1 projection. */
@JvmSynthetic
internal fun reconstructArchiveEnd(
    value: EndSnapshot, target: TargetType, path: String, onIssue: (ArchiveIssue) -> Nothing,
): End = try { Reconstructor().end(value, target, path) }
catch (error: UnresolvedArchive) { onIssue(error.issue) }
