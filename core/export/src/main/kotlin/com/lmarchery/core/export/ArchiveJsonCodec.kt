package com.lmarchery.core.export

import com.lmarchery.core.scoring.archive.*

sealed interface ArchiveJsonRead {
    data class Accepted(val snapshot: ArchiveSnapshot, val archive: DomainArchive) : ArchiveJsonRead
    data class Rejected(val issue: JsonIssue) : ArchiveJsonRead
}

/** Successful variants preserve whether equipment was provided, including explicitly empty V2 lists. */
sealed interface VersionedArchiveJsonRead {
    /** Equipment is NOT PROVIDED by V1: this is never a request to empty a local catalog. */
    data class V1(val snapshot: ArchiveSnapshot, val archive: DomainArchive) : VersionedArchiveJsonRead
    data class V2(val snapshot: ArchiveV2Snapshot, val archive: DomainArchiveV2) : VersionedArchiveJsonRead
    data class Rejected(val issue: JsonIssue) : VersionedArchiveJsonRead
}

/** JVM/Android codec only, never an import or an authorization to overwrite Room. */
class ArchiveJsonCodec(private val limits: JsonLimits = JsonLimits()) {
    fun encode(archive: DomainArchive): ByteArray {
        val snapshot = archive.toSnapshot()
        validate(snapshot)
        val bytes = try { writeStrict(json(snapshot), limits) }
        catch (e: ArchiveJsonException) { throw e }
        catch (_: Exception) { invalid("$", "Archive cannot be encoded as strict UTF-8 JSON.") }
        // Also enforces depth, counts, strings and Unicode for trusted sources before returning bytes.
        val restored = decode(bytes)
        if (restored !is ArchiveJsonRead.Accepted || restored.archive.toSnapshot() != snapshot)
            throw ArchiveJsonException((restored as? ArchiveJsonRead.Rejected)?.issue
                ?: JsonIssue("ROUND_TRIP", "$", "Encoded archive does not match its source."))
        return bytes
    }

    /** V2 has a distinct domain type: overload resolution cannot select the lossy V1 encoder. */
    fun encode(archive: DomainArchiveV2): ByteArray {
        val snapshot = archive.toSnapshot()
        validateV2(snapshot)
        val bytes = try { writeStrict(jsonV2(snapshot), limits) }
        catch (error: ArchiveJsonException) { throw error }
        catch (_: Exception) { invalid("$", "V2 archive cannot be encoded as strict UTF-8 JSON.") }
        val restored = decodeVersioned(bytes)
        if (restored !is VersionedArchiveJsonRead.V2 || restored.snapshot != snapshot || restored.archive != archive)
            throw ArchiveJsonException((restored as? VersionedArchiveJsonRead.Rejected)?.issue
                ?: JsonIssue("ROUND_TRIP", "$", "Encoded V2 archive does not match its complete source."))
        return bytes
    }

    /** Read-only versioned contract. Existing Room/SAF APIs remain V1-only and reject a V2 file. */
    fun decodeVersioned(bytes: ByteArray): VersionedArchiveJsonRead = try {
        val tree = parseStrict(bytes, limits)
        when (readSchemaVersion(tree)) {
            ArchiveContractVersions.SCHEMA -> {
                val snapshot = readArchiveSnapshot(tree, "$")
                VersionedArchiveJsonRead.V1(snapshot, validate(snapshot))
            }
            ArchiveContractVersions.SCHEMA_V2 -> {
                val snapshot = readArchiveV2Snapshot(tree, "$")
                VersionedArchiveJsonRead.V2(snapshot, validateV2(snapshot))
            }
            else -> invalid("$.schemaVersion", "Unsupported archive schema; no downgrade is available.", "UNSUPPORTED_VERSION")
        }
    } catch (error: ArchiveJsonException) { VersionedArchiveJsonRead.Rejected(error.issue) }

    private fun validateV2(snapshot: ArchiveV2Snapshot): DomainArchiveV2 = when (val result = reconstructArchiveV2(snapshot)) {
        is ArchiveV2Reconstruction.Accepted -> result.archive
        is ArchiveV2Reconstruction.NeedsIntervention -> throw ArchiveJsonException(JsonIssue(
            result.issue.code.name, "$.${result.issue.path}", result.issue.detail))
    }

    internal fun decodeSnapshot(bytes: ByteArray): ArchiveSnapshot = readArchiveSnapshot(parseStrict(bytes, limits), "$")

    fun decode(bytes: ByteArray): ArchiveJsonRead = try {
        val snapshot = decodeSnapshot(bytes)
        ArchiveJsonRead.Accepted(snapshot, validate(snapshot))
    } catch (e: ArchiveJsonException) { ArchiveJsonRead.Rejected(e.issue) }

    private fun validate(snapshot: ArchiveSnapshot): DomainArchive = when (val result = reconstructArchive(snapshot)) {
        is ArchiveReconstruction.Accepted -> result.archive
        is ArchiveReconstruction.NeedsIntervention -> throw ArchiveJsonException(
            JsonIssue(result.issue.code.name, result.issue.path, result.issue.detail))
    }
}
