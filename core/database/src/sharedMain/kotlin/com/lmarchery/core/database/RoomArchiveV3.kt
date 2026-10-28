package com.lmarchery.core.database

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.InitialConfigurationOrigin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

enum class ArchiveV3StorageIssueKind { STORAGE, DOMAIN }
data class ArchiveV3StorageDiagnostic(
    val kind: ArchiveV3StorageIssueKind, val path: String, val detail: String,
    val archiveIssue: ArchiveV3Issue? = null, val storageIssue: StorageDiagnostic? = null,
)
sealed interface ArchiveV3ReadResult {
    data class Found(val archive: DomainArchiveV3) : ArchiveV3ReadResult
    data class NeedsIntervention(val diagnostic: ArchiveV3StorageDiagnostic) : ArchiveV3ReadResult
    data class TechnicalFailure(val cause: Exception) : ArchiveV3ReadResult
}
internal class CorruptV3Storage(val diagnostic: ArchiveV3StorageDiagnostic) : RuntimeException(diagnostic.detail)

internal class RoomArchiveV3(private val database: SessionDatabase, private val ensureOpen: () -> Unit) {
    suspend fun read(limits: ArchiveV3Limits): ArchiveV3ReadResult = try {
        val caller = currentCoroutineContext()
        caller.ensureActive(); ensureOpen()
        database.useReaderConnection { connection -> connection.deferredTransaction {
            // One Room connection/transaction for every table, no independent store/catalog calls.
            val queries = StrictStorageQueries(connection)
            val archive = collectArchiveV3(queries, caller, limits)
            caller.ensureActive()
            ArchiveV3ReadResult.Found(archive)
        } }
    } catch (error: CorruptV3Storage) { ArchiveV3ReadResult.NeedsIntervention(error.diagnostic) }
      catch (error: CorruptStorage) { ArchiveV3ReadResult.NeedsIntervention(ArchiveV3StorageDiagnostic(
          ArchiveV3StorageIssueKind.STORAGE, error.diagnostic.path, error.diagnostic.detail, storageIssue = error.diagnostic)) }
      catch (error: CancellationException) { throw error }
      catch (error: Exception) { ArchiveV3ReadResult.TechnicalFailure(error) }
}

/** Shared graph audit, independent of either archive's homogeneous/configurable session representation. */
internal suspend fun auditStoredIdentities(queries: StorageReadQueries): List<IdentityRow> {
    val registered = queries.allIdentities()
    val physical = queries.allRowIdentities()
    val byUuid = physical.groupBy { it.uuid }
    val duplicate = byUuid.entries.firstOrNull { it.value.size != 1 }
    dataCheck(duplicate == null, "identities[${duplicate?.key}]", "UUID is claimed by multiple physical entity types.")
    val registry = registered.associateBy { it.uuid }
    val mismatch = (registered.map { it.uuid } + physical.map { it.uuid }).sorted().firstOrNull { registry[it] != byUuid[it]?.singleOrNull() }
    dataCheck(mismatch == null, "identities[$mismatch]", "Registry and physical entity ownership differ or a row is missing.")
    val orphan = queries.orphanChildPaths().firstOrNull()
    dataCheck(orphan == null, orphan ?: "children", "Stored child has no physical parent.")
    return physical
}

