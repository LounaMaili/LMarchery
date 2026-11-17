package com.lmarchery.core.database

import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.archive.v4.*
import java.util.concurrent.CancellationException

/** Only a read-only preview constructs this exact, immutable confirmation candidate. */
class ArchiveV4ImportPreview internal constructor(
    val archive: DomainArchiveV4,
    val plan: ArchiveV3ImportPlan,
    internal val baseline: DomainArchiveV4,
    fingerprint: Map<String, List<Byte>>,
) {
    internal val baselineFingerprint = ownFingerprint(fingerprint)
}

sealed interface ArchiveV4PreviewResult {
    data class Ready(val preview: ArchiveV4ImportPreview) : ArchiveV4PreviewResult
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : ArchiveV4PreviewResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV4PreviewResult
}

data class ArchiveV4Counts(val sessions: Int, val profiles: Int, val ends: Int, val impacts: Int, val bows: Int, val arrowSets: Int)
enum class ArchiveV4CommitOutcome { NOT_ATTEMPTED, UNCONFIRMED }

/** Every result retains the exact candidate. No result authorizes an automatic retry/rebase. */
sealed interface ArchiveV4ImportResult {
    val preview: ArchiveV4ImportPreview
    data class Imported(override val preview: ArchiveV4ImportPreview, val added: ArchiveV4Counts,
        val identical: ArchiveV4Counts) : ArchiveV4ImportResult
    data class AlreadyPresent(override val preview: ArchiveV4ImportPreview, val identical: ArchiveV4Counts,
        val emptyArchive: Boolean) : ArchiveV4ImportResult
    data class Conflict(override val preview: ArchiveV4ImportPreview, val diagnostic: StorageDiagnostic) : ArchiveV4ImportResult
    data class NeedsIntervention(override val preview: ArchiveV4ImportPreview, val diagnostic: ArchiveV3StorageDiagnostic,
        val commitOutcome: ArchiveV4CommitOutcome) : ArchiveV4ImportResult
    /** No business DML attempted by this call; does not establish the outcome of an earlier attempt. */
    data class TechnicalFailure(override val preview: ArchiveV4ImportPreview, val cause: Exception) : ArchiveV4ImportResult
    /** DML attempted, final commit confirmation unavailable. Re-read and explicitly preview again. */
    data class CommitUnconfirmed(override val preview: ArchiveV4ImportPreview, val cause: Exception) : ArchiveV4ImportResult
}

class ArchiveV4ImportCancelled(val preview: ArchiveV4ImportPreview, val commitOutcome: ArchiveV4CommitOutcome,
    cause: CancellationException) : CancellationException("V4 import cancelled; commit outcome: $commitOutcome") {
    init { initCause(cause) }
}
