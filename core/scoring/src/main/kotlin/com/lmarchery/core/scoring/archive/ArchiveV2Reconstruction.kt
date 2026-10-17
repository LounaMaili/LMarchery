package com.lmarchery.core.scoring.archive

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.time.UtcTimestamps
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

sealed interface ArchiveV2Reconstruction {
    data class Accepted(val archive: DomainArchiveV2) : ArchiveV2Reconstruction
    data class NeedsIntervention(val source: ArchiveV2Snapshot, val issue: ArchiveIssue) : ArchiveV2Reconstruction
}

/** Independent all-or-nothing validation, not a merge planner or authorization to restore Room. */
fun reconstructArchiveV2(snapshot: ArchiveV2Snapshot): ArchiveV2Reconstruction = try {
    fun version(value: Int?, expected: Int, path: String) {
        if (required(value, path) != expected) fail(ArchiveIssueCode.UNSUPPORTED_VERSION, path,
            "Only version $expected is supported for $path; no fallback is available.")
    }
    version(snapshot.schemaVersion, ArchiveContractVersions.SCHEMA_V2, "schemaVersion")
    version(snapshot.scoringContractVersion, ArchiveContractVersions.SCORING, "scoringContractVersion")
    val profiles = required(snapshot.profiles, "profiles")
    val sessions = required(snapshot.sessions, "sessions")
    val bows = required(snapshot.bows, "bows")
    val arrows = required(snapshot.arrowSets, "arrowSets")
    // Reuse the complete existing profile/session/decision/history validator, not a JSON downgrade.
    val sessionContent = when (val result = reconstructArchive(ArchiveSnapshot(
        ArchiveContractVersions.SCHEMA, snapshot.scoringContractVersion, profiles, sessions))) {
        is ArchiveReconstruction.Accepted -> result.archive
        is ArchiveReconstruction.NeedsIntervention -> throw InvalidV2(result.issue)
    }
    val identities = buildSet {
        sessionContent.profiles.forEach { add(requireNotNull(it.definition).uuid) }
        sessionContent.sessions.forEach { session ->
            add(session.uuid)
            session.ends.forEach { end -> add(end.uuid); end.impacts.forEach { add(it.uuid) } }
        }
    }.toMutableSet()
    fun identity(value: String?, path: String): String {
        val text = required(value, path)
        checked(path, ArchiveIssueCode.INVALID_UUID) {
            val id = UUID.fromString(text)
            require(id.version() == 4 && id.variant() == 2 && id.toString() == text) {
                "A canonical lowercase RFC 4122 UUID v4 is required."
            }
        }
        if (!identities.add(text) || BuiltInTargetRegistry.find(text) != null) fail(ArchiveIssueCode.DUPLICATE_UUID,
            path, "UUID is already owned by another entity or reserved by a published profile.")
        return text
    }
    val validBows = bows.mapIndexed { index, value ->
        val path = "bows[$index]"
        val uuid = identity(value.uuid, "$path.uuid")
        val name = name(value.name, "$path.name")
        value.drawWeightLbs?.let { power -> checked("$path.drawWeightLbs") {
            require(power.isFinite() && power > 0f) { "Supplied draw weight must be finite and strictly positive." }
        } }
        val created = time(value.createdAt, "$path.createdAt")
        val updated = revision(value.updatedAt, created, "$path.updatedAt")
        checked(path) { Bow(uuid, name, value.type, value.drawWeightLbs, value.brand, value.model, value.notes, created, updated) }
    }
    val validArrows = arrows.mapIndexed { index, value ->
        val path = "arrowSets[$index]"
        val uuid = identity(value.uuid, "$path.uuid")
        val name = name(value.name, "$path.name")
        val created = time(value.createdAt, "$path.createdAt")
        val updated = revision(value.updatedAt, created, "$path.updatedAt")
        checked(path) { ArrowSet(uuid, name, value.spine, value.length, value.pointWeight, value.nock, value.vanes,
            value.material, value.notes, created, updated) }
    }
    ArchiveV2Reconstruction.Accepted(DomainArchiveV2(sessionContent, validBows, validArrows))
} catch (error: InvalidV2) {
    ArchiveV2Reconstruction.NeedsIntervention(snapshot, error.issue)
}

private class InvalidV2(val issue: ArchiveIssue) : RuntimeException(issue.detail)
private fun fail(code: ArchiveIssueCode, path: String, detail: String): Nothing = throw InvalidV2(ArchiveIssue(code, path, detail))
private fun <T : Any> required(value: T?, path: String): T = value
    ?: fail(ArchiveIssueCode.MISSING_FIELD, path, "Required field is absent.")
private inline fun <T> checked(path: String, code: ArchiveIssueCode = ArchiveIssueCode.INVALID_DOMAIN_VALUE, block: () -> T): T =
    try { block() }
    catch (error: IllegalArgumentException) { fail(code, path, error.message ?: "Invalid value.") }
    catch (error: DateTimeException) { fail(code, path, error.message ?: "Invalid timestamp.") }
private fun time(value: String?, path: String): Instant = checked(path, ArchiveIssueCode.TEMPORAL_CONTEXT) {
    UtcTimestamps.parse(required(value, path))
}
private fun revision(value: String?, created: Instant, path: String): Instant = time(value, path).also {
    checked(path, ArchiveIssueCode.TEMPORAL_CONTEXT) { require(!it.isBefore(created)) { "updatedAt must cover createdAt." } }
}
private fun name(value: String?, path: String): String = required(value, path).also {
    checked(path) { require(it.isNotBlank()) { "Equipment name must not be blank." } }
}