/** Call only inside the caller's single read/migration transaction. Children reuse historical mapping/replay. */
internal suspend fun collectArchiveV3(queries: StrictStorageQueries, caller: CoroutineContext, limits: ArchiveV3Limits): DomainArchiveV3 {
    caller.ensureActive()
    val equipment: EquipmentReadQueries = queries
    val configurations: ConfigurationReadQueries = queries
    queries.checkBudgets(limits,configured = true)
    val physical = auditStoredIdentities(queries)
    val orphan = configurations.orphanConfigurationPaths().firstOrNull()
    dataCheck(orphan == null, orphan ?: "configurations", "Configuration has no physical owner or predecessor.")
    val profiles = queries.profileUuids().map { caller.ensureActive(); queries.profileSnapshot(it) }
    val sessions = queries.sessionUuids().map { uuid ->
        caller.ensureActive()
        val row = queries.session(uuid) ?: throw CorruptStorage(StorageDiagnostic("sessions[$uuid]", "Session row is missing."))
        val initial = configurations.sessionConfiguration(uuid) ?: throw CorruptStorage(
            StorageDiagnostic("session_configurations[$uuid]", "Initial configuration is missing; fallback is forbidden."))
        when (initial.representation) {
            LEGACY_HOMOGENEOUS -> requireLegacyCapture(queries, configurations, row)
            CONFIGURED -> {
                dataCheck(row.archiveSchemaVersion == 3 && row.scoringContractVersion == 1,
                    "sessions[$uuid].versions", "Configured row requires representation 3 and scoring contract 1.")
                // Legacy NOT NULL columns become explicit initial aliases only. NEVER effective end configuration.
                dataCheck(initial.configuration == queries.legacyConfiguration(row).configuration,
                    "sessions[$uuid].initialAliases", "Initial aliases contradict the complete stored initial capture/profile edition.")
            }
            else -> throw CorruptStorage(StorageDiagnostic("session_configurations[$uuid].representation", "Unknown stored session representation."))
        }
        val endSnapshots = queries.endSnapshots(row)
        val ends = endSnapshots.map { end ->
            caller.ensureActive()
            val capture = configurations.endConfiguration(end.uuid) ?: throw CorruptStorage(
                StorageDiagnostic("end_configurations[${end.uuid}]", "End configuration is missing, including for an empty end."))
            dataCheck(capture.sessionUuid == uuid, "end_configurations[${end.uuid}].sessionUuid", "Captured end belongs to a different session.")
            decode("end_configurations[${end.uuid}]") {
                ConfiguredEndSnapshot(capture.sessionUuid, end, capture.configuration.snapshot(),
                    EndConfigurationOriginSnapshot(enumValueOf<EndConfigurationOriginKind>(capture.origin), capture.previousEndUuid))
            }
        }
        decode("sessions[$uuid]") {
            ConfiguredSessionSnapshot(row.uuid, ConfiguredMetadataSnapshot(CivilDateSnapshot(row.localDateTime, row.zoneId, row.offset),
                row.title, row.plannedEndCount, row.bowUuid, row.arrowSetUuid, row.locationName, row.notes),
                initial.configuration.snapshot(), enumValueOf<InitialConfigurationOrigin>(initial.origin), enumValueOf<SessionStatus>(row.status),
                row.startedAt?.text(), row.endedAt?.text(), ends, row.createdAt.text(), row.updatedAt.text())
        }
    }
    val bows = equipment.bowUuids().map { id -> caller.ensureActive(); equipment.bow(id)?.domain()
        ?: throw CorruptStorage(StorageDiagnostic("bows[$id]", "Bow row is missing.")) }
    val arrows = equipment.arrowSetUuids().map { id -> caller.ensureActive(); equipment.arrowSet(id)?.domain()
        ?: throw CorruptStorage(StorageDiagnostic("arrow_sets[$id]", "Arrow set row is missing.")) }
    // Only identical shared profile/equipment DTO capture; no session is projected to V2.
    val shared = snapshotArchiveV2(profiles, emptyList(), bows, arrows)
    val snapshot = ArchiveV3Snapshot(3, 1, profiles, sessions, shared.bows, shared.arrowSets)
    val result = validateStoredV3(snapshot, limits)
    val reachable = result.identitiesV3()
    val mismatch = physical.firstOrNull { reachable[it.uuid] != it }
    dataCheck(reachable.size == physical.size && mismatch == null, "identities[${mismatch?.uuid}]",
        "Stored entities include unreachable descendants; archive would be incomplete.")
    caller.ensureActive()
    return result
}

private fun StoredEndConfiguration.snapshot() = EndConfigurationSnapshot(profileUuid, regulatoryEdition, distanceM,
    enumValueOf<ArrowCountMode>(arrowCountMode), arrowCountPerEnd, isIndoor.flag(), enumValueOf<ScoringMode>(scoringMode))

internal fun validateStoredV3(snapshot: ArchiveV3Snapshot, limits: ArchiveV3Limits = ArchiveV3Limits()): DomainArchiveV3 =
    when (val result = reconstructArchiveV3(snapshot, limits = limits)) {
        is ArchiveV3Reconstruction.Accepted -> result.archive
        is ArchiveV3Reconstruction.NeedsIntervention -> throw CorruptV3Storage(ArchiveV3StorageDiagnostic(
            ArchiveV3StorageIssueKind.DOMAIN, result.issue.path, result.issue.detail, result.issue))
    }

internal fun DomainArchiveV3.identitiesV3(): Map<String, IdentityRow> = buildMap {
    profiles.forEach { put(it.definition!!.uuid, IdentityRow(it.definition!!.uuid, "PROFILE")) }
    sessions.forEach { s -> put(s.uuid, IdentityRow(s.uuid,"SESSION")); s.ends.forEach { e ->
        put(e.uuid, IdentityRow(e.uuid,"END")); e.end.impacts.forEach { put(it.uuid,IdentityRow(it.uuid,"IMPACT")) }
    } }
    bows.forEach { put(it.uuid,IdentityRow(it.uuid,"BOW")) }; arrowSets.forEach { put(it.uuid,IdentityRow(it.uuid,"ARROW_SET")) }
}
