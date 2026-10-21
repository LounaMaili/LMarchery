package com.lmarchery.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.database.EquipmentTestData.archive
import com.lmarchery.core.database.EquipmentTestData.arrows
import com.lmarchery.core.database.EquipmentTestData.bow
import com.lmarchery.core.database.EquipmentTestData.contents
import com.lmarchery.core.database.EquipmentTestData.denyWrites
import com.lmarchery.core.database.EquipmentTestData.id
import com.lmarchery.core.database.EquipmentTestData.now
import com.lmarchery.core.database.EquipmentTestData.oldTables
import com.lmarchery.core.database.EquipmentTestData.rows
import com.lmarchery.core.database.EquipmentTestData.session
import com.lmarchery.core.export.ArchiveJsonCodec
import com.lmarchery.core.export.ArchiveJsonRead
import com.lmarchery.core.export.VersionedArchiveJsonRead
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.core.scoring.summarizeSessionScore
import com.lmarchery.core.model.equipment.Bow
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RoomEquipmentMigrationTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("real-v1.db")

    /** Build V1 from its frozen DDL/hash, then seed the eight unchanged tables with synthetic rich rows.
     * The source database only supplies data: V1 is never produced by downgrading a V2 file/version. */
    private suspend fun richV1(): ByteArray {
        val seed = directory.resolve("seed.db")
        val bytes = openSessionStore(seed).use { store ->
            listOf(session(), session(100, completed = true)).forEach { assertIs<SessionCreateResult.Created>(store.create(archive(it))) }
            ArchiveJsonCodec().encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive)
        }
        val ddl = requireNotNull(javaClass.getResourceAsStream("/room-schema-v1.sql")).bufferedReader().use { it.readLines() }
        BundledSQLiteDriver().open(path.toString()).use { c ->
            fun exec(sql: String) { c.prepare(sql).use { it.step() } }
            exec("PRAGMA foreign_keys = ON")
            exec("ATTACH DATABASE '${seed.toString().replace("'", "''")}' AS seed")
            exec("BEGIN IMMEDIATE")
            ddl.filter { it.isNotBlank() && !it.startsWith("--") }.forEach(::exec)
            oldTables.forEach { exec("INSERT INTO `$it` SELECT * FROM seed.`$it`") }
            exec("COMMIT")
            exec("DETACH DATABASE seed")
        }
        assertEquals(listOf("1"), rows(path, "PRAGMA user_version"))
        assertEquals(listOf("6d9db7df5796fd7567c9cfb0a7a8d846"), rows(path, "SELECT identity_hash FROM room_master_table WHERE id = 42"))
        assertEquals(emptyList(), rows(path, "SELECT name FROM sqlite_master WHERE name IN ('bows', 'arrow_sets')"))
        oldTables.forEach { assertTrue(rows(path, "SELECT count(*) FROM $it").single().toInt() > 0, "$it must contain V1 data") }
        assertEquals(emptyList(), rows(path, "PRAGMA foreign_key_check"))
        return bytes
    }

    @Test fun `real populated V1 migrates to V2 preserving every old row schema and JSON byte`() = runBlocking<Unit> {
        val originalBytes = richV1()
        val before = contents(path, oldTables)
        val oldSchema = rows(path, "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name != 'room_master_table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        openSessionStore(path).use { store ->
            val restored = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertEquals(listOf(session(), session(100, true)), restored.sessions)
            assertContentEquals(originalBytes, ArchiveJsonCodec().encode(restored))
            assertEquals(emptyList(), assertIs<EquipmentDiscoveryResult.Found>(store.equipment.discoverBows()).uuids)
            assertEquals(emptyList(), assertIs<EquipmentDiscoveryResult.Found>(store.equipment.discoverArrowSets()).uuids)
        }
        assertEquals(before, contents(path, oldTables))
        assertEquals(listOf("3"), rows(path, "PRAGMA user_version"))
        assertEquals(oldSchema, rows(path, "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name NOT IN ('room_master_table','bows','arrow_sets','session_configurations','end_configurations') AND name NOT LIKE 'sqlite_%' ORDER BY name"))
        assertEquals(12, rows(path, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT IN ('room_master_table','sqlite_sequence','android_metadata')").size)
        assertEquals(emptyList(), rows(path, "PRAGMA foreign_key_check"))
        openSessionStore(path).use { store ->
            assertContentEquals(originalBytes, ArchiveJsonCodec().encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows()))
            // Catalog records sharing opaque reference values do not alter an exported session.
            assertContentEquals(originalBytes, ArchiveJsonCodec().encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
        }
        openSessionStore(path).use { store ->
            assertEquals(bow(), assertIs<EquipmentReadResult.Found<*>>(store.equipment.readBow(id(800))).value)
            assertEquals(arrows(), assertIs<EquipmentReadResult.Found<*>>(store.equipment.readArrowSet(id(801))).value)
        }
        println("V1→V2: real populated disk file; 8 old tables and schema unchanged; 2 catalog tables added; complete JSON bytes identical; second reopen verified")
    }
    @Test fun `migration failure is atomic preserves V1 and never recreates or deletes data`() = runBlocking<Unit> {
        richV1()
        // A conflicting preexisting table makes the second CREATE fail after the first ran.
        rows(path, "CREATE TABLE arrow_sets (unexpected TEXT)")
        val before = contents(path, oldTables)
        openSessionStore(path).use { assertIs<SessionReadResult.TechnicalFailure>(it.read(session().uuid)) }
        assertEquals(before, contents(path, oldTables))
        assertEquals(listOf("1"), rows(path, "PRAGMA user_version"))
        assertEquals(emptyList(), rows(path, "SELECT name FROM sqlite_master WHERE name = 'bows'"))
    }
    @Test fun `JSON V1 import preserves opaque references and never changes catalog records`() = runBlocking<Unit> {
        val bytes = richV1()
        val decoded = assertIs<ArchiveJsonRead.Accepted>(ArchiveJsonCodec().decode(bytes)).archive
        val target = directory.resolve("import.db")
        openSessionStore(target).use { store ->
            store.equipment.create(bow()); store.equipment.create(arrows())
            val catalogBefore = contents(target, listOf("bows", "arrow_sets"))
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(decoded)).preview
            assertTrue(preview.plan.conflicts.isEmpty())
            assertEquals(ArchiveImportResult.Imported(2, 0), store.importArchive(preview))
            assertContentEquals(bytes, ArchiveJsonCodec().encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertEquals(catalogBefore, contents(target, listOf("bows", "arrow_sets")))
            denyWrites(target)
            val again = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(decoded)).preview
            assertEquals(ArchiveImportResult.Imported(0, 2), store.importArchive(again))
            assertEquals(catalogBefore, contents(target, listOf("bows", "arrow_sets")))
        }
    }
    @Test fun `import rechecks a new catalog identity collision acquired after preview`() = runBlocking<Unit> {
        val source = archive(session())
        openSessionStore(path).use { first -> openSessionStore(path).use { second ->
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(first.previewImport(source)).preview
            assertTrue(preview.plan.conflicts.isEmpty())
            assertIs<EquipmentCreateResult.Created>(second.equipment.create(Bow(source.sessions.single().uuid,
                "Catalog reservation", createdAt = now, updatedAt = now)))
            val before = contents(path)
            assertIs<ArchiveImportResult.Conflict>(first.importArchive(preview))
            assertEquals(before, contents(path))
            assertEquals(emptyList(), assertIs<SessionDiscoveryResult.Found>(first.discover()).uuids)
        } }
    }
    @Test fun `archive import and equipment creation share writer lock and cannot duplicate ownership`() = runBlocking<Unit> {
        val source = archive(session())
        openSessionStore(path).use { first -> openSessionStore(path).use { second ->
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(first.previewImport(source)).preview
            val start = CompletableDeferred<Unit>()
            val imported = async(Dispatchers.IO) { start.await(); first.importArchive(preview) }
            val created = async(Dispatchers.IO) { start.await(); second.equipment.create(Bow(source.sessions.single().uuid,
                "Competing catalog identity", createdAt = now, updatedAt = now)) }
            start.complete(Unit)
            val result = imported.await() to created.await()
            assertTrue(result.first is ArchiveImportResult.Imported && result.second is EquipmentCreateResult.Conflict ||
                result.first is ArchiveImportResult.Conflict && result.second is EquipmentCreateResult.Created, result.toString())
            assertEquals(listOf("1"), rows(path, "SELECT count(*) FROM entity_identities WHERE uuid = '${source.sessions.single().uuid}'"))
            assertEquals(emptyList(), rows(path, "PRAGMA foreign_key_check"))
        } }
    }
    @Test fun `migrated V1 supports confirmed placement judgment reposition and equipment with full V1 V2 restoration`() = runBlocking<Unit> {
        val originalBytes = richV1(); val originalRows = contents(path, oldTables)
        val codec = ArchiveJsonCodec()
        lateinit var finalSession: Session
        lateinit var v1Bytes: ByteArray
        lateinit var v2Bytes: ByteArray
        openSessionStore(path).use { store ->
            assertContentEquals(originalBytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertEquals(originalRows, contents(path, oldTables))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(bow(802)))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(arrows(803)))
            var current = assertIs<SessionReadResult.Found>(store.read(session().uuid)).session
            val original = current; val endUuid = current.ends.single().uuid
            suspend fun save(next: Session) {
                assertIs<SessionUpdateResult.Updated>(store.update(archive(current), archive(next)))
                current = assertIs<SessionReadResult.Found>(store.read(current.uuid)).session
                assertEquals(next, current)
            }
            // Both UI modes submit this same domain path only after explicit confirmation.
            val placed = scoreArrowImpact(id(900), 4, current.targetType, NormalizedPoint(.105f, -0.0f),
                current.scoringMode, createdAt = now.plusSeconds(20))
            save(current.withEnd(current.ends.single().withImpact(placed)))
            val boundary = current.targetType.scoreBoundaries().first { it.inner.score == 10 && it.outer.score == 9 }
            val judged = placed.withFinalScore(current.targetType,
                ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner, now.plusSeconds(21)), now.plusSeconds(21))
            save(current.withEnd(current.ends.single().withImpact(judged)))
            val moved = judged.reposition(NormalizedPoint(.25f, -0.0f), now.plusSeconds(22), "Synthetic integration correction")
            assertEquals(8, moved.scoreFinal); assertEquals(judged.finalScoreDecision, moved.repositions.single().previousDecision)
            save(current.withEnd(current.ends.single().withImpact(moved)))
            val corrected = moved.withFinalScore(current.targetType, ScoreDecisionRequest.ExceptionalCorrection(
                BoundaryValue(9), "Synthetic observed nine", now.plusSeconds(23)), now.plusSeconds(23))
            save(current.withEnd(current.ends.single().withImpact(corrected)))
            val beforeMetadata = current
            save(current.withEquipmentReferences(id(802), id(803), now.plusSeconds(24)))
            assertEquals(beforeMetadata.ends, current.ends)
            assertEquals(original.ends.single().impacts, current.ends.single().impacts.take(3))
            assertEquals(original.ends.single().impacts.sumOf { it.scoreFinal } + 9, current.ends.single().impacts.sumOf { it.scoreFinal })
            assertEquals(endUuid, current.ends.single().uuid)
            assertEquals(placed.uuid, current.ends.single().impacts.last().uuid)
            save(current.withEnd(current.ends.single().validate(now.plusSeconds(25))).complete(now.plusSeconds(26)))
            assertEquals(28, summarizeSessionScore(current).totalScore)
            assertEquals(1, summarizeSessionScore(current).xCount); assertEquals(1, summarizeSessionScore(current).missCount)
            assertEquals(session(100, true), assertIs<SessionReadResult.Found>(store.read(session(100, true).uuid)).session)
            finalSession = current
            v1Bytes = codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive)
            v2Bytes = codec.encode(store.fullV2())
        }
        assertEquals(listOf("3"), rows(path, "PRAGMA user_version"))
        openSessionStore(path).use { store ->
            assertEquals(finalSession, assertIs<SessionReadResult.Found>(store.read(finalSession.uuid)).session)
            assertContentEquals(v1Bytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertContentEquals(v2Bytes, codec.encode(store.fullV2()))
        }
        val legacy = assertIs<VersionedArchiveJsonRead.V1>(codec.decodeVersioned(v1Bytes)).archive
        openSessionStore(directory.resolve("legacy-restore.db")).use { store ->
            val preview = assertIs<ArchiveImportPreviewResult.Ready>(store.previewImport(legacy)).preview
            assertEquals(ArchiveImportResult.Imported(2, 0), store.importArchive(preview))
            assertEquals(finalSession, assertIs<SessionReadResult.Found>(store.read(finalSession.uuid)).session)
            assertContentEquals(v1Bytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertTrue(store.fullV2().bows.isEmpty()) // Opaque references survive, without inventing material.
            assertEquals(id(802), store.fullV2().sessions.first { it.uuid == finalSession.uuid }.bowUuid)
        }
        val full = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(v2Bytes)).archive
        val restoredPath = directory.resolve("full-restore.db")
        openSessionStore(restoredPath).use { assertIs<ArchiveV2ImportResult.Imported>(it.restoreV2(full)) }
        openSessionStore(restoredPath).use { assertEquals(full, it.fullV2()) }
        assertEquals(contents(path), contents(restoredPath))
    }

    @Test fun `concurrent equipment link and failed reposition never erase historical decisions or traces`() = runBlocking<Unit> {
        richV1()
        openSessionStore(path).use { first -> openSessionStore(path).use { second ->
            val original = assertIs<SessionReadResult.Found>(first.read(session().uuid)).session
            val linked = original.withEquipmentReferences(id(802), id(803), now.plusSeconds(20))
            assertIs<SessionUpdateResult.Updated>(second.update(archive(original), archive(linked)))
            val impact = original.ends.single().impacts.first()
            val moved = impact.reposition(NormalizedPoint(.35f, 0f), now.plusSeconds(21), "Synthetic reposition after judgment")
            val stale = original.withEnd(original.ends.single().withImpact(moved))
            val before = contents(path)
            assertIs<SessionUpdateResult.Conflict>(first.update(archive(original), archive(stale)))
            assertEquals(before, contents(path))
            val proposed = linked.withEnd(linked.ends.single().withImpact(moved))
            rows(path, "CREATE TRIGGER reject_new_trace BEFORE INSERT ON impact_repositions BEGIN SELECT RAISE(ABORT, 'synthetic trace write failure'); END")
            assertIs<SessionUpdateResult.TechnicalFailure>(first.update(archive(linked), archive(proposed)))
            assertEquals(before, contents(path))
            assertEquals(linked, assertIs<SessionReadResult.Found>(first.read(linked.uuid)).session)
            rows(path, "DROP TRIGGER reject_new_trace")
            assertIs<SessionUpdateResult.Updated>(first.update(archive(linked), archive(proposed)))
            val confirmed = assertIs<SessionReadResult.Found>(first.read(linked.uuid)).session
            assertEquals(proposed, confirmed); assertEquals(3, confirmed.ends.single().impacts.first().repositions.size)
            assertEquals(moved.finalScoreDecision, confirmed.ends.single().impacts.first().finalScoreDecision)
            assertEquals(id(802), confirmed.bowUuid)
        } }
        openSessionStore(path).use { assertEquals(3, assertIs<SessionReadResult.Found>(it.read(session().uuid)).session.ends.single().impacts.first().repositions.size) }
    }

}
