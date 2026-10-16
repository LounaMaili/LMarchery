package com.lmarchery.core.database

import com.lmarchery.core.scoring.archive.*

/** Immutable informational candidate, never an authorization. Store revalidates archive and plan before INSERT. */
class ArchiveImportPreview(val archive: DomainArchive, plan: ArchiveImportPlan) {
    private fun <T> owned(values: List<T>): List<T> = java.util.Collections.unmodifiableList(values.toList())
    val plan = plan.copy(newSessionUuids = owned(plan.newSessionUuids),
        identicalSessionUuids = owned(plan.identicalSessionUuids), newProfileUuids = owned(plan.newProfileUuids),
        conflicts = owned(plan.conflicts))
}

sealed interface ArchiveImportPreviewResult {
    data class Ready(val preview: ArchiveImportPreview) : ArchiveImportPreviewResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveImportPreviewResult
    data class TechnicalFailure(val cause: Exception) : ArchiveImportPreviewResult
}

sealed interface ArchiveImportResult {
    data class Imported(val added: Int, val identical: Int) : ArchiveImportResult
    data class Conflict(val diagnostic: StorageDiagnostic) : ArchiveImportResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : ArchiveImportResult
    data class TechnicalFailure(val cause: Exception) : ArchiveImportResult
}
