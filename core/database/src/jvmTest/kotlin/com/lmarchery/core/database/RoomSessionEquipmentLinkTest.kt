package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData.archive
import com.lmarchery.core.database.EquipmentTestData.arrows
import com.lmarchery.core.database.EquipmentTestData.bow
import com.lmarchery.core.database.EquipmentTestData.contents
import com.lmarchery.core.database.EquipmentTestData.denyWrites
import com.lmarchery.core.database.EquipmentTestData.id
import com.lmarchery.core.database.EquipmentTestData.now
import com.lmarchery.core.database.EquipmentTestData.session
import com.lmarchery.core.database.EquipmentTestData.tables
import com.lmarchery.core.export.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.summarizeSessionScore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomSessionEquipmentLinkTest {
    @TempDir lateinit var directory: Path
    @Test fun `completed metadata association changes only root row and round trips decisions traces and references in V2`() = runBlocking<Unit> {
        val path = directory.resolve("linked.db"); val original = session(completed = true)
        val revised = original.withEquipmentReferences(id(802), id(803), now.plusSeconds(60))
        val bytes = openSessionStore(path).use { store ->
            store.create(archive(original)); store.equipment.create(bow(802)); store.equipment.create(arrows(803))
            val protected = tables.filter { it != "sessions" }; val before = contents(path, protected)
            denyWrites(path, protected)
            assertIs<SessionUpdateResult.Updated>(store.update(archive(original), archive(revised)))
            assertEquals(before, contents(path, protected))
            val read = assertIs<SessionReadResult.Found>(store.read(original.uuid)).session
            assertEquals(revised, read); assertEquals(original.ends, read.ends); assertEquals(original.endedAt, read.endedAt)
            assertEquals(summarizeSessionScore(original), summarizeSessionScore(read))
            ArchiveJsonCodec().encode(store.fullV2())
        }
        openSessionStore(path).use { assertEquals(revised, assertIs<SessionReadResult.Found>(it.read(original.uuid)).session) }
        val decoded = assertIs<VersionedArchiveJsonRead.V2>(ArchiveJsonCodec().decodeVersioned(bytes)).archive
        val destination = directory.resolve("restored.db")
        openSessionStore(destination).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(decoded)) }
        openSessionStore(destination).use { assertEquals(decoded, it.fullV2()) }
        assertEquals(contents(path), contents(destination))
    }
    @Test fun `opaque references stay intact and stale metadata association is rejected by complete expected state`() = runBlocking<Unit> {
        val path = directory.resolve("opaque.db"); val original = session(completed = true)
        openSessionStore(path).use { first ->
            first.create(archive(original))
            val before = assertIs<SessionReadResult.Found>(first.read(original.uuid)).session
            assertEquals(id(800), before.bowUuid); assertTrue(first.fullV2().bows.isEmpty())
            openSessionStore(path).use { second ->
                val concurrent = original.copy(notes = "Concurrent metadata", updatedAt = now.plusSeconds(20))
                assertIs<SessionUpdateResult.Updated>(second.update(archive(original), archive(concurrent)))
                val rows = contents(path)
                val proposal = original.withEquipmentReferences(null, null, now.plusSeconds(60))
                assertIs<SessionUpdateResult.Conflict>(first.update(archive(original), archive(proposal)))
                assertEquals(rows, contents(path)); assertEquals(concurrent, assertIs<SessionReadResult.Found>(first.read(original.uuid)).session)
            }
        }
    }
}
