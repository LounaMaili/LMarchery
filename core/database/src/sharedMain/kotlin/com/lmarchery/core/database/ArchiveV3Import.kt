package com.lmarchery.core.database

import com.lmarchery.core.scoring.archive.v3.*
import java.util.concurrent.CancellationException

/** Only a read-only preview constructs this exact, immutable confirmation candidate. */
class ArchiveV3ImportPreview internal constructor(
    val archive: DomainArchiveV3,
    val plan: ArchiveV3ImportPlan,
    internal val baseline: DomainArchiveV3,
    fingerprint: Map<String, List<Byte>>,
) {
    internal val baselineFingerprint = ownFingerprint(fingerprint)
}

sealed interface ArchiveV3PreviewResult {
    data class Ready(val preview: ArchiveV3ImportPreview) : ArchiveV3PreviewResult
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : ArchiveV3PreviewResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV3PreviewResult
}

data class ArchiveV3Counts(val sessions: Int, val profiles: Int, val ends: Int, val impacts: Int, val bows: Int, val arrowSets: Int)
enum class ArchiveV3CommitOutcome { NOT_ATTEMPTED, UNCONFIRMED }

/** Every result retains the exact candidate. No result authorizes an automatic retry/rebase. */
sealed interface ArchiveV3ImportResult {
    val preview: ArchiveV3ImportPreview
    data class Imported(override val preview: ArchiveV3ImportPreview, val added: ArchiveV3Counts,
        val identical: ArchiveV3Counts) : ArchiveV3ImportResult
    data class AlreadyPresent(override val preview: ArchiveV3ImportPreview, val identical: ArchiveV3Counts,
        val emptyArchive: Boolean) : ArchiveV3ImportResult
    data class Conflict(override val preview: ArchiveV3ImportPreview, val diagnostic: StorageDiagnostic) : ArchiveV3ImportResult
    data class NeedsIntervention(override val preview: ArchiveV3ImportPreview, val diagnostic: ArchiveV3StorageDiagnostic,
        val commitOutcome: ArchiveV3CommitOutcome) : ArchiveV3ImportResult
    /** No business DML attempted by this call; does not establish the outcome of an earlier attempt. */
    data class TechnicalFailure(override val preview: ArchiveV3ImportPreview, val cause: Exception) : ArchiveV3ImportResult
    /** DML attempted, final commit confirmation unavailable. Re-read and explicitly preview again. */
    data class CommitUnconfirmed(override val preview: ArchiveV3ImportPreview, val cause: Exception) : ArchiveV3ImportResult
}

class ArchiveV3ImportCancelled(val preview: ArchiveV3ImportPreview, val commitOutcome: ArchiveV3CommitOutcome,
    cause: CancellationException) : CancellationException("V3 import cancelled; commit outcome: $commitOutcome") {
    init { initCause(cause) }
}
