package com.lmarchery.core.database

import com.lmarchery.core.scoring.archive.ArchiveV2ImportPlan
import com.lmarchery.core.scoring.archive.DomainArchiveV2

sealed interface ArchiveV2ReadResult {
    data class Found(val archive: DomainArchiveV2) : ArchiveV2ReadResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveV2ReadResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV2ReadResult
}

/** Exact immutable source and database snapshot. Only previewImportV2 creates a confirmation candidate. */
class ArchiveV2ImportPreview internal constructor(
    val archive: DomainArchiveV2,
    val plan: ArchiveV2ImportPlan,
    internal val baseline: DomainArchiveV2,
)

sealed interface ArchiveV2PreviewResult {
    data class Ready(val preview: ArchiveV2ImportPreview) : ArchiveV2PreviewResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveV2PreviewResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV2PreviewResult
}

data class ArchiveV2Counts(val sessions: Int, val profiles: Int, val bows: Int, val arrowSets: Int)

/** A technical failure does not prove that COMMIT was never acquired. Re-read and preview again, never auto-retry. */
sealed interface ArchiveV2ImportResult {
    data class Imported(val added: ArchiveV2Counts, val identical: ArchiveV2Counts) : ArchiveV2ImportResult
    data class AlreadyPresent(val identical: ArchiveV2Counts, val emptyArchive: Boolean) : ArchiveV2ImportResult
    data class Conflict(val diagnostic: StorageDiagnostic) : ArchiveV2ImportResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveV2ImportResult
    data class TechnicalFailure(val cause: Exception, val commitOutcome: CommitOutcome = CommitOutcome.UNCONFIRMED) : ArchiveV2ImportResult
    enum class CommitOutcome { UNCONFIRMED }
}
