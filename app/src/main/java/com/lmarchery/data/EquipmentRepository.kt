package com.lmarchery.data

import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.*
import kotlinx.coroutines.*

enum class EquipmentKind { BOW, ARROW_SET }

interface EquipmentRepository {
    suspend fun discover(kind: EquipmentKind): EquipmentDiscoveryResult
    suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment>
    suspend fun create(value: Equipment): EquipmentCreateResult
    suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult
}

/** Dispatcher boundary also testable without Android or an extra database instance. */
internal class IoEquipmentRepository(private val delegate: EquipmentRepository,
    private val dispatcher: CoroutineDispatcher) : EquipmentRepository {
    override suspend fun discover(kind: EquipmentKind) = withContext(dispatcher) { delegate.discover(kind) }
    override suspend fun read(kind: EquipmentKind, uuid: String) = withContext(dispatcher) { delegate.read(kind, uuid) }
    override suspend fun create(value: Equipment) = withContext(dispatcher) { delegate.create(value) }
    override suspend fun update(expected: Equipment, proposed: Equipment) = withContext(dispatcher) { delegate.update(expected, proposed) }
}

/** Uses the process-owned catalog, including its SQL identity checks, revision comparison and rollback. */
class RoomEquipmentRepository(catalog: RoomEquipmentCatalog, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
    EquipmentRepository by IoEquipmentRepository(object : EquipmentRepository {
        override suspend fun discover(kind: EquipmentKind) = when (kind) {
            EquipmentKind.BOW -> catalog.discoverBows()
            EquipmentKind.ARROW_SET -> catalog.discoverArrowSets()
        }
        override suspend fun read(kind: EquipmentKind, uuid: String) = when (kind) {
            EquipmentKind.BOW -> catalog.readBow(uuid)
            EquipmentKind.ARROW_SET -> catalog.readArrowSet(uuid)
        }
        override suspend fun create(value: Equipment) = when (value) {
            is Bow -> catalog.create(value)
            is ArrowSet -> catalog.create(value)
        }
        override suspend fun update(expected: Equipment, proposed: Equipment) = when {
            expected is Bow && proposed is Bow -> catalog.update(expected, proposed)
            expected is ArrowSet && proposed is ArrowSet -> catalog.update(expected, proposed)
            else -> EquipmentUpdateResult.NeedsIntervention(StorageDiagnostic("equipment.kind", "Equipment kind must be preserved."))
        }
    }, dispatcher)
