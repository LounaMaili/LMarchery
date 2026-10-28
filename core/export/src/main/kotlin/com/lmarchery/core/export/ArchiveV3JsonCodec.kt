package com.lmarchery.core.export

import com.lmarchery.core.scoring.archive.v3.*

enum class ArchiveV3JsonIssueKind { JSON, DOMAIN, ROUND_TRIP }

/** The original business diagnostic and its DTO path are retained without reinterpretation. */
data class ArchiveV3JsonIssue(
    val kind: ArchiveV3JsonIssueKind,
    val code: String,
    val path: String,
    val detail: String,
    val domainIssue: ArchiveV3Issue? = null,
)

class ArchiveV3JsonException(val issue: ArchiveV3JsonIssue) :
    IllegalArgumentException("${issue.code} at ${issue.path}: ${issue.detail}")

sealed interface ArchiveV3JsonRead {
    data class Accepted(val snapshot: ArchiveV3Snapshot, val archive: DomainArchiveV3) : ArchiveV3JsonRead
    data class Rejected(val issue: ArchiveV3JsonIssue) : ArchiveV3JsonRead
}

/** Standalone V3 only. No version dispatch, historical conversion, Room or SAF integration. */
class ArchiveV3JsonCodec(
    private val limits: JsonLimits = JsonLimits(),
    private val archiveLimits: ArchiveV3Limits = ArchiveV3Limits(),
) {
    init {
        val json = JsonLimits(); val domain = ArchiveV3Limits()
        require(limits.maxBytes <= json.maxBytes && limits.maxDepth <= json.maxDepth &&
            limits.maxValues <= json.maxValues && limits.maxArrayElements <= json.maxArrayElements &&
            limits.maxStringChars <= json.maxStringChars) { "V3 JSON budgets can only be tightened." }
        require(archiveLimits.maxCollectionElements <= domain.maxCollectionElements &&
            archiveLimits.maxTotalElements <= domain.maxTotalElements && archiveLimits.maxTextLength <= domain.maxTextLength) {
            "V3 business budgets can only be tightened."
        }
    }

    fun decode(bytes: ByteArray): ArchiveV3JsonRead = try {
        val tree = parseStrict(bytes, limits)
        if (readSchemaVersion(tree) != ArchiveV3Contract.SCHEMA)
            invalid("$.schemaVersion", "This standalone codec accepts only archive schema 3.", "UNSUPPORTED_VERSION")
        val snapshot = readArchiveV3Snapshot(tree, "$")
        // All keys/types have been checked before inspecting the Float lexemes of this schema.
        validateV3FloatLexemes(tree)
        ArchiveV3JsonRead.Accepted(snapshot, validate(snapshot))
    } catch (error: ArchiveJsonException) { ArchiveV3JsonRead.Rejected(error.issue.v3()) }
      catch (error: ArchiveV3JsonException) { ArchiveV3JsonRead.Rejected(error.issue) }

    fun encode(archive: DomainArchiveV3): ByteArray {
        val snapshot = archive.toSnapshot()
        validate(snapshot)
        val bytes = try { writeStrict(jsonV3(snapshot), limits) }
        catch (error: ArchiveJsonException) { throw ArchiveV3JsonException(error.issue.v3()) }
        // Re-read enforces every parser budget on output too; no success before complete equality.
        val restored = when (val read = decode(bytes)) {
            is ArchiveV3JsonRead.Accepted -> read
            is ArchiveV3JsonRead.Rejected -> throw ArchiveV3JsonException(read.issue)
        }
        if (restored.snapshot != snapshot || restored.archive != archive)
            throw ArchiveV3JsonException(ArchiveV3JsonIssue(ArchiveV3JsonIssueKind.ROUND_TRIP,
                "ROUND_TRIP", "$", "Encoded V3 archive differs from its complete source."))
        return bytes
    }

    private fun validate(snapshot: ArchiveV3Snapshot): DomainArchiveV3 =
        when (val result = reconstructArchiveV3(snapshot, limits = archiveLimits)) {
            is ArchiveV3Reconstruction.Accepted -> result.archive
            is ArchiveV3Reconstruction.NeedsIntervention -> throw ArchiveV3JsonException(ArchiveV3JsonIssue(
                ArchiveV3JsonIssueKind.DOMAIN, result.issue.code.name, "$.${result.issue.path}", result.issue.detail, result.issue))
        }
}

private fun JsonIssue.v3() = ArchiveV3JsonIssue(ArchiveV3JsonIssueKind.JSON, code, path, detail)
