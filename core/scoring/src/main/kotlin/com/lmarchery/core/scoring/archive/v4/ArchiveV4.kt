package com.lmarchery.core.scoring.archive.v4

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import java.util.Collections
import java.util.UUID

object ArchiveV4Contract { const val SCHEMA = 4; const val SCORING = 1 }

/** Untrusted, all fields explicit. Shared V3 child records never carry implicit cancellation defaults. */
data class ArchiveV4Snapshot(val schemaVersion: Int?, val scoringContractVersion: Int?,
    val profiles: List<TargetProfileSnapshot>?, val sessions: List<ConfiguredSessionSnapshot>?,
    val bows: List<BowSnapshot>?, val arrowSets: List<ArrowSetSnapshot>?,
    val cancellations: List<ImpactCancellationSnapshot>?)

data class ImpactCancellationSnapshot(val endUuid: String?, val impactUuid: String?, val cancelledAt: String?,
    val reason: ImpactCancellationReason?, val comment: String?)

class DomainArchiveV4 internal constructor(profiles: List<TargetProfileSnapshot>, sessions: List<ConfiguredSession>,
    bows: List<Bow>, arrowSets: List<ArrowSet>) : ArchiveGraph {
    override val profiles = Collections.unmodifiableList(profiles.toList())
    override val sessions = Collections.unmodifiableList(sessions.toList())
    override val bows = Collections.unmodifiableList(bows.toList())
    override val arrowSets = Collections.unmodifiableList(arrowSets.toList())
    fun toSnapshot() = snapshotArchiveV4(profiles, sessions, bows, arrowSets)
    override fun equals(other: Any?): Boolean = other is DomainArchiveV4 && profiles == other.profiles &&
        sessions == other.sessions && bows == other.bows && arrowSets == other.arrowSets
    override fun hashCode(): Int = arrayOf(profiles, sessions, bows, arrowSets).contentHashCode()
}

fun snapshotArchiveV4(profiles: List<TargetProfileSnapshot>, sessions: List<ConfiguredSession>,
    bows: List<Bow>, arrowSets: List<ArrowSet>): ArchiveV4Snapshot {
    val payload = snapshotArchiveV3Payload(profiles, sessions, bows, arrowSets)
    val proofs = sessions.flatMap { s -> s.ends.flatMap { e -> e.end.cancellations.map { p ->
        ImpactCancellationSnapshot(p.endUuid, p.impactUuid, UtcTimestamps.format(p.cancelledAt), p.reason, p.comment)
    } } }
    return ArchiveV4Snapshot(4, 1, payload.profiles, payload.sessions, payload.bows, payload.arrowSets, proofs)
}

fun convertArchiveV3ToV4(archive: DomainArchiveV3): DomainArchiveV4 =
    DomainArchiveV4(archive.profiles, archive.sessions, archive.bows, archive.arrowSets)

data class ArchiveV4Issue(val code: ArchiveV3IssueCode, val path: String, val detail: String)
sealed interface ArchiveV4Reconstruction {
    data class Accepted(val archive: DomainArchiveV4) : ArchiveV4Reconstruction
    data class NeedsIntervention(val source: ArchiveV4Snapshot, val issue: ArchiveV4Issue) : ArchiveV4Reconstruction
}
private class InvalidV4(val issue: ArchiveV4Issue) : RuntimeException(issue.detail)
private fun fail(code: ArchiveV3IssueCode, path: String, detail: String): Nothing = throw InvalidV4(ArchiveV4Issue(code, path, detail))
private fun <T : Any> required(value: T?, path: String): T = value ?: fail(ArchiveV3IssueCode.MISSING_FIELD, path, "Required value is absent.")

