package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.ArchiveDocumentDecoder
import com.lmarchery.core.export.ArchiveDocumentRead
import com.lmarchery.core.export.ArchiveJsonCodec
import com.lmarchery.core.export.ArchiveV3JsonCodec
import com.lmarchery.core.export.ArchiveV4JsonCodec
import com.lmarchery.core.export.ArchiveV5JsonCodec
import com.lmarchery.core.export.ArchiveV6JsonCodec
import com.lmarchery.core.export.ArchiveV7JsonCodec
import com.lmarchery.core.export.ArchiveV8JsonCodec
import com.lmarchery.core.model.place.*
import com.lmarchery.core.model.weather.*
import com.lmarchery.core.scoring.archive.v6.historicalWind
import com.lmarchery.core.scoring.configured.configuredEndScoringContext
import com.lmarchery.core.scoring.grouping.GroupingMissPolicy
import com.lmarchery.core.scoring.grouping.calculateSelectionGrouping
import com.lmarchery.core.scoring.grouping.calculateTargetZoneDistribution
import com.lmarchery.feature.archiveimport.ImportState
import com.lmarchery.feature.export.ExportState
import com.lmarchery.feature.sessions.EndGroupingSummaryReader
import com.lmarchery.feature.sessions.EndGroupingSummarySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AndroidConsolidationTest {
    @TempDir lateinit var directory: Path
    private val v4Tables = ArchiveV3DiskData.tables + "impact_cancellations"
    private val v8Tables = v4Tables + listOf("shooting_sites", "shooting_lines", "session_places",
        "weather_observations", "weather_ends", "weather_estimates")

    @Test fun migrateRoom4InOneOpenAndReadSevenHistoricalFormats() = runBlocking<Unit> {
        val path = directory.resolve("legacy-room4.db")
        val original = ArchiveV3DiskData.source()
        ArchiveV3DiskData.historical(path, 4, original)
        val before = f.contents(path, v4Tables)
        val graph = openSessionStore(path).use { store ->
            val decoder = ArchiveDocumentDecoder()
            val legacy = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            val decodedV1 = assertIs<ArchiveDocumentRead.V1>(decoder.decode(ArchiveJsonCodec().encode(legacy)))
            assertEquals(legacy.toSnapshot(), decodedV1.archive.toSnapshot())
            val v2 = assertIs<ArchiveV2ReadResult.Found>(store.readArchiveV2()).archive
            assertEquals(original, v2)
            assertEquals(v2, assertIs<ArchiveDocumentRead.V2>(
                decoder.decode(ArchiveJsonCodec().encode(v2))).archive)
            val v3 = store.fullV3()
            assertEquals(v3, assertIs<ArchiveDocumentRead.V3>(decoder.decode(ArchiveV3JsonCodec().encode(v3))).archive)
            val v4 = assertIs<ArchiveV4ReadResult.Found>(store.readArchiveV4()).archive
            assertEquals(v4, assertIs<ArchiveDocumentRead.V4>(decoder.decode(ArchiveV4JsonCodec().encode(v4))).archive)
            val v5 = store.readArchiveV5()
            assertEquals(v5, assertIs<ArchiveDocumentRead.V5>(decoder.decode(ArchiveV5JsonCodec().encode(v5))).archive)
            val v6 = store.readArchiveV6()
            assertEquals(v6, assertIs<ArchiveDocumentRead.V6>(decoder.decode(ArchiveV6JsonCodec().encode(v6))).archive)
            val v7 = store.readArchiveV7()
            assertEquals(v7, assertIs<ArchiveDocumentRead.V7>(decoder.decode(ArchiveV7JsonCodec().encode(v7))).archive)
            assertEquals(v7, store.readArchiveV8())
            v7
        }
        assertEquals(before, f.contents(path, v4Tables))
        assertTrue(f.contents(path, v8Tables - v4Tables.toSet()).values.all { it.isEmpty() })
        openSessionStore(path).use { assertEquals(graph, it.readArchiveV8()) }
        assertRecipeIntegrity(path)
    }

    @Test fun completeArchiveV8RoundTripSupportsBothStatisticsAndAndroidSummary() = runBlocking<Unit> {
        val now = f.now
        val first = ConfiguredWriteData.rich()
        val firstEnd = first.ends.single().end
        val cancelled = firstEnd.impacts[1]
        val miss = firstEnd.impacts[2]
        val secondEnd = f.id(8301)
        val next = first.cancelImpact(firstEnd.uuid, cancelled.uuid, now.plusSeconds(8), "Synthetic cancellation")
            .validateEnd(firstEnd.uuid, now.plusSeconds(9))
            .createNextEnd(secondEnd, now.plusSeconds(10))
        val session = next.withImpact(secondEnd, ConfiguredWriteData.arrow(next, 1, 0f))
            .validateEnd(secondEnd, now.plusSeconds(13))
        val completed = session.complete(now.plusSeconds(14))
        val site = ShootingSite(f.id(8302), "Synthetic venue")
        val reading = CompassReading(359.75, now.minusSeconds(8), CompassMethod.ROTATION_VECTOR, CompassQuality.MEDIUM)
        val orientation = LineOrientation(0.5, NorthReference.MAGNETIC_NORTH, OrientationOrigin.SENSOR_CONFIRMED,
            confirmedAt = now.minusSeconds(2), reading = reading)
        val line = ShootingLine(f.id(8303), site.uuid, "Synthetic line", orientation = orientation)
        val adopted = SessionPlace(session.uuid, now, site, line, orientation)
        val observation = WeatherObservation(f.id(8304), session.uuid, WeatherScope(listOf(firstEnd.uuid, secondEnd)),
            now.plusSeconds(12), now.plusSeconds(1), meanMps = 2.0, from = WindFrom(90.0, NorthReference.TRUE_NORTH, 1.0))
        val estimate = SessionEstimate(f.id(8305), session.uuid, WeatherEstimate("Synthetic", EstimateKind.FORECAST,
            "selected", "synthetic-grid", now, now.minusSeconds(3600), now.plusSeconds(3600), 3600,
            WeatherPoint(0.25, -0.0), WeatherPoint(0.24, 0.01), 10.0, 2.0, 1.5, 3.0, 135.0, null, 0.0))

        val source = directory.resolve("complete.db")
        val (archive, json) = AppIntegrationHarness(source).use { h ->
            h.store.places.save(null, site)
            h.store.places.save(null, line)
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(session, adopted))
            h.store.weather.record(observation)
            h.store.weather.recordEstimate(estimate)
            assertIs<ConfiguredWriteResult.Updated>(h.store.updateConfiguredSession(session, completed))
            val expected = h.store.readArchiveV8()
            assertEquals(completed, expected.base.base.sporting.sessions.single())
            assertEquals(adopted, expected.base.base.places.snapshots.single())
            assertEquals(listOf(site), expected.base.base.places.sites)
            assertEquals(listOf(line), expected.base.base.places.lines)
            assertEquals(listOf(observation), expected.base.observations)
            assertEquals(listOf(estimate), expected.estimates)
            assertNull(expected.base.historicalWind(observation.uuid))
            assertIs<ArchiveReadResult.NeedsIntervention>(h.store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(h.store.readArchiveV2())
            assertIs<ArchiveV3ReadResult.NeedsIntervention>(h.store.readArchiveV3())
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(h.store.readArchiveV4())
            assertFailsWith<CorruptStorage> { h.store.readArchiveV5() }
            assertFailsWith<CorruptStorage> { h.store.readArchiveV6() }
            assertFailsWith<CorruptStorage> { h.store.readArchiveV7() }
            h.refresh()
            h.onUi { h.exporter.beginV8() }
            val prompt = assertIs<ExportState.ShareCoordinates>(h.awaitUiState(h.exporter.state) {
                it is ExportState.ShareCoordinates || it is ExportState.Failed
            })
            h.onUi {
                h.exporter.shareCoordinates(prompt.destination.requestId, true)
                assertNotNull(h.exporter.claimDestinationRequest())
                h.exporter.destinationChosen(prompt.destination.requestId, h.documents.uri("complete.json"))
            }
            assertEquals(8, assertIs<ExportState.Succeeded>(h.awaitUiState(h.exporter.state) {
                it is ExportState.Succeeded || it is ExportState.Failed
            }).schemaVersion)
            expected to h.documents.bytes("complete.json")
        }
        openSessionStore(source).use { assertEquals(archive, it.readArchiveV8()) }
        assertRecipeIntegrity(source)
        assertEquals(archive, assertIs<ArchiveDocumentRead.V8>(ArchiveDocumentDecoder().decode(json)).archive)

        val restored = directory.resolve("restored.db")
        AppIntegrationHarness(restored).use { h ->
            h.refresh()
            val preview = assertIs<ImportState.PreviewV4>(h.preview("complete.json", json))
            assertEquals(8, preview.version)
            assertTrue(preview.containsCoordinates)
            assertEquals(8, assertIs<ImportState.SucceededV4>(h.confirm()).version)
            f.denyWrites(restored, v8Tables)
            h.preview("same.json", json)
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent)
            val altered = json.decodeToString().replace("\"magneticDegrees\": 359.75", "\"magneticDegrees\": 359.5")
                .encodeToByteArray()
            val conflicting = assertIs<ArchiveDocumentRead.V8>(ArchiveDocumentDecoder().decode(altered)).archive
            assertNotEquals(archive, conflicting)
            assertIs<ImportState.Failed>(h.preview("conflict.json", altered))
            assertEquals(archive, h.store.readArchiveV8())
        }

        val rows = f.contents(restored, v8Tables)
        openSessionStore(restored).use { store ->
            val decoded = store.readArchiveV8()
            val sporting = decoded.base.base.sporting.sessions.single()
            assertEquals(completed, sporting)
            val contexts = sporting.ends.map { configuredEndScoringContext(sporting, it) }
            val initial = contexts.first()
            val reader = EndGroupingSummaryReader(EndGroupingSummarySource(initial.end, initial.configuration))
            val judgedScore = firstEnd.impacts.first().scoreFinal.toLong()
            for (policy in GroupingMissPolicy.entries) {
                val includesMiss = policy == GroupingMissPolicy.INCLUDE_MISSES
                val summary = reader.read(policy)
                val statistics = calculateSelectionGrouping(contexts, policy)
                val zones = calculateTargetZoneDistribution(contexts, policy)
                assertEquals(judgedScore, summary.score)
                assertEquals(if (includesMiss) 2 else 1, summary.grouping.impactCount)
                assertEquals(summary.grouping, statistics.ends.first())
                assertEquals(2, zones.selectedEndCount)
                assertEquals(statistics.counts, zones.counts)
                assertEquals(1, zones.counts.cancelledImpactCount)
                assertEquals(if (includesMiss) 3 else 2, zones.counts.retainedImpactCount)
                assertEquals(2, zones.zones.sumOf { it.frequency.count })
                assertEquals(listOfNotNull(miss.uuid.takeIf { includesMiss }), zones.outsideImpacts.map { it.impactUuid })
            }
            assertEquals(decoded, store.readArchiveV8())
        }
        assertEquals(rows, f.contents(restored, v8Tables))
        assertRecipeIntegrity(restored)
    }

    @Test fun lateV8InsertFailureRollsBackAllRecords() = runBlocking<Unit> {
        val source = ConfiguredWriteData.rich()
        val archive = openSessionStore(directory.resolve("rollback-source.db")).use { store ->
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(source))
            store.weather.record(WeatherObservation(f.id(8310), source.uuid, WeatherScope(), f.now, f.now,
                temperatureC = 12.0))
            store.readArchiveV8()
        }
        val copy = ArchiveV8JsonCodec().decode(ArchiveV8JsonCodec().encode(archive))
        val target = directory.resolve("rollback-target.db")
        openSessionStore(target).use { store ->
            val before = store.readArchiveV8()
            f.rows(target, """
                CREATE TRIGGER refuse_weather BEFORE INSERT ON weather_observations
                WHEN EXISTS (SELECT 1 FROM sessions WHERE uuid = NEW.sessionUuid)
                BEGIN SELECT RAISE(ABORT, 'synthetic late refusal'); END
            """.trimIndent())
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV8(copy)).preview
            assertIs<ArchiveV4ImportResult.CommitUnconfirmed>(store.importArchiveV4(preview))
            assertEquals(before, store.readArchiveV8())
            assertTrue(f.contents(target, v8Tables).values.all { it.isEmpty() })
        }
        assertRecipeIntegrity(target)
    }
}
