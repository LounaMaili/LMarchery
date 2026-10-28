package com.lmarchery.data

import com.lmarchery.core.database.ArchiveV2ImportResult
import com.lmarchery.core.database.StorageDiagnostic
import com.lmarchery.core.scoring.archive.ArchiveV2ImportPlan
import com.lmarchery.core.scoring.archive.DomainArchiveV2

/** Opaque confirmation capability. The Room adapter captures the exact source and baseline preview;
 * the UI cannot manufacture or replace a database preview from a displayed plan. */
interface PreparedV2Import {
    val plan: ArchiveV2ImportPlan
    suspend fun commit(): ArchiveV2ImportResult
}

sealed interface PreparedV2Result {
    data class Ready(val candidate: PreparedV2Import) : PreparedV2Result
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : PreparedV2Result
    data class TechnicalFailure(val cause: Exception) : PreparedV2Result
}

fun interface ArchiveV2Importer {
    suspend fun previewImportV2(archive: DomainArchiveV2): PreparedV2Result
}
