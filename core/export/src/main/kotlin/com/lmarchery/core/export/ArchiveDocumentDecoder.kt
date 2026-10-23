package com.lmarchery.core.export

import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.archive.DomainArchiveV2
import com.lmarchery.core.scoring.archive.v3.DomainArchiveV3

/** Dispatch only: no conversion, lossy fallback, persistence or changes to published codecs. */
sealed interface ArchiveDocumentRead {
    data class V1(val archive: DomainArchive) : ArchiveDocumentRead
    data class V2(val archive: DomainArchiveV2) : ArchiveDocumentRead
    data class V3(val archive: DomainArchiveV3) : ArchiveDocumentRead
    data class Rejected(val code: String, val path: String, val detail: String,
        val historicalIssue: JsonIssue? = null, val v3Issue: ArchiveV3JsonIssue? = null) : ArchiveDocumentRead
}

class ArchiveDocumentDecoder(
    private val historical: ArchiveJsonCodec = ArchiveJsonCodec(),
    private val v3: ArchiveV3JsonCodec = ArchiveV3JsonCodec(),
) {
    fun decode(bytes: ByteArray): ArchiveDocumentRead = try {
        // The existing strict, bounded parser checks duplicate keys and the entire document.
        // Release this dispatch tree before reconstructing with the selected, unchanged codec.
        when (version(bytes)) {
            1, 2 -> when (val result = historical.decodeVersioned(bytes)) {
                is VersionedArchiveJsonRead.V1 -> ArchiveDocumentRead.V1(result.archive)
                is VersionedArchiveJsonRead.V2 -> ArchiveDocumentRead.V2(result.archive)
                is VersionedArchiveJsonRead.Rejected -> result.issue.rejected()
            }
            3 -> when (val result = v3.decode(bytes)) {
                is ArchiveV3JsonRead.Accepted -> ArchiveDocumentRead.V3(result.archive)
                is ArchiveV3JsonRead.Rejected -> result.issue.let {
                    ArchiveDocumentRead.Rejected(it.code, it.path, it.detail, v3Issue = it)
                }
            }
            else -> ArchiveDocumentRead.Rejected("UNSUPPORTED_VERSION", "$.schemaVersion", "Unsupported archive schema; no downgrade is available.")
        }
    } catch (error: ArchiveJsonException) { error.issue.rejected() }

    private fun version(bytes: ByteArray) = readSchemaVersion(parseStrict(bytes, JsonLimits()))
}

private fun JsonIssue.rejected() = ArchiveDocumentRead.Rejected(code, path, detail, historicalIssue = this)
