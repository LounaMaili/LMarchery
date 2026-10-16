package com.lmarchery.data

import com.lmarchery.core.database.*
import com.lmarchery.core.scoring.archive.DomainArchive
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

/** Process-owned store; disk access and reconstruction never block the UI thread. */
class RoomSessionRepository(
    private val store: RoomSessionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository, ArchiveSource, ArchiveImporter {
    override suspend fun previewImport(archive: DomainArchive) = withContext(dispatcher) { store.previewImport(archive) }
    override suspend fun importArchive(preview: ArchiveImportPreview) = withContext(dispatcher) { store.importArchive(preview) }
    override suspend fun readArchive() = withContext(dispatcher) { store.readArchive() }
    override suspend fun discover() = withContext(dispatcher) { store.discover() }
    override suspend fun read(uuid: String) = withContext(dispatcher) { store.read(uuid) }
    override suspend fun create(archive: DomainArchive) = withContext(dispatcher) { store.create(archive) }
    override suspend fun update(expected: DomainArchive, proposed: DomainArchive) =
        withContext(dispatcher) { store.update(expected, proposed) }
}
