package com.lmarchery.core.export

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.scoring.configured.EndConfigurationOrigin
import com.lmarchery.core.scoring.configured.summarizeConfiguredSession
import java.time.Instant
import kotlin.test.*

/** Versioned transfer stays distinct from the historical V1 configured-session adapter. */
class ConfiguredArchiveCoexistenceTest {
    private val codec = ArchiveJsonCodec()
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private fun golden(): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/v1-rich-golden.json")).use { it.readBytes() }
    private fun legacy(): VersionedArchiveJsonRead.V1 =
        assertIs<VersionedArchiveJsonRead.V1>(codec.decodeVersioned(golden()))

    private fun fullV2(): DomainArchiveV2 {
        val v1 = legacy().archive
        val session = v1.sessions.single()
        val bow = Bow(requireNotNull(session.bowUuid), "Matching bow", "custom",
            35.5f, "", null, "synthetic", now, now.plusNanos(1))
        val arrows = ArrowSet(requireNotNull(session.arrowSetUuid), "Matching arrows",
            "500", "28 in", "100 gr", null, "", "carbon", "synthetic", now, now.plusNanos(2))
        val snapshot = snapshotArchiveV2(v1.profiles, v1.sessions, listOf(bow), listOf(arrows))
        return assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshot)).archive
    }

    @Test fun `validated V1 golden converts to configured domain and remains byte identical`() {
        val bytes = golden()
        val archive = legacy().archive
        val session = archive.sessions.single()
        val configured = ConfiguredSession.fromLegacyArchive(archive, session.uuid)
        assertEquals(session.uuid, configured.uuid)
        assertEquals(session.ends, configured.ends.map { it.end })
        assertEquals(archive.profiles.single().regulatoryEdition, configured.initialConfiguration.regulatoryEdition)
        assertTrue(configured.ends.all { it.origin == EndConfigurationOrigin.LegacyDerived })
        assertEquals(session.bowUuid, configured.metadata.bowUuid)
        assertEquals(session.arrowSetUuid, configured.metadata.arrowSetUuid)
        assertEquals(session.ends.sumOf { end -> end.impacts.sumOf { it.scoreFinal } }.toLong(),
            summarizeConfiguredSession(configured).totals.recorded.score)
        assertContentEquals(bytes, codec.encode(archive))
    }

    @Test fun `V2 equipment and opaque references survive without projection into V1`() {
        val source = fullV2()
        val originalBytes = codec.encode(source)
        val result = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(originalBytes))
        assertEquals(source, result.archive)
        assertEquals(source.bows, result.archive.bows)
        assertEquals(source.arrowSets, result.archive.arrowSets)
        assertEquals(source.sessions.single().bowUuid, result.archive.sessions.single().bowUuid)
        assertEquals(source.sessions.single().arrowSetUuid, result.archive.sessions.single().arrowSetUuid)
        assertContentEquals(originalBytes, codec.encode(result.archive))
        assertIs<ArchiveJsonRead.Rejected>(codec.decode(originalBytes))
        assertContentEquals(golden(), codec.encode(legacy().archive))
    }

    @Test fun `V2 missing catalog references remain opaque and no empty catalog is invented by V1`() {
        val v1 = legacy().archive
        val v2 = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(
            snapshotArchiveV2(v1.profiles, v1.sessions, emptyList(), emptyList()))).archive
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(v2))).archive
        assertTrue(restored.bows.isEmpty())
        assertTrue(restored.arrowSets.isEmpty())
        assertEquals(v1.sessions.single().bowUuid, restored.sessions.single().bowUuid)
        assertEquals(v1.sessions.single().arrowSetUuid, restored.sessions.single().arrowSetUuid)
    }

    @Test fun `V2 contradictory complete published profile rejects the entire archive`() {
        val snapshot = fullV2().toSnapshot()
        val profile = snapshot.profiles!!.single()
        val malformed = snapshot.copy(profiles = listOf(
            profile.copy(definition = profile.definition!!.copy(name = "Tampered published profile"))))
        val result = assertIs<ArchiveV2Reconstruction.NeedsIntervention>(reconstructArchiveV2(malformed))
        assertEquals(ArchiveIssueCode.PROFILE_IDENTITY_CONFLICT, result.issue.code)
        assertEquals("profiles[0]", result.issue.path)
    }
}
