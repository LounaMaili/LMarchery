package com.lmarchery.core.database

import com.lmarchery.core.database.EquipmentTestData as f
import com.lmarchery.core.export.*
import com.lmarchery.core.model.place.*
import com.lmarchery.core.scoring.archive.v7.DomainArchiveV7
import com.lmarchery.feature.archiveimport.ImportState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class CompassArchivesTest {
    @TempDir lateinit var dir: Path
    private val site = ShootingSite(f.id(8100), "Site synthétique")
    private val at = Instant.parse("2026-01-14T10:00:00Z")
    private val reading = CompassReading(359.75, at, CompassMethod.ROTATION_VECTOR, CompassQuality.MEDIUM)
    private val confirmed = LineOrientation(0.5, NorthReference.MAGNETIC_NORTH, OrientationOrigin.SENSOR_CONFIRMED,
        null, at.plusSeconds(3), reading)
    private val line = ShootingLine(f.id(8101), site.uuid, "Ligne", orientation = confirmed)

    @Test fun correctedSessionAndFavouriteHaveIndependentEvidenceAcrossReopenAndDocumentRestore() = runBlocking<Unit> {
        val path = dir.resolve("source.db")
        lateinit var bytes: ByteArray
        lateinit var expected: DomainArchiveV7
        val next = reading.copy(magneticDegrees = 2.75, method = CompassMethod.GEOMAGNETIC_ROTATION_VECTOR)
        AppIntegrationHarness(path).use { h ->
            h.store.places.save(null, site)
            h.store.places.save(null, line)
            val before = h.store.readArchiveV8()
            h.refresh()
            suspend fun openForm() {
                h.onUi { h.configured.create(); h.configured.loadPlaces(h.token) }
                h.idle()
                h.change { h.configured.choosePlace(h.token, site, line) }
            }
            openForm()
            val stale = h.token
            h.change { h.configured.changeOrientation(h.token, line, 4.5, NorthReference.MAGNETIC_NORTH, null, next) }
            h.change { h.configured.cancelForm(h.token) }
            h.closeEditor()
            assertEquals(before, h.store.readArchiveV8())
            openForm()
            h.change { h.configured.changeOrientation(stale, line, 50.0, NorthReference.MAGNETIC_NORTH, null, next) }
            assertEquals(before, h.store.readArchiveV8())
            h.change { h.configured.changeOrientation(h.token, line, 3.25, NorthReference.MAGNETIC_NORTH, null, next) }
            h.change { h.configured.createAndStart(h.token) }
            val snapshot = assertNotNull(h.state.savedPlace)
            val effective = assertNotNull(snapshot.orientation)
            assertEquals(3.25, effective.degrees)
            assertEquals(next, effective.reading)
            assertEquals(OrientationOrigin.SENSOR_CONFIRMED, effective.origin)
            assertNull(effective.uncertaintyDegrees)
            assertNotNull(effective.confirmedAt)
            assertEquals(confirmed, snapshot.line?.orientation)
            h.closeEditor()
            h.store.places.save(line, line.copy(orientation = null))
            expected = h.store.readArchiveV8()
            assertEquals(snapshot, expected.base.base.places.snapshots.single())
            assertNull(expected.base.base.places.lines.single().orientation)
            assertFailsWith<CorruptStorage> { h.store.readArchiveV5() }
            assertFailsWith<CorruptStorage> { h.store.readArchiveV6() }
            assertFailsWith<CorruptStorage> { h.store.readArchiveV7() }
            assertFails { ArchiveV7JsonCodec().encode(expected) }
            bytes = h.export(8, "survey.json")
            assertEquals(expected, assertIs<ArchiveDocumentRead.V8>(ArchiveDocumentDecoder().decode(bytes)).archive)
            val altered = bytes.decodeToString().replace("SENSOR_CONFIRMED", "MANUAL")
            assertNotEquals(bytes.decodeToString(), altered)
            assertIs<ArchiveDocumentRead.Rejected>(ArchiveDocumentDecoder().decode(altered.encodeToByteArray()))
        }
        openSessionStore(path).use { assertEquals(expected, it.readArchiveV8()) }
        val target = dir.resolve("restored.db")
        AppIntegrationHarness(target).use { h ->
            h.refresh()
            assertEquals(8, assertIs<ImportState.PreviewV4>(h.preview("survey.json", bytes)).version)
            assertEquals(8, assertIs<ImportState.SucceededV4>(h.confirm()).version)
            h.preview("again.json", bytes)
            assertTrue(assertIs<ImportState.SucceededV4>(h.confirm()).alreadyPresent)
            val conflict = bytes.decodeToString().replace("359.75","359.5").encodeToByteArray()
            assertFalse(bytes.contentEquals(conflict))
            assertIs<ImportState.Failed>(h.preview("conflict.json", conflict))
            assertEquals(expected, h.store.readArchiveV8())
        }
        openSessionStore(target).use { assertEquals(expected, it.readArchiveV8()) }
        assertRecipeIntegrity(target)
    }

    @Test fun genuineV7MigrationKeepsManualNorthAndDoesNotInventAReading() = runBlocking<Unit> {
        val path = dir.resolve("v7.db")
        ArchiveV3DiskData.historical(path, 7)
        ArchiveV3DiskData.raw(path,
            "INSERT INTO entity_identities VALUES('${site.uuid}','SITE'),('${line.uuid}','LINE')",
            "INSERT INTO shooting_sites(uuid,name) VALUES('${site.uuid}','Site synthétique')",
            "INSERT INTO shooting_lines(uuid,siteUuid,name,active,bearing_degreesBits,bearing_north,bearing_origin) " +
                "VALUES('${line.uuid}','${site.uuid}','Ligne',1,${19.75.toBits()},'MAGNETIC_NORTH','MANUAL')")
        openSessionStore(path).use { store ->
            val manual = LineOrientation.manual(19.75, NorthReference.MAGNETIC_NORTH, null, null)
            val v7 = store.readArchiveV7()
            assertEquals(manual, v7.base.base.places.lines.single().orientation)
            val original = ArchiveV7JsonCodec().encode(v7)
            assertEquals(v7, assertIs<ArchiveDocumentRead.V7>(ArchiveDocumentDecoder().decode(original)).archive)
            val illegal = original.decodeToString().replace("\"origin\": \"MANUAL\"", "\"origin\": \"SENSOR_CONFIRMED\"")
            assertNotEquals(original.decodeToString(), illegal)
            assertIs<ArchiveDocumentRead.Rejected>(ArchiveDocumentDecoder().decode(illegal.encodeToByteArray()))
            assertEquals(v7, store.readArchiveV8())
        }
        assertRecipeIntegrity(path)
    }

    @Test fun correctedMagneticObservationCannotBeRelabelledAsTrueNorthOrGivenFictitiousAccuracy() {
        assertFails { confirmed.copy(reference = NorthReference.TRUE_NORTH) }
        assertFails { confirmed.copy(uncertaintyDegrees = 0.0) }
        assertFails { confirmed.copy(confirmedAt = at.minusSeconds(1)) }
        assertFails { confirmed.copy(origin = OrientationOrigin.MANUAL) }
        assertFails { confirmed.copy(reading = null) }
    }
}
