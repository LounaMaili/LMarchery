package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData.archive
import com.lmarchery.core.database.EquipmentTestData.arrows
import com.lmarchery.core.database.EquipmentTestData.bow
import com.lmarchery.core.database.EquipmentTestData.contents
import com.lmarchery.core.database.EquipmentTestData.now
import com.lmarchery.core.database.EquipmentTestData.session
import com.lmarchery.core.database.EquipmentTestData.tables
import com.lmarchery.core.export.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomEquipmentBackupWorkflowTest {
    @TempDir lateinit var directory: Path

    @Test fun `catalog edits leave every session row and V1 byte intact and survive V2 disk restoration`() = runBlocking<Unit> {
        val path = directory.resolve("edited.db")
        val nextBow = bow().copy(name = " Updated 🏹 ", drawWeightLbs = Float.fromBits(0x42000001), notes = "", updatedAt = now.plusNanos(1))
        val nextArrows = arrows().copy(spine = "", length = null, pointWeight = " 100 gr ? ", updatedAt = now.plusNanos(2))
        val codec = ArchiveJsonCodec()
        val exported = openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(session())))
            val before = contents(path, tables.filter { it !in listOf("bows", "arrow_sets", "entity_identities") })
            val v1Bytes = codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive)
            store.equipment.create(bow()); store.equipment.create(arrows())
            assertIs<EquipmentUpdateResult.Updated>(store.equipment.update(bow(), nextBow))
            assertIs<EquipmentUpdateResult.Updated>(store.equipment.update(arrows(), nextArrows))
            assertEquals(before, contents(path, before.keys.toList()))
            assertContentEquals(v1Bytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            codec.encode(store.fullV2())
        }
        val decoded = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(exported)).archive
        assertEquals(nextBow, decoded.bows.single()); assertEquals(nextArrows, decoded.arrowSets.single())
        openSessionStore(path).use { assertEquals(decoded, it.fullV2()) }
        val restored = directory.resolve("restored.db")
        openSessionStore(restored).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(decoded)) }
        openSessionStore(restored).use { assertEquals(decoded, it.fullV2()) }
        assertEquals(contents(path), contents(restored))
    }
    @Test fun `duplicate equipment names keep all UUIDs through close reopen and backup`() = runBlocking<Unit> {
        val path = directory.resolve("duplicates.db")
        val exported = openSessionStore(path).use { store ->
            for (value in listOf(bow(800), bow(802))) assertIs<EquipmentCreateResult.Created>(store.equipment.create(value))
            for (value in listOf(arrows(801), arrows(803))) assertIs<EquipmentCreateResult.Created>(store.equipment.create(value))
            ArchiveJsonCodec().encode(store.fullV2())
        }
        openSessionStore(path).use { store ->
            val archive = store.fullV2(); assertEquals(2, archive.bows.size); assertEquals(2, archive.arrowSets.size)
            assertEquals(1, archive.bows.map { it.name }.distinct().size)
            assertContentEquals(exported, ArchiveJsonCodec().encode(archive))
        }
        val received = assertIs<VersionedArchiveJsonRead.V2>(ArchiveJsonCodec().decodeVersioned(exported)).archive
        openSessionStore(directory.resolve("copy.db")).use { store ->
            assertEquals(ArchiveV2Counts(0, 0, 2, 2), assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(received)).added)
            assertEquals(received, store.fullV2())
        }
    }
}
