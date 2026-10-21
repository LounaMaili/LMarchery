package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.scoring.archive.v3.*
import kotlin.test.*

class ArchiveV3JsonLimitsTest {
    private val f = V3CodecFixtures
    private fun empty() = f.accepted(ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), emptyList(), emptyList()))
    private fun tree(source: DomainArchiveV3 = empty()) = JsonParser.parseString(ArchiveV3JsonCodec().encode(source).toString(Charsets.UTF_8)).asJsonObject
    private fun readReject(codec: ArchiveV3JsonCodec, bytes: ByteArray, code: String? = "LIMIT") =
        assertIs<ArchiveV3JsonRead.Rejected>(codec.decode(bytes)).issue.also { code?.let { c -> assertEquals(c, it.code) } }
    private fun writeReject(codec: ArchiveV3JsonCodec, source: DomainArchiveV3, code: String? = "LIMIT") =
        assertFailsWith<ArchiveV3JsonException> { codec.encode(source) }.issue.also { code?.let { c -> assertEquals(c, it.code) } }
    private fun sourceWithNote(note: String): DomainArchiveV3 {
        val s = empty().toSnapshot().copy(bows = listOf(f.bow().let {
            com.lmarchery.core.scoring.archive.BowSnapshot(it.uuid, it.name, it.type, it.drawWeightLbs,
                it.brand, it.model, note, it.createdAt.toString(), it.updatedAt.toString()) }))
        return f.accepted(s)
    }
    @Test fun `file byte limit is inclusive on reading and writing including multibyte Unicode`() {
        val source = sourceWithNote("🏹é東京")
        val bytes = ArchiveV3JsonCodec().encode(source)
        val exact = ArchiveV3JsonCodec(JsonLimits(maxBytes = bytes.size))
        assertIs<ArchiveV3JsonRead.Accepted>(exact.decode(bytes)); assertContentEquals(bytes, exact.encode(source))
        val small = ArchiveV3JsonCodec(JsonLimits(maxBytes = bytes.size - 1))
        readReject(small, bytes); writeReject(small, source)
        readReject(ArchiveV3JsonCodec(), ByteArray(16 * 1024 * 1024 + 1) { 32 })
    }
    @Test fun `default byte ceiling also bounds output before returning any bytes`() {
        val bow = sourceWithNote("a".repeat(16_384)).toSnapshot().bows!!.single()
        val source = f.accepted(empty().toSnapshot().copy(bows = (0 until 1050).map { bow.copy(uuid = f.id(1000 + it)) }))
        writeReject(ArchiveV3JsonCodec(), source)
    }
    @Test fun `value budget is inclusive and counts the whole tree on input and output`() {
        val source = sourceWithNote("x"); val bytes = ArchiveV3JsonCodec().encode(source)
        fun count(n: JsonElement): Int = 1 + when {
            n.isJsonObject -> n.asJsonObject.entrySet().sumOf { count(it.value) }
            n.isJsonArray -> n.asJsonArray.sumOf(::count)
            else -> 0
        }
        val size = count(tree(source)); val exact = ArchiveV3JsonCodec(JsonLimits(maxValues = size))
        assertIs<ArchiveV3JsonRead.Accepted>(exact.decode(bytes)); assertContentEquals(bytes, exact.encode(source))
        val small = ArchiveV3JsonCodec(JsonLimits(maxValues = size - 1))
        readReject(small, bytes); writeReject(small, source)
    }
    @Test fun `default total JSON value ceiling is enforced before shape validation`() {
        val array = "[" + List(20_000) { "null" }.joinToString(",") + "]"
        val text = "{\"schemaVersion\":3,\"extra\":[" + List(10) { array }.joinToString(",") + "]}"
        readReject(ArchiveV3JsonCodec(), text.toByteArray())
    }
    @Test fun `default value ceiling also rejects otherwise valid large output`() {
        val arrows = f.accepted(f.snapshot()).toSnapshot().arrowSets!!.single()
        val source = f.accepted(empty().toSnapshot().copy(arrowSets = (0 until 17_000).map { arrows.copy(uuid = f.id(1000 + it)) }))
        writeReject(ArchiveV3JsonCodec(), source)
    }
    @Test fun `depth is bounded on input and generated output`() {
        val nested = "[".repeat(33) + "0" + "]".repeat(33)
        readReject(ArchiveV3JsonCodec(), nested.toByteArray(), null)
        val source = f.rich(); val bytes = ArchiveV3JsonCodec().encode(source)
        val shallow = ArchiveV3JsonCodec(JsonLimits(maxDepth = 3))
        readReject(shallow, bytes, null); writeReject(shallow, source, null)
    }
    @Test fun `per-array budgets apply globally to root and nested arrays in both directions`() {
        val source = f.rich(); val bytes = ArchiveV3JsonCodec().encode(source)
        val codec = ArchiveV3JsonCodec(JsonLimits(maxArrayElements = 2))
        readReject(codec, bytes); writeReject(codec, source)
        val malformed = "{\"schemaVersion\":3,\"extra\":[" + List(20_001) { "null" }.joinToString(",") + "]}"
        readReject(ArchiveV3JsonCodec(), malformed.toByteArray())
    }
    @Test fun `object property ceiling rejects 65 keys without dropping any`() {
        val root = JsonObject(); repeat(65) { root.add("k$it", JsonNull.INSTANCE) }
        readReject(ArchiveV3JsonCodec(), root.toString().toByteArray())
        root.remove("k64")
        assertEquals("MISSING_FIELD", readReject(ArchiveV3JsonCodec(), root.toString().toByteArray(), null).code)
        // V3's fixed records have at most 13 properties, so no domain value can generate 65 keys.
    }
    @Test fun `UTF16 text budgets are inclusive and also enforced on trusted output`() {
        val source = sourceWithNote("x".repeat(16_384)); val codec = ArchiveV3JsonCodec()
        assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source)))
        val root = tree(source); root["bows"].asJsonArray[0].asJsonObject.addProperty("notes", "x".repeat(16_385))
        assertEquals("$.bows[0].notes", readReject(codec, root.toString().toByteArray()).path)
        val stricter = ArchiveV3JsonCodec(JsonLimits(maxStringChars = 100))
        readReject(stricter, codec.encode(source)); writeReject(stricter, source)
        val astral = sourceWithNote("🏹".repeat(8192))
        assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(astral)))
    }
    @Test fun `object keys are subject to the same UTF16 and surrogate checks`() {
        readReject(ArchiveV3JsonCodec(), ("{\"" + "k".repeat(16_385) + "\":null}").toByteArray())
        assertIs<ArchiveV3JsonRead.Rejected>(ArchiveV3JsonCodec().decode("{\"\\uD800\":null}".toByteArray()))
    }
    @Test fun `numeric representation ceiling is inclusive without rounding`() {
        val text = ArchiveV3JsonCodec().encode(sourceWithNote("")).toString(Charsets.UTF_8)
        val exact = text.replace("\"drawWeightLbs\": 1.0000001", "\"drawWeightLbs\": 1." + "0".repeat(126))
        assertNotEquals(text, exact)
        val accepted = assertIs<ArchiveV3JsonRead.Accepted>(ArchiveV3JsonCodec().decode(exact.toByteArray()))
        assertEquals(1f, accepted.archive.bows.single().drawWeightLbs)
        readReject(ArchiveV3JsonCodec(), exact.replace("1." + "0".repeat(126), "1." + "0".repeat(127)).toByteArray())
        // Valid Float.toString lexemes are shorter than 128; the writer cannot emit a longer numeric token.
    }
    @Test fun `mandatory business collection limits apply independently on read and write`() {
        val source = f.rich(); val limits = ArchiveV3Limits(maxCollectionElements = 2)
        val codec = ArchiveV3JsonCodec(archiveLimits = limits)
        val issue = readReject(codec, ArchiveV3JsonCodec().encode(source))
        assertEquals(ArchiveV3JsonIssueKind.DOMAIN, issue.kind); assertEquals(ArchiveV3IssueCode.LIMIT, issue.domainIssue!!.code)
        writeReject(codec, source)
    }
    @Test fun `mandatory cumulative business element budget applies to zones traces and all lists`() {
        val source = f.rich(); val codec = ArchiveV3JsonCodec(archiveLimits = ArchiveV3Limits(maxTotalElements = 1))
        readReject(codec, ArchiveV3JsonCodec().encode(source)); writeReject(codec, source)
    }
    @Test fun `mandatory business text limit also bounds both directions`() {
        val source = sourceWithNote("x".repeat(101)); val codec = ArchiveV3JsonCodec(archiveLimits = ArchiveV3Limits(maxTextLength = 100))
        assertEquals("$.bows[0].notes", readReject(codec, ArchiveV3JsonCodec().encode(source)).path)
        writeReject(codec, source)
    }
    @Test fun `budgets can be tightened but never enlarged or disabled`() {
        for (limits in listOf(JsonLimits(maxBytes = 16 * 1024 * 1024 + 1), JsonLimits(maxDepth = 33),
            JsonLimits(maxValues = 200_001), JsonLimits(maxArrayElements = 20_001), JsonLimits(maxStringChars = 16_385)))
            assertFailsWith<IllegalArgumentException> { ArchiveV3JsonCodec(limits) }
        for (limits in listOf(ArchiveV3Limits(maxCollectionElements = 20_001), ArchiveV3Limits(maxTotalElements = 200_001),
            ArchiveV3Limits(maxTextLength = 16_385))) assertFailsWith<IllegalArgumentException> { ArchiveV3JsonCodec(archiveLimits = limits) }
        assertFailsWith<IllegalArgumentException> { JsonLimits(maxBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ArchiveV3Limits(maxTotalElements = 0) }
    }
}
