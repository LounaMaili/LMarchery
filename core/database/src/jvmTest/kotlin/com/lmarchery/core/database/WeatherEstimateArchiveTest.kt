package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.model.weather.*
import com.lmarchery.core.scoring.archive.v7.DomainArchiveV7
import com.lmarchery.feature.archiveimport.ImportState
import com.lmarchery.feature.export.ExportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WeatherEstimateArchiveTest {
    @TempDir lateinit var dir: Path
    private val session = ConfiguredWriteData.rich()
    private val manual = WeatherObservation(f.id(8020), session.uuid, WeatherScope(), f.now, f.now, temperatureC = 10.0)
    private val estimate = SessionEstimate(f.id(8021), session.uuid, WeatherEstimate("Synthetic", EstimateKind.FORECAST,
        "selected", "synthetic-grid", f.now, f.now.minusSeconds(3600), f.now.plusSeconds(3600), 3600,
        WeatherPoint(0.25, -0.0), WeatherPoint(0.24, 0.01), 10.0, 2.0, 1.5, 3.0, 135.0, null, 0.0))
    private val tables = ArchiveV3DiskData.tables + listOf("impact_cancellations", "shooting_sites", "shooting_lines",
        "session_places", "weather_observations", "weather_ends", "weather_estimates")

    private suspend fun seed(store: RoomSessionStore) {
        // No point in L1: the V7 SAF consent must be triggered solely by the provider data.
        val site = ShootingSite(f.id(8022), "Site synthétique")
        store.places.save(null, site)
        val adopted = SessionPlace(session.uuid, f.now, site, null, null)
        assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(session, adopted))
        store.weather.record(manual)
    }

    @Test fun explicitCoordinateConsentStrictV7AndAppendOnlyRestoreThroughDocumentCoordinators() = runBlocking<Unit> {
        val source = dir.resolve("source.db")
        val target = dir.resolve("target.db")
        lateinit var bytes: ByteArray
        lateinit var expected: DomainArchiveV7
        AppIntegrationHarness(source).use { h ->
            seed(h.store)
            val original = h.store.readArchiveV6()
            h.store.weather.recordEstimate(estimate)
            h.store.weather.recordEstimate(estimate)
            expected = h.store.readArchiveV7()
            assertEquals(original, expected.base)
            assertEquals(listOf(estimate), expected.estimates)
            assertIs<ArchiveReadResult.NeedsIntervention>(h.store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(h.store.readArchiveV2())
            assertIs<ArchiveV3ReadResult.NeedsIntervention>(h.store.readArchiveV3())
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(h.store.readArchiveV4())
            assertFailsWith<CorruptStorage> { h.store.readArchiveV5() }
            assertFailsWith<CorruptStorage> { h.store.readArchiveV6() }
            h.refresh()
            suspend fun prompt(): String {
                h.onUi { h.exporter.beginV7() }
                return assertIs<ExportState.ShareCoordinates>(h.awaitUiState(h.exporter.state) {
                    it is ExportState.ShareCoordinates || it is ExportState.Failed
                }).destination.requestId
            }
            val declined = prompt()
            h.onUi { assertNull(h.exporter.claimDestinationRequest()); h.exporter.shareCoordinates(declined, false) }
            assertIs<ExportState.Cancelled>(h.exporter.state.value)
            val ticket = prompt()
            h.onUi {
                h.exporter.shareCoordinates(declined, true)
                assertIs<ExportState.ShareCoordinates>(h.exporter.state.value)
                h.exporter.shareCoordinates(ticket, true)
                assertNotNull(h.exporter.claimDestinationRequest())
                h.exporter.destinationChosen(ticket, h.documents.uri("estimate.json"))
            }
            val success = h.awaitUiState(h.exporter.state) { it is ExportState.Succeeded || it is ExportState.Failed }
            assertEquals(1, assertIs<ExportState.Succeeded>(success).estimateCount)
            bytes = h.documents.bytes("estimate.json")
            assertEquals(expected, assertIs<ArchiveDocumentRead.V7>(ArchiveDocumentDecoder().decode(bytes)).archive)
        }
        openSessionStore(source).use { assertEquals(expected, it.readArchiveV7()) }
        AppIntegrationHarness(target).use { h ->
            h.refresh()
            val preview = assertIs<ImportState.PreviewV4>(h.preview("copy.json", bytes))
            assertEquals(1, preview.newEstimates)
            assertTrue(preview.containsCoordinates)
            assertEquals(7, assertIs<ImportState.SucceededV4>(h.confirm()).version)
            assertEquals(expected, h.store.readArchiveV7())
            f.denyWrites(target, tables)
            h.preview("again.json", bytes)
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent)
            val different = estimate.copy(estimate = estimate.estimate.copy(model = "other"))
            val conflict = ArchiveV7JsonCodec().encode(DomainArchiveV7(expected.base, listOf(different)))
            assertIs<ImportState.Failed>(h.preview("conflict.json", conflict))
            assertEquals(expected, h.store.readArchiveV7())
        }
        openSessionStore(target).use { assertEquals(expected, it.readArchiveV7()) }
        assertRecipeIntegrity(target)
    }

    @Test fun lateInsertRollsBackTheWholeArchiveAndOldPreviewCannotOverwrite() = runBlocking<Unit> {
        val archive = openSessionStore(dir.resolve("archive.db")).use {
            seed(it); it.weather.recordEstimate(estimate); it.readArchiveV7()
        }
        val target = dir.resolve("refusal.db")
        openSessionStore(target).use { store ->
            val empty = store.readArchiveV7()
            val plan = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV7(archive)).preview
            f.rows(target, "CREATE TRIGGER refuse_estimate BEFORE INSERT ON weather_estimates BEGIN SELECT RAISE(ABORT,'test'); END")
            assertIs<ArchiveV4ImportResult.CommitUnconfirmed>(store.importArchiveV4(plan))
            assertEquals(empty, store.readArchiveV7())
            seed(store)
            assertIs<ArchiveV4ImportResult.Conflict>(store.importArchiveV4(plan))
            assertEquals(archive.base, store.readArchiveV7().base)
        }
        assertRecipeIntegrity(target)
    }

    @Test fun archiveRejectsAmbiguousDatesWrongTypesMissingOwnersAndCrossKindCollisions() = runBlocking<Unit> {
        openSessionStore(dir.resolve("strict.db")).use { store ->
            seed(store)
            val base = store.readArchiveV6()
            assertFails { DomainArchiveV7(base, listOf(estimate.copy(uuid = manual.uuid))) }
            assertFails { DomainArchiveV7(base, listOf(estimate.copy(sessionUuid = f.id(8999)))) }
            assertFails { DomainArchiveV7(base, listOf(estimate, estimate)) }
            val json = ArchiveV7JsonCodec().encode(DomainArchiveV7(base, listOf(estimate))).decodeToString()
            val valid = com.lmarchery.core.model.time.UtcTimestamps.format(estimate.estimate.validAt)
            val offset = estimate.estimate.validAt.atOffset(java.time.ZoneOffset.ofHours(-5)).toString()
            val decoder = ArchiveDocumentDecoder()
            val stamp = "\"validAt\": \"$valid\""
            val alternate = json.replace(stamp, "\"validAt\": \"$offset\"")
            assertNotEquals(json, alternate)
            assertEquals(estimate, assertIs<ArchiveDocumentRead.V7>(decoder.decode(alternate.encodeToByteArray())).archive.estimates.single())
            for ((old, new) in listOf("FORECAST" to "MANUAL", "\"windMps\": 1.5" to "\"windMps\": \"1.5\"",
                "fromTrueDegrees" to "fromMagneticDegrees", stamp to "\"validAt\": \"${valid.removeSuffix("Z")}\"")) {
                val bad = json.replace(old, new)
                assertNotEquals(json, bad)
                assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(bad.encodeToByteArray()))
            }
        }
    }

    @Test fun genuineV6MigrationPreservesManualWeatherAndSportingTablesByteForByteInSql() = runBlocking<Unit> {
        val file = dir.resolve("v6.db")
        ArchiveV3DiskData.historical(file, 6)
        val id = f.id(8555)
        val sessionId = f.rows(file, "SELECT uuid FROM sessions ORDER BY uuid LIMIT 1").single()
        ArchiveV3DiskData.raw(file, "INSERT INTO entity_identities VALUES('$id','WEATHER')",
            "INSERT INTO weather_observations(uuid,sessionUuid,endCount,observedAt,validFrom,variable,temperatureBits,origin) " +
                "VALUES('$id','$sessionId',0,'2026-01-01T06:30:00Z','2026-01-01T06:30:00Z',0,${(-1.25).toBits()},'MANUAL')")
        val before = f.contents(file, tables - "weather_estimates")
        openSessionStore(file).use { store ->
            val full = store.readArchiveV7()
            assertTrue(full.estimates.isEmpty())
            assertEquals(-1.25, full.base.observations.single().temperatureC)
            assertEquals(full.base, store.readArchiveV6())
        }
        assertEquals(before, f.contents(file, tables - "weather_estimates"))
        assertRecipeIntegrity(file)
    }
}
