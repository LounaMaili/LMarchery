package com.lmarchery.core.database

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.lmarchery.core.scoring.archive.v3.ArchiveV3Limits
import com.lmarchery.core.scoring.configured.ConfiguredSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** A V3 projection alone does not prove that a physical row is CONFIGURED. */
sealed interface ConfiguredSessionReadResult {
    data class Found(val session: ConfiguredSession) : ConfiguredSessionReadResult
    data object NotFound : ConfiguredSessionReadResult
    data object LegacyHomogeneous : ConfiguredSessionReadResult
    data class IdentityOccupied(val diagnostic: StorageDiagnostic) : ConfiguredSessionReadResult
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : ConfiguredSessionReadResult
    data class TechnicalFailure(val cause: Exception) : ConfiguredSessionReadResult
}

internal class RoomConfiguredRead(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun read(uuid: String): ConfiguredSessionReadResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val queries = StrictStorageQueries(connection)
            val archive = collectArchiveV3(queries, caller, ArchiveV3Limits())
            val session = archive.sessions.singleOrNull { it.uuid == uuid }
            caller.ensureActive()
            when {
                session == null && uuid in archive.identitiesV3() -> ConfiguredSessionReadResult.IdentityOccupied(
                    StorageDiagnostic("identities[$uuid]", "UUID belongs to another entity type."))
                session == null -> ConfiguredSessionReadResult.NotFound
                queries.sessionConfiguration(uuid)?.representation == LEGACY_HOMOGENEOUS -> ConfiguredSessionReadResult.LegacyHomogeneous
                else -> ConfiguredSessionReadResult.Found(session) // collectArchiveV3 checked the exact CONFIGURED marker/captures.
            }
        } }
    } catch (error: CorruptV3Storage) { ConfiguredSessionReadResult.NeedsIntervention(error.diagnostic) }
      catch (error: CorruptStorage) { ConfiguredSessionReadResult.NeedsIntervention(ArchiveV3StorageDiagnostic(
          ArchiveV3StorageIssueKind.STORAGE, error.diagnostic.path, error.diagnostic.detail, storageIssue = error.diagnostic)) }
      catch (error: CancellationException) { throw error }
      catch (error: Exception) { ConfiguredSessionReadResult.TechnicalFailure(error) }
}
