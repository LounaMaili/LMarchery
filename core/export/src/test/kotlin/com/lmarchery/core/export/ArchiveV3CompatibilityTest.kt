package com.lmarchery.core.export

import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.ConfiguredSession
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.time.Instant
import kotlin.test.*

/** V3 has no JSON implementation. These are regression tests of the unchanged published codecs. */
class ArchiveV3CompatibilityTest {
    private val codec = ArchiveJsonCodec()
    private fun golden(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private fun legacy() = assertIs<ArchiveJsonRead.Accepted>(codec.decode(golden("v1-rich-golden.json"))).archive
    private fun v2(): DomainArchiveV2 {
        val source = legacy(); val session = source.sessions.single(); val at = Instant.parse("2026-10-25T00:30:00.123456789Z")
        val bow = Bow(session.bowUuid!!, " Synthetic 🏹 ", drawWeightLbs = Float.MIN_VALUE, createdAt = at, updatedAt = at.plusNanos(1))
        val arrows = ArrowSet(session.arrowSetUuid!!, "Arrows", "500", "28 in", "100 gr", "", " ", null, "", at, at)
        val unreferenced = BuiltInTargetRegistry.all.first { entry -> source.profiles.none { it.definition == entry.definition } }
        return assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(
            source.profiles + TargetProfileSnapshot(unreferenced.definition, unreferenced.regulatoryEdition),
            source.sessions, listOf(bow), listOf(arrows)))).archive
    }
    @Test fun `both V1 golden byte sequences remain identical after partial configured conversion`() {
        for (name in listOf("v1-empty-golden.json", "v1-rich-golden.json")) {
            val bytes = golden(name); val source = assertIs<VersionedArchiveJsonRead.V1>(codec.decodeVersioned(bytes)).archive
            val partial = convertArchiveV1ToConfiguredContent(source)
            assertEquals(EquipmentAvailability.NOT_PROVIDED, partial.equipmentAvailability)
            assertEquals(source.profiles, partial.profiles)
            assertContentEquals(bytes, codec.encode(source))
        }
    }
    @Test fun `V2 bytes catalog and unused profiles are unchanged after V3 business round trip`() {
        val source = v2(); val bytes = codec.encode(source)
        val restoredV2 = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes)).archive
        val v3 = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(restoredV2)).archive
        val restoredV3 = assertIs<ArchiveV3Reconstruction.Accepted>(reconstructArchiveV3(v3.toSnapshot())).archive
        assertEquals(v3, restoredV3); assertEquals(source.bows, restoredV3.bows); assertEquals(source.arrowSets, restoredV3.arrowSets)
        assertEquals(source.profiles, restoredV3.profiles); assertEquals(source.sessions.single().ends, restoredV3.sessions.single().ends.map { it.end })
        assertContentEquals(bytes, codec.encode(source)); assertContentEquals(bytes, codec.encode(restoredV2))
    }
    @Test fun `existing encode overloads cannot accept V3 configured session or partial V1 content`() {
        val methods = ArchiveJsonCodec::class.java.methods.filter { it.name == "encode" && it.parameterCount == 1 }
        assertEquals(setOf(DomainArchive::class.java, DomainArchiveV2::class.java), methods.map { it.parameterTypes.single() }.toSet())
        for (type in listOf(DomainArchiveV3::class.java, ConfiguredSession::class.java, LegacyV1ConfiguredContent::class.java))
            assertTrue(methods.none { it.parameterTypes.single().isAssignableFrom(type) })
    }
    @Test fun `old versioned reader rejects schema three instead of downgrading or partly restoring`() {
        val result = assertIs<VersionedArchiveJsonRead.Rejected>(codec.decodeVersioned("""{"schemaVersion":3}""".toByteArray()))
        assertEquals("UNSUPPORTED_VERSION", result.issue.code)
        assertEquals("$.schemaVersion", result.issue.path)
    }
}
