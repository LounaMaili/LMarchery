package com.lmarchery.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.export.ArchiveJsonCodec
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.scoring.scoreBoundaries
import com.lmarchery.core.model.session.ArrowCountMode
import com.lmarchery.core.model.session.EndConfiguration
import com.lmarchery.core.model.session.NextEndConfigurationChoice
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import com.lmarchery.core.scoring.scoreArrowImpact
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.scoring.configured.EndConfigurationOrigin
import com.lmarchery.core.scoring.configured.InitialConfigurationOrigin
import com.lmarchery.core.scoring.configured.summarizeConfiguredSession
import com.lmarchery.core.scoring.summarizeSessionScore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Integration: real Room 2 disk data remain untouched by the new, pure configurable aggregate. */
class ConfiguredSessionV2CoexistenceTest {
    @TempDir lateinit var directory: Path

    @Test fun `legacy Room2 snapshot can feed configured scoring without writing or losing equipment`() = runBlocking<Unit> {
        val path = directory.resolve("configured-read-only.db")
        val source = EquipmentTestData.session(completed = true)
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(EquipmentTestData.archive(source)))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(EquipmentTestData.bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(EquipmentTestData.arrows()))
            // V2 may also contain a profile that the historical V1 session archive does not provide.
            val extra = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(
                listOf(TargetProfileSnapshot(WA.compound40, WA.RULEBOOK_VERSION)), emptyList(), emptyList(), emptyList()))).archive
            assertIs<ArchiveV2ImportResult.Imported>(store.restoreV2(extra))
        }
        val originalRows = EquipmentTestData.contents(path)
        EquipmentTestData.denyWrites(path)
        assertEquals(listOf("3"), EquipmentTestData.rows(path, "PRAGMA user_version"))
        openSessionStore(path).use { store ->
            // Explicit historical V1 read is separate from the full V2 backup read.
            val fullV2 = store.fullV2()
            val legacy = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            val configured = ConfiguredSession.fromLegacyArchive(legacy, source.uuid)
            assertEquals(source.uuid, configured.uuid)
            assertEquals(source.status, configured.status)
            assertEquals(source.bowUuid, configured.metadata.bowUuid)
            assertEquals(source.arrowSetUuid, configured.metadata.arrowSetUuid)
            assertEquals(source.targetType, configured.initialConfiguration.targetType)
            assertEquals(InitialConfigurationOrigin.LEGACY_DERIVED, configured.initialConfigurationOrigin)
            assertTrue(configured.ends.all { it.origin == EndConfigurationOrigin.LegacyDerived })
            assertEquals(source.ends, configured.ends.map { it.end })
            assertEquals(summarizeSessionScore(source).totalScore.toLong(),
                summarizeConfiguredSession(configured).totals.validated.score)
            assertEquals(fullV2.profiles.filter { it.definition?.uuid == source.targetType.uuid }, legacy.profiles)
            assertEquals(legacy.profiles.size + 1, fullV2.profiles.size)
            assertTrue(fullV2.profiles.any { it == TargetProfileSnapshot(WA.compound40, WA.RULEBOOK_VERSION) })
            assertEquals(fullV2.sessions, legacy.sessions)
            assertEquals(listOf(EquipmentTestData.bow()), fullV2.bows)
            assertEquals(listOf(EquipmentTestData.arrows()), fullV2.arrowSets)
            assertEquals(fullV2, store.fullV2())
        }
        assertEquals(originalRows, EquipmentTestData.contents(path))
        openSessionStore(path).use { store ->
            assertEquals(listOf(EquipmentTestData.bow()), store.fullV2().bows)
            assertEquals(listOf(EquipmentTestData.arrows()), store.fullV2().arrowSets)
        }
        assertEquals(originalRows, EquipmentTestData.contents(path))
    }

    @Test fun `adding configured end in memory never creates a Room2 end or changes rows`() = runBlocking<Unit> {
        val path = directory.resolve("configured-not-persisted.db")
        val source = EquipmentTestData.emptySession()
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(EquipmentTestData.archive(source)))
        }
        val originalRows = EquipmentTestData.contents(path)
        EquipmentTestData.denyWrites(path)
        openSessionStore(path).use { store ->
            val historical = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            val configured = ConfiguredSession.fromLegacyArchive(historical, source.uuid)
            assertTrue(configured.ends.isEmpty())
            val next = configured.createNextEnd(EquipmentTestData.id(910), source.updatedAt.plusSeconds(1))
            assertEquals(1, next.ends.size)
            assertEquals(0L, summarizeConfiguredSession(next).totals.recorded.score)
            assertTrue(assertIs<SessionReadResult.Found>(store.read(source.uuid)).session.ends.isEmpty())
            assertTrue(store.fullV2().sessions.single().ends.isEmpty())
        }
        assertEquals(originalRows, EquipmentTestData.contents(path))
    }

    @Test fun `populated real V1 migration keeps archives and catalog intact while configured mixed decisions stay in memory`() = runBlocking<Unit> {
        val path = directory.resolve("configured-migrated-v1.db")
        val seed = directory.resolve("v1-row-source.db")
        val source = EquipmentTestData.session()
        val codec = ArchiveJsonCodec()
        val legacyBytes = openSessionStore(seed).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(EquipmentTestData.archive(source)))
            codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive)
        }
        // Build from the frozen V1 DDL/hash. The V2 seed supplies only compatible row data;
        // no V2 database is relabelled as V1 and no production factory or migration is changed.
        val ddl = requireNotNull(javaClass.getResourceAsStream("/room-schema-v1.sql"))
            .bufferedReader().use { it.readLines() }
        BundledSQLiteDriver().open(path.toString()).use { c ->
            fun sql(text: String) { c.prepare(text).use { it.step() } }
            sql("PRAGMA foreign_keys = ON")
            sql("ATTACH DATABASE '${seed.toString().replace("'", "''")}' AS seed")
            sql("BEGIN IMMEDIATE")
            ddl.filter { it.isNotBlank() && !it.startsWith("--") }.forEach(::sql)
            EquipmentTestData.oldTables.forEach { sql("INSERT INTO `$it` SELECT * FROM seed.`$it`") }
            sql("COMMIT")
            sql("DETACH DATABASE seed")
        }
        assertEquals(listOf("1"), EquipmentTestData.rows(path, "PRAGMA user_version"))
        assertEquals(listOf("6d9db7df5796fd7567c9cfb0a7a8d846"),
            EquipmentTestData.rows(path, "SELECT identity_hash FROM room_master_table WHERE id = 42"))
        assertTrue(EquipmentTestData.rows(path, "SELECT name FROM sqlite_master WHERE name IN ('bows','arrow_sets')").isEmpty())
        val oldRows = EquipmentTestData.contents(path, EquipmentTestData.oldTables)
        assertTrue(oldRows.values.all { it.isNotEmpty() }) // All eight tables contain history, decisions and traces.
        val oldSchema = EquipmentTestData.rows(path,
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name != 'room_master_table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        openSessionStore(path).use { store ->
            assertEquals(oldRows, EquipmentTestData.contents(path, EquipmentTestData.oldTables))
            assertContentEquals(legacyBytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(EquipmentTestData.bow()))
            assertIs<EquipmentCreateResult.Created>(store.equipment.create(EquipmentTestData.arrows()))
        }
        assertEquals(listOf("3"), EquipmentTestData.rows(path, "PRAGMA user_version"))
        val expectedRows = oldRows + ("entity_identities" to (oldRows.getValue("entity_identities") + listOf(
            "${EquipmentTestData.id(800)}|BOW", "${EquipmentTestData.id(801)}|ARROW_SET")).sorted())
        assertEquals(expectedRows, EquipmentTestData.contents(path, EquipmentTestData.oldTables))
        assertEquals(oldSchema, EquipmentTestData.rows(path,
            "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name NOT IN ('room_master_table','bows','arrow_sets','session_configurations','end_configurations') AND name NOT LIKE 'sqlite_%' ORDER BY name"))
        val before = EquipmentTestData.contents(path)
        EquipmentTestData.denyWrites(path)
        val denied = assertFailsWith<Exception> { EquipmentTestData.rows(path, "UPDATE sessions SET title = title") }
        assertTrue(denied.message.orEmpty().contains("read only")) // Even an otherwise invisible no-op write is blocked.
        lateinit var fullBytes: ByteArray
        openSessionStore(path).use { store ->
            val full = store.fullV2(); fullBytes = codec.encode(full)
            val historical = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertContentEquals(legacyBytes, codec.encode(historical))
            val configured = ConfiguredSession.fromLegacyArchive(historical, source.uuid)
            val target = WA.compound40
            val configuration = EndConfiguration(target, WA.RULEBOOK_VERSION, 25, ArrowCountMode.FIXED, 6,
                isIndoor = true, scoringMode = source.scoringMode)
            val at = source.updatedAt.plusSeconds(1)
            val nextUuid = EquipmentTestData.id(910); val impactUuid = EquipmentTestData.id(911)
            val placed = scoreArrowImpact(impactUuid, 1, target, NormalizedPoint(.075f, 0f),
                configuration.scoringMode, createdAt = at)
            val next = configured.createNextEnd(nextUuid, at, NextEndConfigurationChoice.Replace(configuration))
                .withImpact(nextUuid, placed)
            assertEquals(9, placed.scoreFinal)
            val boundary = target.scoreBoundaries().single { it.inner.score == 10 && it.outer.score == 9 }
            val judged = next.decideImpact(nextUuid, impactUuid,
                ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner, at.plusSeconds(1)), at.plusSeconds(1))
            val moved = judged.repositionImpact(nextUuid, impactUuid, NormalizedPoint(.25f, 0f),
                at.plusSeconds(2), "Synthetic integration correction")
            val impact = moved.ends.last().end.impacts.single()
            assertEquals(8, impact.scoreFinal)
            assertEquals(judged.ends.last().end.impacts.single().finalScoreDecision, impact.repositions.single().previousDecision)
            assertEquals(WA.RULEBOOK_VERSION, moved.ends.last().configuration.regulatoryEdition)
            assertEquals(configured.ends, moved.ends.take(configured.ends.size))
            assertSame(configured.ends.single().end, moved.ends.first().end)
            assertEquals(source.ends.single().impacts.sumOf { it.scoreFinal }.toLong() + 8,
                summarizeConfiguredSession(moved).totals.recorded.score)
            assertEquals(full, store.fullV2())
            assertContentEquals(fullBytes, codec.encode(store.fullV2()))
            assertEquals(source, assertIs<SessionReadResult.Found>(store.read(source.uuid)).session)
        }
        assertEquals(before, EquipmentTestData.contents(path))
        assertTrue(EquipmentTestData.rows(path, "PRAGMA foreign_key_check").isEmpty())
        openSessionStore(path).use { store ->
            assertContentEquals(fullBytes, codec.encode(store.fullV2()))
            assertContentEquals(legacyBytes, codec.encode(assertIs<ArchiveReadResult.Found>(store.readArchive()).archive))
        }
        assertEquals(before, EquipmentTestData.contents(path))
    }

}
