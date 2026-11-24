package com.lmarchery.data

import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5
import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.archive.DomainArchiveV2
import com.lmarchery.core.scoring.archive.SessionContent
import com.lmarchery.core.scoring.archive.configuredLegacyValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SessionRepository {
    suspend fun discover(): SessionDiscoveryResult
    suspend fun read(uuid: String): SessionReadResult
    suspend fun create(archive: DomainArchive): SessionCreateResult
    suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult
    /** Legacy doubles keep their old contract; complete V4 revisions require an explicit capability. */
    suspend fun updateContent(expected: SessionContent, proposed: SessionContent): ConfiguredWriteResult {
        val request = ConfiguredWriteRequest.Update(expected.configuredLegacyValue(), proposed.configuredLegacyValue())
        if (expected !is DomainArchive || proposed !is DomainArchive)
            return ConfiguredWriteResult.TechnicalFailure(request, UnsupportedOperationException("Complete cancellation revisions are unsupported by this repository."))
        return when (val r = update(expected, proposed)) {
            is SessionUpdateResult.Updated -> ConfiguredWriteResult.Updated(request)
            is SessionUpdateResult.Unchanged -> ConfiguredWriteResult.AlreadyApplied(request)
            is SessionUpdateResult.Conflict -> ConfiguredWriteResult.Conflict(request, ConfiguredWriteConflict.REVISION, r.diagnostic)
            SessionUpdateResult.NotFound -> ConfiguredWriteResult.Conflict(request, ConfiguredWriteConflict.NOT_FOUND, StorageDiagnostic("session", "Session not found."))
            is SessionUpdateResult.TechnicalFailure -> ConfiguredWriteResult.TechnicalFailure(request, r.cause)
            is SessionUpdateResult.NeedsIntervention -> ConfiguredWriteResult.NeedsIntervention(request,
                ArchiveV3StorageDiagnostic(ArchiveV3StorageIssueKind.STORAGE, r.diagnostic.path, r.diagnostic.detail, storageIssue = r.diagnostic), ConfiguredCommitOutcome.NOT_ATTEMPTED)
        }
    }
}

/** Separate read-only capability: no discover/read loop pretending to be atomic. */
fun interface ArchiveSource {
    suspend fun readArchive(): ArchiveReadResult
}

interface ArchiveImporter {
    suspend fun previewImport(archive: DomainArchive): ArchiveImportPreviewResult
    suspend fun importArchive(preview: ArchiveImportPreview): ArchiveImportResult
}

fun interface ArchiveV2Source {
    suspend fun readArchiveV2(): ArchiveV2ReadResult
}

