package com.lmarchery.core.database

import androidx.room.deferredTransaction
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.equipment.Equipment
import com.lmarchery.core.model.equipment.validateEquipmentUpdate
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Collections
import java.util.concurrent.CancellationException

sealed interface EquipmentReadResult<out T : Equipment> {
    data object NotFound : EquipmentReadResult<Nothing>
    data class Found<T : Equipment>(val value: T) : EquipmentReadResult<T>
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : EquipmentReadResult<Nothing>
    data class TechnicalFailure(val cause: Exception) : EquipmentReadResult<Nothing>
}

sealed interface EquipmentDiscoveryResult {
    data class Found(val uuids: List<String>) : EquipmentDiscoveryResult
    data class TechnicalFailure(val cause: Exception) : EquipmentDiscoveryResult
}

sealed interface EquipmentCreateResult {
    data class Created(val uuid: String) : EquipmentCreateResult
    data class AlreadyExists(val uuid: String) : EquipmentCreateResult
    data class Conflict(val diagnostic: StorageDiagnostic) : EquipmentCreateResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : EquipmentCreateResult
    data class TechnicalFailure(val cause: Exception) : EquipmentCreateResult
}

sealed interface EquipmentUpdateResult {
    data class Updated(val uuid: String) : EquipmentUpdateResult
    data class Unchanged(val uuid: String) : EquipmentUpdateResult
    data object NotFound : EquipmentUpdateResult
    data class Conflict(val diagnostic: StorageDiagnostic) : EquipmentUpdateResult
    data class NeedsIntervention(val diagnostic: StorageDiagnostic) : EquipmentUpdateResult
    data class TechnicalFailure(val cause: Exception) : EquipmentUpdateResult
}

