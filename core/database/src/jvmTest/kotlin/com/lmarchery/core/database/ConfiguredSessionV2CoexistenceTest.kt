package com.lmarchery.core.database

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
        }
        val originalRows = EquipmentTestData.contents(path)
        assertEquals(listOf("2"), EquipmentTestData.rows(path, "PRAGMA user_version"))
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
            assertEquals(fullV2.profiles, legacy.profiles)
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
}
