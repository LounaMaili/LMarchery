package com.lmarchery.core.export

import com.lmarchery.core.scoring.archive.*

sealed interface ArchiveJsonRead {
    data class Accepted(val snapshot: ArchiveSnapshot, val archive: DomainArchive) : ArchiveJsonRead
    data class Rejected(val issue: JsonIssue) : ArchiveJsonRead
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
