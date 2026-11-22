package com.lmarchery.core.database

import com.lmarchery.core.database.ConfiguredWriteData as sessions
import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.scoring.archive.v5.DomainArchiveV5
import com.lmarchery.feature.archiveimport.ImportState
import com.lmarchery.feature.export.ExportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ShootingPlacesTest {
    @TempDir lateinit var directory: Path
    private val file get() = directory.resolve("source.db")
    private val site = ShootingSite(f.id(7100), "Terrain synthétique", "Deux lignes",
        GeoPosition(0.0, -0.0, ManualOrigin.MANUAL, 5.5))
    private val north = LineOrientation.manual(360.0, NorthReference.TRUE_NORTH, null, f.now)
    private val line = ShootingLine(f.id(7101), site.uuid, "A", orientation = north)
    private val south = line.copy(uuid = f.id(7102), name = "B", orientation = north.copy(degrees = 180.0))
    private val tables = ArchiveV3DiskData.tables + listOf("impact_cancellations", "shooting_sites", "shooting_lines", "session_places")

    @Test fun favouriteCatalogueOwnsGateAndNeverCreatesASession() = runBlocking<Unit> {
        AppIntegrationHarness(file).use { h ->
            h.refresh()
            h.change { h.configured.openPlaceCatalogue() }
            val token = assertIs<com.lmarchery.feature.configured.ConfiguredForm.PlaceCatalogue>(h.state.form).token
            assertTrue(h.legacy.state.value.archiveOperationLocked)
            h.change { h.configured.saveSite(token, null, site) }
            h.change { h.configured.saveLine(token, null, line) }
            h.change { h.configured.confirmForm(token) }
            assertEquals(listOf(site), h.state.catalogue!!.first)
            assertTrue(h.store.readArchiveV8().base.base.sporting.sessions.isEmpty())
            h.change { h.configured.closePlaceCatalogue(token) }
            h.ready()
            assertFalse(h.legacy.state.value.archiveOperationLocked)
            h.change { h.configured.openPlaceCatalogue() }
            h.change { h.configured.saveLine(token, line, line.copy(active = false)) }
            assertEquals(line, h.state.catalogue!!.second.single())
            val current = h.state.form!!.token
            h.change { h.configured.saveLine(current, line, line.copy(active = false)) }
            assertFalse(h.state.catalogue!!.second.single().active)
        }
    }

    @Test fun manualAnglesAndMissingValues() {
        assertEquals(0.0, north.degrees)
        assertEquals(359.75, LineOrientation.manual(-0.25, NorthReference.MAGNETIC_NORTH, 0.5, null).degrees)
        assertNull(line.copy(orientation = null).orientation)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFailsWith<IllegalArgumentException> { LineOrientation.manual(it, NorthReference.TRUE_NORTH, null, null) }
        }
        assertFailsWith<IllegalArgumentException> { north.copy(degrees = 360.0) }
        assertFailsWith<IllegalArgumentException> { north.copy(uncertaintyDegrees = -1.0) }
        assertFailsWith<IllegalArgumentException> { GeoPosition(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPosition(0.0, 180.0) }
    }

    @Test fun configuredCreationCapturesOneExactSelectionAndLeavesHistoryIndependent() = runBlocking<Unit> {
        val changed = north.copy(degrees = 34.25, reference = NorthReference.MAGNETIC_NORTH)
        val old = sessions.rich()
        lateinit var captured: SessionPlace
        AppIntegrationHarness(file).use { h ->
            h.store.createConfiguredSession(old)
            h.store.places.save(null, site)
            h.store.places.save(null, line)
            h.store.places.save(null, south)
            h.refresh()
            h.onUi { h.configured.create(); h.configured.loadPlaces(h.token) }
            h.idle()
            assertEquals(listOf(0.0, 180.0), h.state.catalogue!!.second.map { it.orientation!!.degrees })
            h.change { h.configured.choosePlace(h.token, site, line) }
            h.change { h.configured.changeOrientation(h.token, line, changed.degrees, changed.reference, null) }
            h.change { h.configured.createAndStart(h.token) }
            captured = assertNotNull(h.state.savedPlace)
            val session = assertNotNull(h.state.saved)
            assertEquals(session.createdAt, captured.adoptedAt)
            assertEquals(session.uuid, captured.sessionUuid)
            assertEquals(changed.degrees, captured.orientation!!.degrees)
            assertEquals(changed.reference, captured.orientation!!.reference)
            assertEquals(north, captured.line!!.orientation)
            assertNull(session.metadata.locationName)
            h.store.places.save(site, site.copy(name = "Nouveau nom", position = null))
            h.store.places.save(line, line.copy(active = false))
            h.closeEditor()
            assertEquals(session, h.legacy.state.value.entries.single { it.uuid == session.uuid }.configured)
            val full = h.store.readArchiveV5()
            assertEquals(old, full.sporting.sessions.single { it.uuid == old.uuid })
            assertNull((h.store.readConfiguredSession(old.uuid) as ConfiguredSessionReadResult.Found).place)
            assertEquals(listOf(captured), full.places.snapshots)
            assertTrue(full.places.containsCoordinates)
        }
        openSessionStore(file).use { reopened ->
            val saved = assertIs<ConfiguredSessionReadResult.Found>(reopened.readConfiguredSession(captured.sessionUuid))
            assertEquals(captured, saved.place)
            assertFalse(reopened.places.catalogue().second.single { it.uuid == line.uuid }.active)
            assertIs<ConfiguredWriteResult.AlreadyApplied>(reopened.createConfiguredSession(saved.session, captured))
            assertIs<ConfiguredWriteResult.Conflict>(reopened.createConfiguredSession(saved.session))
        }
    }

    @Test fun cleanV4MigrationPreservesOldRowsAndNeverInfersASiteFromText() = runBlocking<Unit> {
        val old = ArchiveV3DiskData.source()
        ArchiveV3DiskData.historical(file, 4, old)
        val historical = tables - listOf("shooting_sites", "shooting_lines", "session_places")
        val before = f.contents(file, historical)
        openSessionStore(file).use { store ->
            assertEquals(ShootingPlaces.EMPTY, store.readArchiveV5().places)
            assertEquals(old, assertIs<ArchiveV2ReadResult.Found>(store.readArchiveV2()).archive)
        }
        assertEquals(before, f.contents(file, historical))
        assertRecipeIntegrity(file)
    }

    @Test fun exportPrivacyThenAtomicImportReopenAndIdempotence() = runBlocking<Unit> {
        val session = sessions.rich()
        val snapshot = SessionPlace(session.uuid, session.createdAt, site, line, north.copy(degrees = 7.25))
        lateinit var expected: DomainArchiveV5
        lateinit var bytes: ByteArray
        AppIntegrationHarness(file).use { h ->
            h.store.places.save(null, site)
            h.store.places.save(null, line)
            h.store.places.save(null, south)
            assertIs<ConfiguredWriteResult.Created>(h.store.createConfiguredSession(session, snapshot))
            expected = h.store.readArchiveV5()
            assertIs<ArchiveReadResult.NeedsIntervention>(h.store.readArchive())
            assertIs<ArchiveV2ReadResult.NeedsIntervention>(h.store.readArchiveV2())
            assertIs<ArchiveV3ReadResult.NeedsIntervention>(h.store.readArchiveV3())
            assertIs<ArchiveV4ReadResult.NeedsIntervention>(h.store.readArchiveV4())
            h.refresh()
            suspend fun prompt(): ExportState.ShareCoordinates {
                h.onUi { h.exporter.beginV5() }
                return assertIs(h.awaitUiState(h.exporter.state) { it is ExportState.ShareCoordinates || it is ExportState.Failed })
            }
            val first = prompt().destination.requestId
            h.onUi { assertNull(h.exporter.claimDestinationRequest()); h.exporter.shareCoordinates(first, false) }
            assertIs<ExportState.Cancelled>(h.exporter.state.value)
            val ticket = prompt().destination.requestId
            h.onUi {
                h.exporter.shareCoordinates(first, true)
                assertNull(h.exporter.claimDestinationRequest())
                h.exporter.shareCoordinates(ticket, true)
                assertNotNull(h.exporter.claimDestinationRequest())
                h.exporter.destinationChosen(ticket, h.documents.uri("places.json"))
            }
            assertIs<ExportState.Succeeded>(h.awaitUiState(h.exporter.state) { it is ExportState.Succeeded || it is ExportState.Failed })
            bytes = h.documents.bytes("places.json")
        }
        assertEquals(expected, assertIs<ArchiveDocumentRead.V5>(ArchiveDocumentDecoder().decode(bytes)).archive)
        val target = directory.resolve("restored.db")
        AppIntegrationHarness(target).use { h ->
            h.refresh()
            val preview = assertIs<ImportState.PreviewV4>(h.preview("restore.json", bytes))
            assertEquals(4, preview.newPlaceRecords)
            assertTrue(preview.places!!.containsCoordinates)
            assertEquals(5, assertIs<ImportState.SucceededV4>(h.confirm()).version)
            assertEquals(expected, h.store.readArchiveV5())
            f.denyWrites(target, tables)
            h.preview("again.json", bytes)
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent)
        }
        openSessionStore(target).use { assertEquals(expected, it.readArchiveV5()) }
        assertRecipeIntegrity(target)
    }

    @Test fun staleSelectionAndLateInsertCannotLeaveSessionWithoutPlace() = runBlocking<Unit> {
        val session = sessions.oneEnd()
        val snapshot = SessionPlace(session.uuid, session.createdAt, site, line, null)
        openSessionStore(file).use { store ->
            store.places.save(null, site)
            store.places.save(null, line)
            store.places.save(line, line.copy(active = false))
            assertIs<ConfiguredWriteResult.Conflict>(store.createConfiguredSession(session, snapshot))
            assertIs<ConfiguredSessionReadResult.NotFound>(store.readConfiguredSession(session.uuid))
            val siteOnly = snapshot.copy(line = null)
            f.rows(file, "CREATE TRIGGER refuse_capture BEFORE INSERT ON session_places BEGIN SELECT RAISE(ABORT,'test'); END")
            val failed = assertIs<ConfiguredWriteResult.CommitUnconfirmed>(store.createConfiguredSession(session, siteOnly))
            assertEquals(siteOnly, (failed.request as ConfiguredWriteRequest.Create).place)
            assertIs<ConfiguredSessionReadResult.NotFound>(store.readConfiguredSession(session.uuid))
        }
    }

    @Test fun malformedPositionCannotBecomeAnInventedZeroAndImportFailureRollsBackTheWholeFile() = runBlocking<Unit> {
        val session = sessions.rich()
        val capture = SessionPlace(session.uuid, session.createdAt, site, null, null)
        val full = openSessionStore(file).use { store ->
            store.places.save(null, site)
            assertIs<ConfiguredWriteResult.Created>(store.createConfiguredSession(session, capture))
            store.readArchiveV5()
        }
        val target = directory.resolve("late-failure.db")
        openSessionStore(target).use { store ->
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV5(full)).preview
            val before = f.contents(target, tables)
            f.rows(target, "CREATE TRIGGER late_failure BEFORE INSERT ON session_places BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertIs<ArchiveV4ImportResult.CommitUnconfirmed>(store.importArchiveV4(preview))
            assertEquals(before, f.contents(target, tables))
        }
        f.rows(file, "UPDATE shooting_sites SET geo_latitudeBits=NULL")
        openSessionStore(file).use { store -> assertFailsWith<CorruptStorage> { store.readArchiveV5() } }
    }

    @Test fun strictV5AndStalePreviewRejectContradictionsWithoutChangingRows() = runBlocking<Unit> {
        openSessionStore(file).use { store ->
            store.places.save(null, site)
            val first = store.readArchiveV5()
            val preview = assertIs<ArchiveV4PreviewResult.Ready>(store.previewImportV5(first)).preview
            store.places.save(site, site.copy(name = "Révision"))
            val before = f.contents(file, tables)
            assertIs<ArchiveV4ImportResult.Conflict>(store.importArchiveV4(preview))
            assertIs<ArchiveV4PreviewResult.NeedsIntervention>(store.previewImportV5(first))
            assertEquals(before, f.contents(file, tables))
            val text = ArchiveV5JsonCodec().encode(first).decodeToString()
            listOf(text.replaceFirst("\"latitude\"", "\"unknown\""),
                text.replaceFirst("\"latitude\": 0.0", "\"latitude\": 95"),
                text.replaceFirst("\"accuracyMetres\"", "\"latitude\"")).forEach { invalid ->
                assertNotEquals(text, invalid)
                assertIs<ArchiveDocumentRead.Rejected>(ArchiveDocumentDecoder().decode(invalid.encodeToByteArray()))
            }
        }
    }
}