/** Process-owned store; disk access and reconstruction never block the UI thread. */
class RoomSessionRepository(
    private val store: RoomSessionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository, ArchiveSource, ArchiveImporter, ArchiveV2Source, ArchiveV2Importer, ArchiveV3Source, ArchiveV3Importer, ArchiveV4Source, ArchiveV4Importer, ArchiveV5Access, ArchiveV6Access, ArchiveV7Access, ArchiveV8Access, ConfiguredSessionRepository {
    val equipment: EquipmentRepository = RoomEquipmentRepository(store.equipment, dispatcher)
    override suspend fun updateContent(expected: SessionContent, proposed: SessionContent) = withContext(dispatcher) { store.updateSessionContent(expected, proposed) }
    val weather get() = store.weather
    override suspend fun readArchiveV8() = withContext(dispatcher) { store.readArchiveV8() }
    override suspend fun previewImportV8(archive: com.lmarchery.core.scoring.archive.v7.DomainArchiveV7) =
        withContext(dispatcher) { prepared(store.previewImportV8(archive)) }
    override suspend fun readArchiveV7() = withContext(dispatcher) { store.readArchiveV7() }
    override suspend fun previewImportV7(archive: com.lmarchery.core.scoring.archive.v7.DomainArchiveV7) =
        withContext(dispatcher) { prepared(store.previewImportV7(archive)) }
    override suspend fun readArchiveV6() = withContext(dispatcher) { store.readArchiveV6() }
    override suspend fun previewImportV6(archive: com.lmarchery.core.scoring.archive.v6.DomainArchiveV6) =
        withContext(dispatcher) { prepared(store.previewImportV6(archive)) }

    val shootingPlaces get() = store.places
    override suspend fun readArchiveV5() = withContext(dispatcher) { store.readArchiveV5() }
    override suspend fun readSportingGraph() = withContext(dispatcher) { store.readSportingGraph() }
    override suspend fun previewImportV5(archive: DomainArchiveV5) =
        withContext(dispatcher) { prepared(store.previewImportV5(archive)) }
    override suspend fun readArchiveV4() = withContext(dispatcher) { store.readArchiveV4() }
    override suspend fun previewImportV4(archive: com.lmarchery.core.scoring.archive.v4.DomainArchiveV4): PreparedV4Result = withContext(dispatcher) {
        prepared(store.previewImportV4(archive))
    }
    private fun prepared(result: ArchiveV4PreviewResult): PreparedV4Result =
        when (result) {
            is ArchiveV4PreviewResult.Ready -> PreparedV4Result.Ready(object : PreparedV4Import {
                override val places = result.preview.incomingPlaces
                override val weather = result.preview.weather
                override val estimates = result.preview.estimates
                override val newEstimates = result.preview.newEstimates
                override val newObservations = result.preview.newObservations
                override val newPlaceRecords = result.preview.newPlaceRecords
                override val archive = result.preview.archive
                override val plan = result.preview.plan
                override suspend fun commit() = withContext(dispatcher) { store.importArchiveV4(result.preview).androidResult() }
            })
            is ArchiveV4PreviewResult.NeedsIntervention -> PreparedV4Result.NeedsIntervention(result.diagnostic)
            is ArchiveV4PreviewResult.TechnicalFailure -> PreparedV4Result.TechnicalFailure(result.cause)
        }

    override suspend fun readConfiguredSession(uuid: String) = withContext(dispatcher) { store.readConfiguredSession(uuid) }
    override suspend fun createConfiguredSession(session: com.lmarchery.core.scoring.configured.ConfiguredSession) =
        withContext(dispatcher) { store.createConfiguredSession(session) }
    override suspend fun createAtPlace(request: ConfiguredWriteRequest.Create) =
        withContext(dispatcher) { store.createConfiguredSession(request.proposed, request.place) }
    override suspend fun updateConfiguredSession(expected: com.lmarchery.core.scoring.configured.ConfiguredSession,
        proposed: com.lmarchery.core.scoring.configured.ConfiguredSession) =
        withContext(dispatcher) { store.updateConfiguredSession(expected, proposed) }

    override suspend fun readArchiveV3() = withContext(dispatcher) { store.readArchiveV3() }
    override suspend fun previewImportV3(archive: com.lmarchery.core.scoring.archive.v3.DomainArchiveV3): PreparedV3Result = withContext(dispatcher) {
        when (val result = store.previewImportV3(archive)) {
            is ArchiveV3PreviewResult.Ready -> PreparedV3Result.Ready(object : PreparedV3Import {
                override val archive = result.preview.archive
                override val plan = result.preview.plan
                override suspend fun commit() = withContext(dispatcher) { store.importArchiveV3(result.preview).androidResult() }
            })
            is ArchiveV3PreviewResult.NeedsIntervention -> PreparedV3Result.NeedsIntervention(result.diagnostic)
            is ArchiveV3PreviewResult.TechnicalFailure -> PreparedV3Result.TechnicalFailure(result.cause)
        }
    }

    override suspend fun readArchiveV2() = withContext(dispatcher) { store.readArchiveV2() }
    override suspend fun previewImportV2(archive: DomainArchiveV2): PreparedV2Result = withContext(dispatcher) {
        when (val result = store.previewImportV2(archive)) {
            is ArchiveV2PreviewResult.Ready -> PreparedV2Result.Ready(object : PreparedV2Import {
                override val plan = result.preview.plan
                override suspend fun commit() = withContext(dispatcher) { store.importArchiveV2(result.preview) }
            })
            is ArchiveV2PreviewResult.NeedsIntervention -> PreparedV2Result.NeedsIntervention(result.diagnostic)
            is ArchiveV2PreviewResult.TechnicalFailure -> PreparedV2Result.TechnicalFailure(result.cause)
        }
    }
    override suspend fun previewImport(archive: DomainArchive) = withContext(dispatcher) { store.previewImport(archive) }
    override suspend fun importArchive(preview: ArchiveImportPreview) = withContext(dispatcher) { store.importArchive(preview) }
    override suspend fun readArchive() = withContext(dispatcher) { store.readArchive() }
    override suspend fun discover() = withContext(dispatcher) { store.discover() }
    override suspend fun read(uuid: String) = withContext(dispatcher) { store.read(uuid) }
    override suspend fun create(archive: DomainArchive) = withContext(dispatcher) { store.create(archive) }
    override suspend fun update(expected: DomainArchive, proposed: DomainArchive) =
        withContext(dispatcher) { store.update(expected, proposed) }
}
