package com.lmarchery.data

import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5
import com.lmarchery.core.model.place.ShootingPlaces
import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.archive.v4.*

fun interface ArchiveV4Source {
    suspend fun readArchiveV4(): ArchiveV4ReadResult
    suspend fun readSportingGraph(): ArchiveV4ReadResult = readArchiveV4()
}

interface ArchiveV8Access {
    suspend fun readArchiveV8(): com.lmarchery.core.scoring.archive.v7.DomainArchiveV7
    suspend fun previewImportV8(archive: com.lmarchery.core.scoring.archive.v7.DomainArchiveV7): PreparedV4Result
}

interface ArchiveV7Access {
    suspend fun readArchiveV7(): com.lmarchery.core.scoring.archive.v7.DomainArchiveV7
    suspend fun previewImportV7(archive: com.lmarchery.core.scoring.archive.v7.DomainArchiveV7): PreparedV4Result
}

interface ArchiveV6Access {
    suspend fun readArchiveV6(): com.lmarchery.core.scoring.archive.v6.DomainArchiveV6
    suspend fun previewImportV6(archive: com.lmarchery.core.scoring.archive.v6.DomainArchiveV6): PreparedV4Result
}

interface ArchiveV5Access {
    suspend fun readArchiveV5(): DomainArchiveV5
    suspend fun previewImportV5(archive: DomainArchiveV5): PreparedV4Result
}

/** Exact opaque Room preview; a displayed plan never authorizes construction of a SQL candidate. */
interface PreparedV4Import {
    val archive: DomainArchiveV4
    val places: ShootingPlaces? get() = null
    val newPlaceRecords: Int get() = 0
    val weather: List<com.lmarchery.core.model.weather.WeatherObservation>? get() = null
    val estimates: List<com.lmarchery.core.model.weather.SessionEstimate>? get() = null
    val newEstimates: Int get() = 0
    val newObservations: Int get() = 0
    val plan: ArchiveV4ImportPlan
    suspend fun commit(): RestoreV4Result
}

sealed interface PreparedV4Result {
    data class Ready(val candidate: PreparedV4Import) : PreparedV4Result
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : PreparedV4Result
    data class TechnicalFailure(val cause: Exception) : PreparedV4Result
}

fun interface ArchiveV4Importer { suspend fun previewImportV4(archive: DomainArchiveV4): PreparedV4Result }

/** Android boundary mirrors the store outcomes; the coordinator retains the exact capability. */
sealed interface RestoreV4Result {
    data class Imported(val added: ArchiveV4Counts, val identical: ArchiveV4Counts) : RestoreV4Result
    data class AlreadyPresent(val identical: ArchiveV4Counts, val emptyArchive: Boolean) : RestoreV4Result
    data class Conflict(val diagnostic: StorageDiagnostic) : RestoreV4Result
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic, val outcome: ArchiveV4CommitOutcome) : RestoreV4Result
    data class TechnicalFailure(val cause: Exception) : RestoreV4Result
    data class CommitUnconfirmed(val cause: Exception) : RestoreV4Result
}

internal fun ArchiveV4ImportResult.androidResult(): RestoreV4Result = when (this) {
    is ArchiveV4ImportResult.Imported -> RestoreV4Result.Imported(added, identical)
    is ArchiveV4ImportResult.AlreadyPresent -> RestoreV4Result.AlreadyPresent(identical, emptyArchive)
    is ArchiveV4ImportResult.Conflict -> RestoreV4Result.Conflict(diagnostic)
    is ArchiveV4ImportResult.NeedsIntervention -> RestoreV4Result.NeedsIntervention(diagnostic, commitOutcome)
    is ArchiveV4ImportResult.TechnicalFailure -> RestoreV4Result.TechnicalFailure(cause)
    is ArchiveV4ImportResult.CommitUnconfirmed -> RestoreV4Result.CommitUnconfirmed(cause)
}
