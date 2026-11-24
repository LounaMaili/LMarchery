package com.lmarchery.core.export

import com.google.gson.JsonParser
import com.lmarchery.core.export.V3CodecFixtures as f
import com.lmarchery.core.scoring.archive.v4.*
import com.lmarchery.core.scoring.configured.configuredSessionScoringContexts
import com.lmarchery.core.scoring.grouping.calculateTargetZoneDistribution
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ArchiveV4JsonCodecTest {
    private val codec = ArchiveV4JsonCodec()
    private fun archive(): DomainArchiveV4 {
        val s = f.mixed().cancelImpact(f.id(12), f.id(110), f.now.plusSeconds(30).plusNanos(7), "  🏹\n\"erreur\" ")
        return assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(snapshotArchiveV4(f.profiles(), listOf(s), listOf(f.bow()), listOf(f.arrows())))).archive
    }
    @Test fun `complete mixed round trip preserves exact Float bits UTC nanoseconds decisions catalog and cancellation`() {
        val a = archive(); val bytes = codec.encode(a)
        val read = assertIs<ArchiveV4JsonRead.Accepted>(codec.decode(bytes))
        assertEquals(a, read.archive); assertEquals(a.toSnapshot(), read.snapshot)
        assertContentEquals(bytes, codec.encode(read.archive))
        val old = a.sessions.single().ends.flatMap { it.end.impacts }
        val restored = read.archive.sessions.single().ends.flatMap { it.end.impacts }
        old.zip(restored).forEach { (x, y) -> assertEquals(x.point.x.toRawBits(), y.point.x.toRawBits()); assertEquals(x.point.y.toRawBits(), y.point.y.toRawBits()) }
        assertEquals(f.now.plusSeconds(30).plusNanos(7).toString(), read.snapshot.cancellations!!.single().cancelledAt)
    }
    @Test fun `zone analysis preserves the decoded archive and its bytes on disk`(@TempDir directory: Path) {
        val bytes = codec.encode(archive())
        val path = Files.write(directory.resolve("synthetic-v4.json"), bytes)
        val decoded = assertIs<ArchiveV4JsonRead.Accepted>(codec.decode(Files.readAllBytes(path))).archive
        val contexts = configuredSessionScoringContexts(decoded.sessions.single())

        val recurve = calculateTargetZoneDistribution(listOf(contexts.first()))
        assertEquals(3, recurve.counts.retainedImpactCount)
        assertEquals(1, recurve.zones.single { it.zone.score == 8 }.frequency.count)
        assertEquals(9, contexts.first().end.impacts.single { it.uuid == f.id(101) }.scoreFinal)
        val compound = calculateTargetZoneDistribution(listOf(contexts.single { it.end.uuid == f.id(12) }))
        assertEquals(1, compound.counts.cancelledImpactCount)
        assertEquals(0, compound.zones.sumOf { it.frequency.count })

        assertContentEquals(bytes, codec.encode(decoded))
        assertContentEquals(bytes, Files.readAllBytes(path))
    }

    @Test fun `empty V4 has an explicit evidence list and deterministic independent wire representation`() {
        val empty = assertIs<ArchiveV4Reconstruction.Accepted>(reconstructArchiveV4(ArchiveV4Snapshot(4, 1, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))).archive
        val expected = """
            {
              "schemaVersion": 4,
              "scoringContractVersion": 1,
              "profiles": [],
              "sessions": [],
              "bows": [],
              "arrowSets": [],
              "cancellations": []
            }
        """.trimIndent().toByteArray()
        assertContentEquals(expected, codec.encode(empty)); assertEquals(empty, assertIs<ArchiveV4JsonRead.Accepted>(codec.decode(expected)).archive)
    }
    @Test fun `missing unexpected duplicate fields and malformed evidence fail without accepted partial content`() {
        val bytes = codec.encode(archive()); val json = bytes.toString(Charsets.UTF_8)
        val missing = JsonParser.parseString(json).asJsonObject.also { it.remove("cancellations") }.toString()
        val extra = JsonParser.parseString(json).asJsonObject.also { it.addProperty("ignored", true) }.toString()
        val duplicate = json.replaceFirst("\"cancellations\":", "\"cancellations\": [], \"cancellations\":")
        val badOwner = json.replace(f.id(110), "not-a-uuid")
        for (bad in listOf(missing, extra, duplicate, badOwner, json.dropLast(1)))
            assertIs<ArchiveV4JsonRead.Rejected>(codec.decode(bad.toByteArray()))
    }
    @Test fun `old codecs reject V4 and dispatch cannot silently downgrade`() {
        val bytes = codec.encode(archive())
        assertIs<VersionedArchiveJsonRead.Rejected>(ArchiveJsonCodec().decodeVersioned(bytes))
        assertIs<ArchiveV3JsonRead.Rejected>(ArchiveV3JsonCodec().decode(bytes))
        assertEquals(archive(), assertIs<ArchiveDocumentRead.V4>(ArchiveDocumentDecoder().decode(bytes)).archive)
    }
    @Test fun `all codec budgets apply to evidence and output as well as input`() {
        val a = archive(); val bytes = codec.encode(a)
        assertIs<ArchiveV4JsonRead.Rejected>(ArchiveV4JsonCodec(JsonLimits(maxBytes = bytes.size - 1)).decode(bytes))
        assertFailsWith<ArchiveJsonException> { ArchiveV4JsonCodec(JsonLimits(maxBytes = bytes.size - 1)).encode(a) }
        val p = a.toSnapshot().cancellations!!.single()
        assertIs<ArchiveV4Reconstruction.NeedsIntervention>(reconstructArchiveV4(a.toSnapshot().copy(cancellations = listOf(p.copy(comment = "x".repeat(16385))))))
    }
}