/** Whole reconstruction. A received cancellation is not permission to revise known stored content. */
fun reconstructArchiveV4(source: ArchiveV4Snapshot, known: DomainArchiveV4? = null,
    limits: ArchiveV3Limits = ArchiveV3Limits()): ArchiveV4Reconstruction = try {
    if (required(source.schemaVersion, "schemaVersion") != 4 || required(source.scoringContractVersion, "scoringContractVersion") != 1)
        fail(ArchiveV3IssueCode.UNSUPPORTED_VERSION, "schemaVersion/scoringContractVersion", "Only archive 4 / scoring 1 are supported.")
    val proofs = required(source.cancellations, "cancellations").toList()
    if (proofs.size > limits.maxCollectionElements || proofs.size.toLong() >= limits.maxTotalElements)
        fail(ArchiveV3IssueCode.LIMIT, "cancellations", "Cancellation budget exceeded.")
    val payload = ArchiveV3Snapshot(3, source.scoringContractVersion, source.profiles, source.sessions, source.bows, source.arrowSets)
    val base = when (val r = reconstructArchiveV3(payload, limits = limits.copy(maxTotalElements = limits.maxTotalElements - proofs.size))) {
        is ArchiveV3Reconstruction.Accepted -> r.archive
        is ArchiveV3Reconstruction.NeedsIntervention -> fail(r.issue.code, r.issue.path, r.issue.detail)
    }
    val ends = base.sessions.flatMap { it.ends }.associateBy { it.uuid }
    val byEnd = linkedMapOf<String, MutableList<ImpactCancellation>>()
    val ids = mutableSetOf<String>()
    proofs.forEachIndexed { i, p ->
        val path = "cancellations[$i]"
        fun id(value: String?, field: String): String = required(value, "$path.$field").also { raw ->
            val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
            if (uuid == null || uuid.toString() != raw || uuid.version() != 4 || uuid.variant() != 2)
                fail(ArchiveV3IssueCode.INVALID_UUID, "$path.$field", "Canonical UUID v4 required.")
        }
        val endId = id(p.endUuid, "endUuid"); val impactId = id(p.impactUuid, "impactUuid")
        if (!ids.add(impactId)) fail(ArchiveV3IssueCode.DUPLICATE_UUID, "$path.impactUuid", "One cancellation per impact.")
        val owner = ends[endId] ?: fail(ArchiveV3IssueCode.WRONG_OWNER, "$path.endUuid", "Owner is absent.")
        val impact = owner.end.impacts.singleOrNull { it.uuid == impactId }
            ?: fail(ArchiveV3IssueCode.WRONG_OWNER, "$path.impactUuid", "Impact is not owned by this end.")
        val at = try { UtcTimestamps.parse(required(p.cancelledAt, "$path.cancelledAt")) }
            catch (e: IllegalArgumentException) { fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.cancelledAt", e.message ?: "Invalid date.") }
            catch (e: java.time.DateTimeException) { fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "$path.cancelledAt", e.message ?: "Invalid date.") }
        p.comment?.let { text ->
            if (text.length > limits.maxTextLength) fail(ArchiveV3IssueCode.LIMIT, "$path.comment", "Text budget exceeded.")
            var k = 0
            while (k < text.length) {
                val c = text[k++]
                if (Character.isHighSurrogate(c)) {
                    if (k == text.length || !Character.isLowSurrogate(text[k++])) fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "$path.comment", "Unpaired surrogate.")
                } else if (Character.isLowSurrogate(c)) fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "$path.comment", "Unpaired surrogate.")
            }
        }
        val proof = try { ImpactCancellation.forImpact(endId, impact, at, required(p.reason, "$path.reason"), p.comment) }
            catch (e: IllegalArgumentException) { fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, path, e.message ?: "Invalid proof.") }
        byEnd.getOrPut(endId) { mutableListOf() }.add(proof)
    }
    val sessions = base.sessions.mapIndexed { sessionIndex, s ->
        val captured = s.ends.map { e ->
            val end = e.end
            val rebuilt = try { End(end.uuid, end.index, end.status, end.impacts, end.notes, end.createdAt,
                end.updatedAt, end.validatedAt, byEnd[end.uuid].orEmpty()) }
                catch (error: IllegalArgumentException) { fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT, "ends[${end.uuid}].cancellations", error.message ?: "Invalid proof chronology.") }
            ConfiguredEnd(s.uuid, rebuilt, e.configuration, e.origin)
        }
        try { ConfiguredSession.rehydrate(s.uuid, s.metadata, s.initialConfiguration, s.initialConfigurationOrigin,
            s.status, s.startedAt, s.endedAt, captured, s.createdAt, s.updatedAt) }
        catch (error: ConfiguredSessionException) { fail(ArchiveV3IssueCode.TEMPORAL_CONTEXT,
            "sessions[$sessionIndex].${error.path}", error.message ?: "Invalid cancellation ownership or chronology.") }
    }
    val archive = DomainArchiveV4(base.profiles, sessions, base.bows, base.arrowSets)
    if (archive.toSnapshot().cancellations != proofs) fail(ArchiveV3IssueCode.INVALID_DOMAIN_VALUE, "cancellations",
        "Evidence must follow session/end source order, retaining each end's append order.")
    if (known != null) planArchiveImportV4(archive, known).conflicts.firstOrNull()?.let {
        fail(ArchiveV3IssueCode.DIVERGENT_REVISION, it.path, "Known content or cancellation differs; no automatic merge or reactivation.")
    }
    ArchiveV4Reconstruction.Accepted(archive)
} catch (error: InvalidV4) { ArchiveV4Reconstruction.NeedsIntervention(source, error.issue) }
