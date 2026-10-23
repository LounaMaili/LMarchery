package com.lmarchery.data

import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.archive.DomainArchive
import com.lmarchery.core.scoring.archive.DomainArchiveV2
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SessionRepository {
    suspend fun discover(): SessionDiscoveryResult
    suspend fun read(uuid: String): SessionReadResult
    suspend fun create(archive: DomainArchive): SessionCreateResult
    suspend fun update(expected: DomainArchive, proposed: DomainArchive): SessionUpdateResult
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
) : SessionRepository, ArchiveSource, ArchiveImporter, ArchiveV2Source, ArchiveV2Importer, ArchiveV3Source, ArchiveV3Importer {
    val equipment: EquipmentRepository = RoomEquipmentRepository(store.equipment, dispatcher)

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