/** Shares the session store's database, writer lock, migration and lifetime. No deletion or implicit retry. */
class RoomEquipmentCatalog internal constructor(
    private val database: SessionDatabase,
    private val ensureOpen: () -> Unit,
) {
    private class Access<T : Equipment>(
        val kind: String,
        val table: String,
        val load: suspend (EquipmentDao, String) -> T?,
        val insert: suspend (EquipmentDao, T) -> Unit,
        val update: suspend (EquipmentDao, T) -> Int,
        val discover: suspend (EquipmentDao) -> List<String>,
    )

    private val bows = Access("BOW", "bows", { dao, uuid -> dao.bow(uuid)?.domain() },
        { dao, value: Bow -> dao.insertBow(value.row()) }, { dao, value -> dao.updateBow(value.row()) },
        { it.bowUuids() })
    private val arrows = Access("ARROW_SET", "arrow_sets", { dao, uuid -> dao.arrowSet(uuid)?.domain() },
        { dao, value: ArrowSet -> dao.insertArrowSet(value.row()) }, { dao, value -> dao.updateArrowSet(value.row()) },
        { it.arrowSetUuids() })

    suspend fun readBow(uuid: String): EquipmentReadResult<Bow> = read(bows, uuid)
    suspend fun readArrowSet(uuid: String): EquipmentReadResult<ArrowSet> = read(arrows, uuid)
    suspend fun discoverBows(): EquipmentDiscoveryResult = discover(bows)
    suspend fun discoverArrowSets(): EquipmentDiscoveryResult = discover(arrows)
    suspend fun create(value: Bow): EquipmentCreateResult = create(bows, value)
    suspend fun create(value: ArrowSet): EquipmentCreateResult = create(arrows, value)
    suspend fun update(expected: Bow, proposed: Bow): EquipmentUpdateResult = update(bows, expected, proposed)
    suspend fun update(expected: ArrowSet, proposed: ArrowSet): EquipmentUpdateResult = update(arrows, expected, proposed)

    private suspend fun <T : Equipment> load(access: Access<T>, uuid: String): T? {
        val identity = database.records().identities(listOf(uuid)).singleOrNull()
        val value = access.load(database.equipment(), uuid)
        if (value == null) {
            dataCheck(identity?.kind != access.kind, "${access.table}[$uuid]", "Registered equipment row is missing.")
        } else {
            dataCheck(identity == IdentityRow(uuid, access.kind), "${access.table}[$uuid].identity",
                "Equipment identity registry is missing or inconsistent.")
            dataCheck(database.equipment().rowIdentities(uuid) == listOf(IdentityRow(uuid, access.kind)) &&
                BuiltInTargetRegistry.find(uuid) == null, "${access.table}[$uuid].identity",
                "Equipment UUID is also claimed by another entity or a published profile.")
        }
        return value
    }

    private suspend fun <T : Equipment> read(access: Access<T>, uuid: String): EquipmentReadResult<T> = guarded(
        { EquipmentReadResult.NeedsIntervention(it) }, { EquipmentReadResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        ensureOpen()
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val value = load(access, uuid)
            caller.ensureActive()
            if (value == null) EquipmentReadResult.NotFound else EquipmentReadResult.Found(value)
        } }
    }

    /** Discovery includes corrupt roots/orphan identities; callers must inspect each read result. */
    private suspend fun <T : Equipment> discover(access: Access<T>): EquipmentDiscoveryResult = guarded(
        { EquipmentDiscoveryResult.TechnicalFailure(CorruptStorage(it)) }, { EquipmentDiscoveryResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        ensureOpen()
        database.useReaderConnection { connection -> connection.deferredTransaction {
            val uuids = access.discover(database.equipment())
            caller.ensureActive()
            EquipmentDiscoveryResult.Found(Collections.unmodifiableList(uuids.toList()))
        } }
    }

    private suspend fun <T : Equipment> create(access: Access<T>, value: T): EquipmentCreateResult = guarded(
        { EquipmentCreateResult.NeedsIntervention(it) }, { EquipmentCreateResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        ensureOpen()
        database.useWriterConnection { connection -> connection.immediateTransaction {
            caller.ensureActive()
            val stored = load(access, value.uuid)
            when {
                stored == value -> EquipmentCreateResult.AlreadyExists(value.uuid)
                stored != null -> EquipmentCreateResult.Conflict(StorageDiagnostic("${access.table}[${value.uuid}]",
                    "Existing equipment has different content; no overwrite allowed."))
                database.records().identities(listOf(value.uuid)).isNotEmpty() ||
                    database.equipment().rowIdentities(value.uuid).isNotEmpty() ||
                    BuiltInTargetRegistry.find(value.uuid) != null -> EquipmentCreateResult.Conflict(StorageDiagnostic(
                    "identities[${value.uuid}]", "UUID is already owned or reserved by another entity kind."))
                else -> {
                    caller.ensureActive()
                    database.records().insertIdentity(IdentityRow(value.uuid, access.kind))
                    access.insert(database.equipment(), value)
                    // Throw after any write: a returned diagnostic would otherwise commit partial data.
                    dataCheck(load(access, value.uuid) == value, "${access.table}[${value.uuid}]",
                        "Stored equipment differs from source; transaction rolled back.")
                    caller.ensureActive()
                    EquipmentCreateResult.Created(value.uuid)
                }
            }
        } }
    }

    private suspend fun <T : Equipment> update(access: Access<T>, expected: T, proposed: T): EquipmentUpdateResult = guarded(
        { EquipmentUpdateResult.NeedsIntervention(it) }, { EquipmentUpdateResult.TechnicalFailure(it) },
    ) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        ensureOpen()
        database.useWriterConnection { connection -> connection.immediateTransaction {
            caller.ensureActive()
            val stored = load(access, expected.uuid)
            when {
                stored == null -> EquipmentUpdateResult.NotFound
                stored != expected -> EquipmentUpdateResult.Conflict(StorageDiagnostic("${access.table}[${expected.uuid}]",
                    "Stored equipment differs from complete expected state; reload before retrying."))
                stored == proposed -> EquipmentUpdateResult.Unchanged(expected.uuid)
                else -> {
                    decode("${access.table}[${expected.uuid}].transition") { validateEquipmentUpdate(stored, proposed) }
                    caller.ensureActive()
                    dataCheck(access.update(database.equipment(), proposed) == 1, "${access.table}[${expected.uuid}]",
                        "Update did not affect exactly one row; transaction rolled back.")
                    dataCheck(load(access, proposed.uuid) == proposed, "${access.table}[${expected.uuid}]",
                        "Stored equipment differs from revision; transaction rolled back.")
                    caller.ensureActive()
                    EquipmentUpdateResult.Updated(expected.uuid)
                }
            }
        } }
    }
}

private suspend inline fun <T> guarded(
    corruption: (StorageDiagnostic) -> T,
    failure: (Exception) -> T,
    block: () -> T,
): T = try { block() }
catch (error: CorruptStorage) { corruption(error.diagnostic) }
catch (error: CancellationException) { throw error }
catch (error: Exception) { failure(error) }
