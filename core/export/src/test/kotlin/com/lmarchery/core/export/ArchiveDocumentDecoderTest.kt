package com.lmarchery.core.export

import kotlin.test.*

class ArchiveDocumentDecoderTest {
    private val f = V3CodecFixtures
    private val decoder = ArchiveDocumentDecoder()
    @Test fun `historical golden V1 and V2 use unchanged codecs and bytes`() {
        val old = ArchiveJsonCodec()
        val one = f.golden("v1-rich-golden.json")
        val v1 = assertIs<ArchiveDocumentRead.V1>(decoder.decode(one))
        assertContentEquals(one, old.encode(v1.archive))
        val two = old.encode(f.v2())
        val v2 = assertIs<ArchiveDocumentRead.V2>(decoder.decode(two))
        assertContentEquals(two, old.encode(v2.archive))
    }
    @Test fun `V3 rich golden is fully reconstructed without historical projection`() {
        val bytes = f.golden("v3-rich-golden.json")
        val read = assertIs<ArchiveDocumentRead.V3>(decoder.decode(bytes))
        assertEquals(f.rich(), read.archive); assertContentEquals(bytes, ArchiveV3JsonCodec().encode(read.archive))
        assertIs<VersionedArchiveJsonRead.Rejected>(ArchiveJsonCodec().decodeVersioned(bytes))
    }
    @Test fun `V3 invalid provenance retains typed original domain diagnostic`() {
        val source = f.golden("v3-rich-golden.json").toString(Charsets.UTF_8)
        val bad = source.replaceFirst("\"sessionUuid\":", "\"wrongOwner\":").toByteArray()
        val result = assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(bad))
        assertNotNull(result.v3Issue); assertTrue(result.path.contains("sessions")); assertNull(result.historicalIssue)
    }
    @Test fun `version is strict explicit and unique without regex sniffing or fallback`() {
        listOf("{}", "{\"schemaVersion\":4}", "{\"schemaVersion\":\"3\"}",
            "{\"schemaVersion\":3,\"schemaVersion\":1}", "{\"schemaVersion\":3} trailing",
            "{\"schemaVersion\":3.0}").forEach { bytes -> assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(bytes.toByteArray())) }
    }
    @Test fun `V3 bad scoring version is not retried under V1 or V2`() {
        val bytes = f.golden("v3-rich-golden.json").toString(Charsets.UTF_8).replace("\"scoringContractVersion\": 1", "\"scoringContractVersion\": 99").toByteArray()
        val result = assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(bytes)); assertNotNull(result.v3Issue)
    }
    @Test fun `all versions reject malformed UTF8 over budget and excessive nesting`() {
        listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(JsonLimits().maxBytes + 1),
            ("[".repeat(100) + "0" + "]".repeat(100)).toByteArray()).forEach { assertIs<ArchiveDocumentRead.Rejected>(decoder.decode(it)) }
    }
    @Test fun `tightened historical and V3 limits still apply after dispatch`() {
        val restricted = ArchiveDocumentDecoder(ArchiveJsonCodec(JsonLimits(maxBytes = 30)), ArchiveV3JsonCodec(JsonLimits(maxBytes = 30)))
        val sources = listOf(f.golden("v1-rich-golden.json"), ArchiveJsonCodec().encode(f.v2()), f.golden("v3-rich-golden.json"))
        sources.forEach { assertEquals("LIMIT", assertIs<ArchiveDocumentRead.Rejected>(restricted.decode(it)).code) }
    }
}
