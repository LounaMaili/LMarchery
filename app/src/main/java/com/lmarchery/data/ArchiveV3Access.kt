package com.lmarchery.data

import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.archive.v3.*

fun interface ArchiveV3Source { suspend fun readArchiveV3(): ArchiveV3ReadResult }

/** Exact opaque Room preview; a displayed plan never authorizes construction of a SQL candidate. */
interface PreparedV3Import {
    val archive: DomainArchiveV3
    val plan: ArchiveV3ImportPlan
    suspend fun commit(): RestoreV3Result
}

sealed interface PreparedV3Result {
    data class Ready(val candidate: PreparedV3Import) : PreparedV3Result
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : PreparedV3Result
    data class TechnicalFailure(val cause: Exception) : PreparedV3Result
}

fun interface ArchiveV3Importer { suspend fun previewImportV3(archive: DomainArchiveV3): PreparedV3Result }

/** Android boundary mirrors the store outcomes; the coordinator retains the exact capability. */
sealed interface RestoreV3Result {
    data class Imported(val added: ArchiveV3Counts, val identical: ArchiveV3Counts) : RestoreV3Result
    data class AlreadyPresent(val identical: ArchiveV3Counts, val emptyArchive: Boolean) : RestoreV3Result
    data class Conflict(val diagnostic: StorageDiagnostic) : RestoreV3Result
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic, val outcome: ArchiveV3CommitOutcome) : RestoreV3Result
    data class TechnicalFailure(val cause: Exception) : RestoreV3Result
    data class CommitUnconfirmed(val cause: Exception) : RestoreV3Result
}

internal fun ArchiveV3ImportResult.androidResult(): RestoreV3Result = when (this) {
    is ArchiveV3ImportResult.Imported -> RestoreV3Result.Imported(added, identical)
    is ArchiveV3ImportResult.AlreadyPresent -> RestoreV3Result.AlreadyPresent(identical, emptyArchive)
    is ArchiveV3ImportResult.Conflict -> RestoreV3Result.Conflict(diagnostic)
    is ArchiveV3ImportResult.NeedsIntervention -> RestoreV3Result.NeedsIntervention(diagnostic, commitOutcome)
    is ArchiveV3ImportResult.TechnicalFailure -> RestoreV3Result.TechnicalFailure(cause)
    is ArchiveV3ImportResult.CommitUnconfirmed -> RestoreV3Result.CommitUnconfirmed(cause)
}
