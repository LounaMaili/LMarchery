package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as sessions
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.model.weather.*
import com.lmarchery.core.scoring.archive.v6.*
import com.lmarchery.feature.archiveimport.ImportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.test.*

class ManualWeatherTest {
    @TempDir lateinit var dir: Path
    private val site = ShootingSite(f.id(7001), "Terrain synthétique")
    private val north = LineOrientation.manual(0.0, NorthReference.TRUE_NORTH, 0.5, f.now)
    private val line = ShootingLine(f.id(7002), site.uuid, "A", orientation = north)
    private val session = sessions.rich()
    private val capture = SessionPlace(session.uuid, session.createdAt, site, line, north)
    private val observation = WeatherObservation(f.id(7100), session.uuid, WeatherScope(session.ends.map { it.uuid }),
        f.now.plusSeconds(60), f.now, f.now.plusSeconds(120), 4.0, 6.0, WindFrom(0.0, NorthReference.TRUE_NORTH, 0.5),
        temperatureC = -0.25, rain = RainReport.RAIN)
    private val tables = ArchiveV3DiskData.tables + listOf("impact_cancellations", "shooting_sites", "shooting_lines",
        "session_places", "weather_observations", "weather_ends")

    private suspend fun seed(store: RoomSessionStore) {
        store.places.save(null, site)
        store.places.save(null, line)
        assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(session, capture))
    }

    @Test fun missingCalmQualitativeRainAndMeasuredZeroAreDistinct() {
        val v = observation.copy(meanMps = null, from = null)
        assertNull(v.meanMps)
        assertNull(v.precipitationMm)
        assertNotEquals(v, v.copy(meanMps = 0.0))
        assertNotEquals(v, v.copy(precipitationMm = 0.0))
        assertFailsWith<IllegalArgumentException> { v.copy(validUntil = null, precipitationMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { v.copy(validUntil = v.validFrom) }
        assertFailsWith<IllegalArgumentException> { v.copy(gustMps = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { v.copy(variableWind = true, from = observation.from) }
        assertFailsWith<IllegalArgumentException> { WeatherScope(listOf(line.uuid, line.uuid)) }
    }

    @Test fun historicalComponentsUseAdoptedNorthAndHandleCardinalObliqueUnknownAndCalm() = runBlocking<Unit> {
        openSessionStore(dir.resolve("wind.db")).use { store ->
            seed(store)
            val directions = listOf(0.0, 90.0, 180.0, 270.0, 45.0)
            val rows = directions.mapIndexed { i, degrees -> observation.copy(uuid = f.id(7200 + i),
                from = WindFrom(degrees, NorthReference.TRUE_NORTH, 0.5)) }
            val absent = observation.copy(uuid = f.id(7300), from = null)
            val calm = absent.copy(uuid = f.id(7301), meanMps = 0.0)
            val magnetic = observation.copy(uuid = f.id(7302), from = WindFrom(0.0, NorthReference.MAGNETIC_NORTH))
            val uncertain = observation.copy(uuid = f.id(7303), from = WindFrom(0.0, NorthReference.TRUE_NORTH, 90.0))
            val unknownQuality = observation.copy(uuid = f.id(7305), from = WindFrom(0.0, NorthReference.TRUE_NORTH))
            val unknownSpeed = observation.copy(uuid = f.id(7304), meanMps = null)
            for (o in rows + listOf(absent, calm, magnetic, uncertain, unknownSpeed, unknownQuality)) store.weather.record(o)
            store.places.save(line, line.copy(orientation = north.copy(degrees = 180.0)))
            assertNull(store.readArchiveV6().historicalWind(rows.first().uuid))
            assertIs<ConfiguredWriteResult.Updated>(store.updateConfiguredSession(session, session.complete(f.now.plusSeconds(200))))
            val archive = store.readArchiveV6()
            val expected = listOf(4.0 to 0.0, 0.0 to 4.0, -4.0 to 0.0, 0.0 to -4.0, sqrt(8.0) to sqrt(8.0))
            for ((o, components) in rows.zip(expected)) {
                val wind = assertNotNull(archive.historicalWind(o.uuid))
                assertEquals(components.first, wind.fromFrontMps, 1e-12)
                assertEquals(components.second, wind.fromRightMps, 1e-12)
            }
            for (o in listOf(absent, magnetic, uncertain, unknownSpeed, unknownQuality)) assertNull(archive.historicalWind(o.uuid))
            assertEquals(HistoricalWind(0.0, 0.0), archive.historicalWind(calm.uuid))
            assertFailsWith<IllegalArgumentException> { store.weather.record(observation) }
        }
    }

    @Test fun strictRoundTripThroughBothCoordinatorsAndSecondDiskFile() = runBlocking<Unit> {
        val source = dir.resolve("source.db")
        val restored = dir.resolve("restored.db")
        lateinit var expected: DomainArchiveV6
        lateinit var bytes: ByteArray
        AppIntegrationHarness(source).use { h ->
            seed(h.store)
            val base = h.store.readArchiveV5()
            val atSession = observation.copy(uuid = f.id(7101), scope = WeatherScope(), rain = RainReport.NO_RAIN,
                precipitationMm = 0.0, meanMps = null, from = null)
            h.store.weather.record(observation)
            h.store.weather.record(atSession)
            h.store.weather.record(observation)
            expected = h.store.readArchiveV6()
            assertEquals(base, expected.base)
            assertEquals(listOf(observation, atSession), expected.observations)
            assertFailsWith<CorruptStorage> { h.store.readArchiveV5() }
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(h.store.readArchiveV4())
            h.refresh()
            bytes = h.export(6, "weather.json")
            assertEquals(expected, assertIs<ArchiveDocumentRead.V6>(ArchiveDocumentDecoder().decode(bytes)).archive)
            val json = bytes.decodeToString()
            for (bad in listOf(json.replaceFirst("\"meanMps\"", "\"unknown\""),
                json.replaceFirst("\"variableWind\"", "\"meanMps\""), json.replaceFirst("\"MANUAL\"", "\"ALIEN\""))) {
                assertNotEquals(json, bad)
                assertIs<ArchiveDocumentRead.Rejected>(ArchiveDocumentDecoder().decode(bad.encodeToByteArray()))
            }
        }
        AppIntegrationHarness(restored).use { h ->
            h.refresh()
            assertEquals(2, assertIs<ImportState.PreviewV4>(h.preview("weather.json", bytes)).newObservations)
            assertEquals(6, assertIs<ImportState.SucceededV4>(h.confirm()).version)
            assertEquals(expected, h.store.readArchiveV6())
            f.denyWrites(restored, tables)
            h.preview("again.json", bytes)
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent)
        }
        openSessionStore(restored).use { store -> assertEquals(expected, store.readArchiveV6()) }
        assertRecipeIntegrity(restored)
    }

    @Test fun stalePreviewConflictOrFailedAssociationCannotPartiallyRestore() = runBlocking<Unit> {
        val path = dir.resolve("conflict.db")
        openSessionStore(path).use { store ->
            seed(store)
            val base = store.readArchiveV5()
            val expected = DomainArchiveV6(base, listOf(observation))
            fun incoming(o: WeatherObservation) = DomainArchiveV6(base, listOf(o))
            val foreign = observation.copy(scope = WeatherScope(listOf(f.id(7920))))
            assertFailsWith<IllegalArgumentException> { incoming(foreign) }
            assertFailsWith<IllegalArgumentException> { store.weather.record(foreign) }
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV6(expected)).preview
            val previous = f.contents(path, tables)
            f.rows(path, "CREATE TRIGGER refuse_link BEFORE INSERT ON weather_ends BEGIN SELECT RAISE(ABORT,'weather'); END")
            assertIs<ArchiveV4ImportResult.CommitUnconfirmed>(store.importArchiveV4(preview))
            assertEquals(previous, f.contents(path, tables))
            store.weather.record(observation.copy(uuid = f.id(7401), scope = WeatherScope()))
            assertIs<ArchiveV4ImportResult.Conflict>(store.importArchiveV4(preview))
            val divergent = observation.copy(uuid = f.id(7401))
            assertIs<ArchiveV4PreviewResult.NeedsIntervention>(store.previewImportV6(incoming(divergent)))
        }
    }

    @Test fun severalExplicitEndsAndHistoricalCoordinatesSurviveInV6WithConsentBeforeSharing() = runBlocking<Unit> {
        val file = dir.resolve("several.db")
        val located = site.copy(position = GeoPosition(0.0, -0.0, ManualOrigin.MANUAL, 10.0))
        val multi = session.createNextEnd(f.id(7500), f.now.plusSeconds(180))
        val saved = capture.copy(site = located)
        val o = observation.copy(scope = WeatherScope(multi.ends.reversed().map { it.uuid }))
        AppIntegrationHarness(file).use { h ->
            h.store.places.save(null, located)
            h.store.places.save(null, line)
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(multi, saved))
            h.store.places.save(located, site)
            h.store.weather.record(o)
            assertEquals(2, o.scope.endUuids.size)
            assertEquals(listOf(o), h.store.weather.details(multi.uuid).observations)
            assertEquals(saved, h.store.weather.details(multi.uuid).place)
            h.refresh()
            h.onUi { h.exporter.beginV6() }
            val prompt = assertIs<com.lmarchery.feature.export.ExportState.ShareCoordinates>(
                h.awaitUiState(h.exporter.state) { it is com.lmarchery.feature.export.ExportState.ShareCoordinates || it is com.lmarchery.feature.export.ExportState.Failed })
            h.onUi {
                assertNull(h.exporter.claimDestinationRequest())
                h.exporter.shareCoordinates(prompt.destination.requestId, false)
            }
            assertEquals(0L, h.documents.outputs.get())
        }
        openSessionStore(file).use { store ->
            val value = store.readArchiveV6()
            assertEquals(o, value.observations.single())
            assertEquals(value, ArchiveV6JsonCodec().decode(ArchiveV6JsonCodec().encode(value)))
        }
    }

    @Test fun lostAcknowledgementIsReconciledByTheAndroidJournalWithoutAnotherWrite() = runBlocking<Unit> {
        val file = dir.resolve("lost.db")
        val armed = java.util.concurrent.atomic.AtomicBoolean()
        val inserted = java.util.concurrent.atomic.AtomicBoolean()
        val commitLost = java.util.concurrent.atomic.AtomicBoolean()
        val connection = hookedStore(file) { sql, _ ->
            if (armed.get() && sql.startsWith("INSERT") && sql.contains("`weather_observations`")) inserted.set(true)
            if (sql == "END TRANSACTION" && inserted.get() && armed.compareAndSet(true, false)) {
                commitLost.set(true)
                error("Synthetic loss of acknowledgement after SQLite commit")
            }
        }
        AppIntegrationHarness(file, connection).use { h ->
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(session))
            val journal = com.lmarchery.feature.weather.WeatherJournal(h.store.weather, h.scope, h.clock) { observation.uuid }
            h.onUi { journal.load(session.uuid) }
            h.awaitUiState(journal.state) { it.loaded }
            armed.set(true)
            h.onUi {
                journal.openForm(session.uuid)
                val id = assertNotNull(journal.state.value.formId)
                journal.save(session.uuid, id) { observation }
                journal.save(session.uuid, id) { error("Repeated submission") }
            }
            val uncertain = h.awaitUiState(journal.state) { !it.busy && it.pending != null }
            assertEquals(observation, uncertain.pending)
            assertTrue(commitLost.get())
            f.denyWrites(file, tables)
            h.onUi { journal.load(session.uuid) }
            val recovered = h.awaitUiState(journal.state) { it.loaded && !it.busy }
            assertNull(recovered.pending)
            assertEquals(listOf(observation), recovered.rows)
            h.onUi { journal.save(session.uuid, observation.uuid) { error("Stale confirmation after re-read") } }
            assertIs<ArchiveReadResult.NeedsIntervention>(h.store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(h.store.readArchiveV2())
            assertIs<ArchiveV3ReadResult.NeedsIntervention>(h.store.readArchiveV3())
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(h.store.readArchiveV4())
            assertFailsWith<CorruptStorage> { h.store.readArchiveV5() }
        }
        openSessionStore(file).use { assertEquals(listOf(observation), it.readArchiveV6().observations) }
    }

    @Test fun missingAssociationCannotSilentlyTurnAnEndObservationIntoASessionObservation() = runBlocking<Unit> {
        val file = dir.resolve("bad-links.db")
        openSessionStore(file).use { store ->
            seed(store)
            store.weather.record(observation)
            f.rows(file, "DELETE FROM weather_ends")
            assertFailsWith<CorruptStorage> { store.weather.list(session.uuid) }
            assertFailsWith<CorruptStorage> { store.readArchiveV6() }
        }
    }

    @Test fun v5MigrationKeepsEveryOldTableAndDoesNotInventWeather() = runBlocking<Unit> {
        val path = dir.resolve("migration.db")
        ArchiveV3DiskData.historical(path, 5)
        ArchiveV3DiskData.raw(path, "INSERT INTO entity_identities VALUES('${site.uuid}','SITE')",
            "INSERT INTO shooting_sites(uuid,name) VALUES('${site.uuid}','Lieu ancien')")
        val existing = tables - listOf("weather_observations","weather_ends")
        val before = f.contents(path, existing)
        openSessionStore(path).use { store ->
            val full = store.readArchiveV6()
            assertTrue(full.observations.isEmpty())
            assertEquals("Lieu ancien", full.base.places.sites.single().name)
            assertEquals(full.base, store.readArchiveV5())
        }
        assertEquals(before, f.contents(path, existing))
        assertRecipeIntegrity(path)
    }
}
