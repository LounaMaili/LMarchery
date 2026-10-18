package com.lmarchery.data

import com.lmarchery.backup.V2TestData.bow
import com.lmarchery.backup.V2TestData.now
import com.lmarchery.core.database.*
import com.lmarchery.core.model.equipment.*
import kotlinx.coroutines.*
import kotlin.test.*

class EquipmentRepositoryTest {
    @Test fun `IO boundary forwards exact kind UUID expected and proposed with no extra call`() = runBlocking<Unit> {
        val tasks = java.util.ArrayDeque<Runnable>()
        val queue = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val calls = mutableListOf<String>(); val originalBow = bow(); val value = originalBow; val next = value.copy(name = "Changed", updatedAt = now.plusSeconds(1))
        val repo = IoEquipmentRepository(object : EquipmentRepository {
            override suspend fun discover(kind: EquipmentKind): EquipmentDiscoveryResult { calls += "discover"; assertEquals(EquipmentKind.BOW, kind); return EquipmentDiscoveryResult.Found(listOf(value.uuid)) }
            override suspend fun read(kind: EquipmentKind, uuid: String): EquipmentReadResult<Equipment> { calls += "read"; assertEquals(value.uuid, uuid); return EquipmentReadResult.Found(value) }
            override suspend fun create(value: Equipment): EquipmentCreateResult { calls += "create"; assertSame(originalBow, value); return EquipmentCreateResult.Created(value.uuid) }
            override suspend fun update(expected: Equipment, proposed: Equipment): EquipmentUpdateResult { calls += "update"; assertSame(value, expected); assertSame(next, proposed); return EquipmentUpdateResult.Updated(proposed.uuid) }
        }, queue)
        suspend fun execute(block: suspend () -> Unit) {
            val job = launch(Dispatchers.Unconfined) { block() }
            assertFalse(job.isCompleted); assertEquals(1, tasks.size); tasks.removeFirst().run(); job.join()
        }
        execute { assertIs<EquipmentDiscoveryResult.Found>(repo.discover(EquipmentKind.BOW)) }
        execute { assertEquals(EquipmentReadResult.Found(value), repo.read(EquipmentKind.BOW, value.uuid)) }
        execute { assertIs<EquipmentCreateResult.Created>(repo.create(value)) }
        // Keep identity assertions in the delegate, rather than rebuilding any revision in the adapter.
        execute { assertIs<EquipmentUpdateResult.Updated>(repo.update(value, next)) }
        assertEquals(listOf("discover", "read", "create", "update"), calls)
    }
}
