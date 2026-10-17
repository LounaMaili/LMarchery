package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.scoring.archive.*
import java.time.Instant
import java.util.TimeZone
import kotlin.test.*

class ArchiveJsonV2CodecTest {
    private val codec = ArchiveJsonCodec()
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private fun id(n: Int) = "91000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun golden(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
    private fun legacy(populated: Boolean = true) = assertIs<ArchiveJsonRead.Accepted>(codec.decode(
        golden(if (populated) "v1-rich-golden.json" else "v1-empty-golden.json"))).archive
    private fun source(populated: Boolean = true, powers: List<Float?> = listOf(Math.nextUp(35.5f))): DomainArchiveV2 {
        val old = legacy(populated)
        val bowId = old.sessions.firstOrNull()?.bowUuid ?: id(100)
        val arrowId = old.sessions.firstOrNull()?.arrowSetUuid ?: id(101)
        val bows = powers.mapIndexed { index, power -> Bow(if (index == 0) bowId else id(200 + index),
            " Arc 東京 🏹 \"quotes\"\nligne ", " division libre ", power, "", " modèle ", "éà<&>\\\t", now, now.plusNanos(1)) }
        val arrows = listOf(ArrowSet(arrowId, " Flèches 🎯 ", "500 / 1916", "28 1/2 pouces", "100 gr?",
            "", " ", "bois/carbone", "notes\n", now, now.plusNanos(2)))
        return assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(snapshotArchiveV2(old.profiles, old.sessions, bows, arrows))).archive
    }
    private fun tree(populated: Boolean = true): JsonObject = JsonParser.parseString(codec.encode(source(populated)).toString(Charsets.UTF_8)).asJsonObject
    private fun JsonObject.bow() = getAsJsonArray("bows")[0].asJsonObject
    private fun JsonObject.arrows() = getAsJsonArray("arrowSets")[0].asJsonObject
    private fun bytes(value: JsonElement) = value.toString().toByteArray(Charsets.UTF_8)
    private fun rejected(value: JsonElement, code: String? = null, path: String? = null) = rejected(bytes(value), code, path)
    private fun rejected(bytes: ByteArray, code: String? = null, path: String? = null): JsonIssue {
        val result = assertIs<VersionedArchiveJsonRead.Rejected>(codec.decodeVersioned(bytes))
        assertTrue(result.issue.path.isNotBlank()); assertTrue(result.issue.detail.isNotBlank())
        code?.let { assertEquals(it, result.issue.code) }; path?.let { assertEquals(it, result.issue.path) }
        return result.issue
    }

    @Test fun `historical V1 golden bytes read through both entry points and encode byte for byte`() {
        listOf("v1-empty-golden.json", "v1-rich-golden.json").forEach { name ->
            val original = golden(name)
            val old = assertIs<ArchiveJsonRead.Accepted>(codec.decode(original))
            val versioned = assertIs<VersionedArchiveJsonRead.V1>(codec.decodeVersioned(original))
            assertEquals(old.snapshot, versioned.snapshot)
            assertEquals(old.archive.toSnapshot(), versioned.archive.toSnapshot())
            assertContentEquals(original, codec.encode(old.archive))
            assertContentEquals(original, codec.encode(versioned.archive))
            assertFalse(JsonParser.parseString(original.toString(Charsets.UTF_8)).asJsonObject.has("bows"))
        }
    }
    @Test fun `V1 missing equipment and V2 explicitly empty catalogs remain distinguishable`() {
        val v1 = assertIs<VersionedArchiveJsonRead.V1>(codec.decodeVersioned(golden("v1-empty-golden.json")))
        assertEquals(1, v1.snapshot.schemaVersion)
        val empty = assertIs<ArchiveV2Reconstruction.Accepted>(reconstructArchiveV2(
            ArchiveV2Snapshot(2, 1, emptyList(), emptyList(), emptyList(), emptyList()))).archive
        val v2 = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(empty)))
        assertEquals(emptyList(), v2.archive.bows); assertEquals(emptyList(), v2.archive.arrowSets)
        assertEquals(2, v2.snapshot.schemaVersion)
    }
    @Test fun `complete V2 round trip preserves profiles sessions history decisions catalogs and full equality`() {
        val original = source()
        val bytes = codec.encode(original)
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes))
        assertEquals(original, restored.archive)
        assertEquals(original.hashCode(), restored.archive.hashCode())
        assertEquals(original.toSnapshot(), restored.snapshot)
        assertEquals(legacy().toSnapshot().sessions, restored.snapshot.sessions)
        val impacts = restored.archive.sessions.single().ends.single().impacts
        assertEquals(2, impacts[1].repositions.size)
        assertTrue(impacts.any { it.isX }); assertTrue(impacts.any { it.isMiss })
        assertContentEquals(bytes, codec.encode(restored.archive))
    }
    @Test fun `equipment-only V2 is valid and may contain unset optional draw weight`() {
        val original = source(false, listOf(null))
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(original))).archive
        assertEquals(original, restored)
        assertTrue(restored.profiles.isEmpty()); assertTrue(restored.sessions.isEmpty())
        assertNull(restored.bows.single().drawWeightLbs)
        assertEquals("28 1/2 pouces", restored.arrowSets.single().length)
    }
    @Test fun `V2 can never pass through the operational V1 decoder or be implicitly downgraded`() {
        val original = source()
        val encoded = codec.encode(original)
        assertIs<ArchiveJsonRead.Rejected>(codec.decode(encoded))
        assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(encoded))
        assertFalse(DomainArchive::class.java.isAssignableFrom(DomainArchiveV2::class.java))
        val disguised = JsonParser.parseString(encoded.toString(Charsets.UTF_8)).asJsonObject
        disguised.addProperty("schemaVersion", 1)
        rejected(disguised, "UNKNOWN_FIELD", "$.bows")
    }
    @Test fun `root lists require explicit keys and arrays never absent null scalar or object defaults`() {
        listOf("profiles", "sessions", "bows", "arrowSets").forEach { name ->
            val missing = tree(false); missing.remove(name); rejected(missing, "MISSING_FIELD", "$.$name")
            listOf<JsonElement>(JsonNull.INSTANCE, JsonPrimitive("[]"), JsonObject(), JsonPrimitive(false)).forEach { value ->
                val bad = tree(false); bad.add(name, value); rejected(bad, "TYPE", "$.$name")
            }
        }
    }
    @Test fun `every equipment key is required even if its declared value may be null`() {
        val root = tree(false)
        listOf("bows" to root.bow(), "arrowSets" to root.arrows()).forEach { (kind, row) ->
            row.keySet().toList().forEach { field ->
                val bad = root.deepCopy(); bad[kind].asJsonArray[0].asJsonObject.remove(field)
                rejected(bad, "MISSING_FIELD", "$.$kind[0].$field")
            }
        }
    }
    @Test fun `required equipment scalars cannot be null and optional strings can remain explicitly null`() {
        listOf("bows", "arrowSets").forEach { kind ->
            listOf("uuid", "name", "createdAt", "updatedAt").forEach { field ->
                val bad = tree(false); bad[kind].asJsonArray[0].asJsonObject.add(field, JsonNull.INSTANCE)
                rejected(bad, "MISSING_FIELD", "$.$kind[0].$field")
            }
        }
        val valid = tree(false)
        listOf("type", "brand", "model", "notes").forEach { valid.bow().add(it, JsonNull.INSTANCE) }
        listOf("spine", "length", "pointWeight", "nock", "vanes", "material", "notes").forEach { valid.arrows().add(it, JsonNull.INSTANCE) }
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes(valid))).archive
        assertNull(restored.bows.single().type); assertNull(restored.arrowSets.single().spine)
    }
    @Test fun `identity collisions across bow impact arrow profile and equipment kinds are rejected precisely`() {
        val root = tree(); val session = root["sessions"].asJsonArray[0].asJsonObject
        val impactId = session["ends"].asJsonArray[0].asJsonObject["impacts"].asJsonArray[0].asJsonObject["uuid"].asString
        val profileId = root["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject["uuid"].asString
        val bowImpact = root.deepCopy(); bowImpact.bow().addProperty("uuid", impactId)
        rejected(bowImpact, "DUPLICATE_UUID", "$.bows[0].uuid")
        val arrowProfile = root.deepCopy(); arrowProfile.arrows().addProperty("uuid", profileId)
        rejected(arrowProfile, "DUPLICATE_UUID", "$.arrowSets[0].uuid")
        val same = root.deepCopy(); same.arrows().addProperty("uuid", same.bow()["uuid"].asString)
        rejected(same, "DUPLICATE_UUID", "$.arrowSets[0].uuid")
        val duplicate = root.deepCopy(); duplicate["bows"].asJsonArray.add(duplicate.bow().deepCopy())
        rejected(duplicate, "DUPLICATE_UUID", "$.bows[1].uuid")
    }
    @Test fun `opaque unresolved references survive V2 without invented material or null substitution`() {
        val root = tree(); root.add("bows", JsonArray()); root.add("arrowSets", JsonArray())
        val original = legacy().sessions.single()
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes(root))).archive
        assertEquals(original.bowUuid, restored.sessions.single().bowUuid)
        assertEquals(original.arrowSetUuid, restored.sessions.single().arrowSetUuid)
        assertTrue(restored.bows.isEmpty()); assertTrue(restored.arrowSets.isEmpty())
    }
    @Test fun `invalid UUIDs blank names known-profile contradictions and unsupported versions never reconstruct partially`() {
        val uuid = tree(false); uuid.bow().addProperty("uuid", "bad")
        rejected(uuid, "INVALID_UUID", "$.bows[0].uuid")
        val blank = tree(false); blank.arrows().addProperty("name", " \n\t")
        rejected(blank, "INVALID_DOMAIN_VALUE", "$.arrowSets[0].name")
        val profile = tree(); profile["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject.addProperty("name", "Tampered")
        rejected(profile, "PROFILE_IDENTITY_CONFLICT", "$.profiles[0]")
        listOf("schemaVersion" to 3, "scoringContractVersion" to 2).forEach { (field, version) ->
            val bad = tree(false); bad.addProperty(field, version); rejected(bad, "UNSUPPORTED_VERSION", "$.$field")
        }
    }
    @Test fun `supplied power rejects zero negatives nonfinite overflow underflow and wrong JSON types`() {
        listOf(0f, -0.0f, -1f).forEach { power ->
            val bad = tree(false); bad.bow().addProperty("drawWeightLbs", power)
            rejected(bad, "INVALID_DOMAIN_VALUE", "$.bows[0].drawWeightLbs")
        }
        listOf<JsonElement>(JsonPrimitive("35"), JsonPrimitive(true), JsonArray(), JsonObject()).forEach {
            val bad = tree(false); bad.bow().add("drawWeightLbs", it); rejected(bad, "TYPE", "$.bows[0].drawWeightLbs")
        }
        val text = codec.encode(source(false)).toString(Charsets.UTF_8)
        listOf("NaN", "Infinity", "-Infinity", "1e1000", "1e-1000").forEach { token ->
            val bad = text.replace("\"drawWeightLbs\": 35.500004", "\"drawWeightLbs\": $token")
            assertNotEquals(text, bad); rejected(bad.toByteArray())
        }
    }
    @Test fun `all valid power Float bits and event nanoseconds survive decimal JSON exactly`() {
        val powers = listOf(Float.MIN_VALUE, Math.nextUp(Float.MIN_VALUE), Math.nextDown(35.5f), 35.5f, Math.nextUp(35.5f), Math.nextDown(Float.MAX_VALUE), Float.MAX_VALUE)
        val original = source(false, powers)
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(original))).archive
        assertEquals(powers.map { it.toRawBits() }, restored.bows.map { it.drawWeightLbs!!.toRawBits() })
        assertEquals(123456789, restored.bows.first().createdAt.nano)
        assertEquals(123456790, restored.bows.first().updatedAt.nano)
        assertEquals(123456791, restored.arrowSets.single().updatedAt.nano)
        assertEquals(original, restored)
    }
    @Test fun `dates require explicit valid temporal context and valid chronology`() {
        listOf("2026-10-25T00:00:00", "2026-02-30T00:00:00Z", "2026-10-25T00:00:00-00:00", "garbage").forEach {
            val bad = tree(false); bad.bow().addProperty("createdAt", it)
            rejected(bad, "TEMPORAL_CONTEXT", "$.bows[0].createdAt")
        }
        val bad = tree(false); bad.arrows().addProperty("updatedAt", now.minusNanos(1).toString())
        rejected(bad, "TEMPORAL_CONTEXT", "$.arrowSets[0].updatedAt")
        val offset = tree(false); offset.bow().addProperty("createdAt", "2026-10-25T02:00:00.123456789+02:00")
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes(offset))).archive
        assertEquals(now, restored.bows.single().createdAt)
        assertTrue(codec.encode(restored).toString(Charsets.UTF_8).contains("2026-10-25T00:00:00.123456789Z"))
    }
    @Test fun `Unicode spaces empty optional strings and free units retain exact values`() {
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(codec.encode(source(false)))).archive
        assertEquals(source(false), restored)
        assertEquals(" Arc 東京 🏹 \"quotes\"\nligne ", restored.bows.single().name)
        assertEquals("", restored.bows.single().brand)
        assertEquals(" division libre ", restored.bows.single().type)
        assertEquals("500 / 1916", restored.arrowSets.single().spine)
        assertEquals("28 1/2 pouces", restored.arrowSets.single().length)
        assertEquals("100 gr?", restored.arrowSets.single().pointWeight)
        assertEquals(" ", restored.arrowSets.single().vanes)
    }
    @Test fun `encoding and decoding are independent of device default timezone`() {
        val originalZone = TimeZone.getDefault()
        val original = source(); val expected = codec.encode(original)
        try {
            listOf("Pacific/Honolulu", "Europe/Paris", "Asia/Tokyo").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertContentEquals(expected, codec.encode(original))
                assertEquals(original, assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(expected)).archive)
                assertContentEquals(golden("v1-rich-golden.json"), codec.encode(legacy()))
            }
        } finally { TimeZone.setDefault(originalZone) }
    }
    @Test fun `property order is canonical deterministic while ordered source lists are preserved`() {
        val original = source(false, listOf(40f, 30f, 20f)); val encoded = codec.encode(original)
        assertContentEquals(encoded, codec.encode(original))
        val root = JsonParser.parseString(encoded.toString(Charsets.UTF_8)).asJsonObject
        assertEquals(listOf("schemaVersion", "scoringContractVersion", "profiles", "sessions", "bows", "arrowSets"), root.keySet().toList())
        assertEquals(listOf("uuid", "name", "type", "drawWeightLbs", "brand", "model", "notes", "createdAt", "updatedAt"), root.bow().keySet().toList())
        val reversedKeys = JsonObject().also { o -> root.keySet().reversed().forEach { o.add(it, root[it]) } }
        val restored = assertIs<VersionedArchiveJsonRead.V2>(codec.decodeVersioned(bytes(reversedKeys))).archive
        assertContentEquals(encoded, codec.encode(restored))
        assertEquals(listOf(40f, 30f, 20f), restored.bows.map { it.drawWeightLbs })
    }
    @Test fun `unknown and duplicate keys including escaped names and null values are never ignored`() {
        listOf<(JsonObject) -> JsonObject>({ it }, { it.bow() }, { it.arrows() }).forEach { parent ->
            val bad = tree(false); parent(bad).addProperty("future", true); rejected(bad, "UNKNOWN_FIELD")
        }
        val text = codec.encode(source(false)).toString(Charsets.UTF_8)
        listOf(text.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 2, \"schemaVersion\": 2"),
            text.replaceFirst("\"bows\": [", "\"bows\": null, \"bo\\u0077s\": ["),
            text.replaceFirst("\"brand\": \"\"", "\"brand\": null, \"brand\": \"\"")
        ).forEach { assertNotEquals(text, it); rejected(it.toByteArray(), "DUPLICATE_KEY") }
    }
    @Test fun `malformed files invalid UTF8 BOM trailing data and isolated surrogates fail as a whole`() {
        val text = codec.encode(source(false)).toString(Charsets.UTF_8)
        listOf("", "{", text.dropLast(1), "$text {}", "//comment\n$text", "{unquoted:true}", "\uFEFF$text",
            text.replaceFirst("\"spine\": \"500 / 1916\"", "\"spine\":\"\\ud800\""),
            text.replaceFirst("\"spine\": \"500 / 1916\"", "\"spine\":\"\\udc00\"")
        ).forEach { rejected(it.toByteArray()) }
        rejected(byteArrayOf(0xc3.toByte(), 0x28))
    }
    @Test fun `byte depth value array and string budgets apply equally to V2 input and generated output`() {
        val original = source(false, listOf(20f, 30f, 40f)); val encoded = codec.encode(original)
        listOf(JsonLimits(maxBytes = encoded.size - 1), JsonLimits(maxDepth = 3), JsonLimits(maxValues = 5),
            JsonLimits(maxArrayElements = 2), JsonLimits(maxStringChars = 8)).forEach { limits ->
            val limited = ArchiveJsonCodec(limits)
            assertIs<VersionedArchiveJsonRead.Rejected>(limited.decodeVersioned(encoded))
            assertFailsWith<ArchiveJsonException> { limited.encode(original) }
        }
    }
    @Test fun `default cardinality property numeric length and nesting limits bound malicious V2 files`() {
        val root = "\"schemaVersion\":2,\"scoringContractVersion\":1,\"profiles\":[],\"sessions\":[],\"arrowSets\":[]"
        rejected("{$root,\"bows\":[${List(20_001) { "null" }.joinToString(",") }]}".toByteArray(), "LIMIT")
        rejected(("{" + (0..64).joinToString(",") { "\"p$it\":null" } + "}").toByteArray(), "LIMIT")
        val numeric = codec.encode(source(false)).toString(Charsets.UTF_8).replace("\"drawWeightLbs\": 35.500004", "\"drawWeightLbs\": ${"1".repeat(129)}")
        rejected(numeric.toByteArray(), "LIMIT")
        rejected(("[".repeat(40) + "0" + "]".repeat(40)).toByteArray())
    }
    @Test fun `late invalid row and bad session history never return a partly accepted V2 archive`() {
        val root = tree(); val late = root.arrows().deepCopy(); late.addProperty("uuid", id(999)); late.addProperty("name", " ")
        root["arrowSets"].asJsonArray.add(late)
        rejected(root, "INVALID_DOMAIN_VALUE", "$.arrowSets[1].name")
        val bad = tree(); val impact = bad["sessions"].asJsonArray[0].asJsonObject["ends"].asJsonArray[0].asJsonObject["impacts"].asJsonArray[0].asJsonObject
        impact["decision"].asJsonObject["assertions"].asJsonObject.addProperty("scoreFinal", 99)
        rejected(bad, "CONTRADICTORY_DERIVED_VALUE")
    }
    @Test fun `version dispatch rejects absent null fractional and wrongly typed versions before accepting content`() {
        rejected("{}".toByteArray(), "MISSING_FIELD", "$.schemaVersion")
        listOf<JsonElement>(JsonNull.INSTANCE, JsonPrimitive("2"), JsonPrimitive(2.0), JsonPrimitive(true)).forEach { version ->
            val bad = tree(false); bad.add("schemaVersion", version)
            rejected(bad, if (version.isJsonNull) "MISSING_FIELD" else "TYPE", "$.schemaVersion")
        }
        listOf<JsonElement>(JsonNull.INSTANCE, JsonPrimitive("1"), JsonPrimitive(1.0)).forEach { version ->
            val bad = tree(false); bad.add("scoringContractVersion", version)
            rejected(bad, if (version.isJsonNull) "MISSING_FIELD" else "TYPE", "$.scoringContractVersion")
        }
        listOf("null", "[]", "\"text\"").forEach { rejected(it.toByteArray(), "TYPE", "$") }
    }
    @Test fun `new version dispatch retains strict V1 rejections and never fills future fields`() {
        val original = JsonParser.parseString(golden("v1-rich-golden.json").toString(Charsets.UTF_8)).asJsonObject
        listOf<(JsonObject) -> Unit>(
            { it.add("bows", JsonArray()) },
            { it.remove("profiles") },
            { it["sessions"].asJsonArray[0].asJsonObject.remove("bowUuid") },
            { it["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject.addProperty("name", "Tampered") }
        ).forEach { mutate ->
            val bad = original.deepCopy(); mutate(bad)
            val legacy = assertIs<ArchiveJsonRead.Rejected>(codec.decode(bytes(bad)))
            assertEquals(legacy.issue, rejected(bytes(bad)))
        }
    }
}
