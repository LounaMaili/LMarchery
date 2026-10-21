package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.archive.v3.*
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import java.security.MessageDigest
import java.util.TimeZone
import kotlin.test.*

class ArchiveV3JsonCodecTest {
    private val f = V3CodecFixtures
    private val codec = ArchiveV3JsonCodec()
    private fun golden() = f.golden("v3-rich-golden.json")
    private fun tree() = JsonParser.parseString(golden().toString(Charsets.UTF_8)).asJsonObject
    private fun bytes(value: JsonElement) = value.toString().toByteArray(Charsets.UTF_8)
    private fun accepted(value: JsonElement) = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(bytes(value)))
    private fun rejected(value: JsonElement, code: String? = null, path: String? = null) = rejected(bytes(value), code, path)
    private fun rejected(value: ByteArray, code: String? = null, path: String? = null): ArchiveV3JsonIssue {
        val issue = assertIs<ArchiveV3JsonRead.Rejected>(codec.decode(value)).issue
        assertTrue(issue.detail.isNotBlank()); assertTrue(issue.path.startsWith("$"))
        code?.let { assertEquals(it, issue.code, issue.toString()) }
        path?.let { assertEquals(it, issue.path, issue.toString()) }
        return issue
    }
    private fun JsonObject.session(i: Int = 0) = getAsJsonArray("sessions")[i].asJsonObject
    private fun JsonObject.captured(i: Int = 0) = session().getAsJsonArray("ends")[i].asJsonObject
    private fun JsonObject.impact(i: Int = 0) = captured()["end"].asJsonObject["impacts"].asJsonArray[i].asJsonObject
    private fun JsonObject.bow() = getAsJsonArray("bows")[0].asJsonObject
    private fun JsonObject.profile() = getAsJsonArray("profiles")[0].asJsonObject

    @Test fun `rich golden is exactly deterministic and preserves complete snapshot domain and equality`() {
        val source = f.rich(); val encoded = codec.encode(source)
        assertContentEquals(golden(), encoded)
        val read = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(golden()))
        assertEquals(source.toSnapshot(), read.snapshot); assertEquals(source, read.archive)
        assertEquals(source.hashCode(), read.archive.hashCode()); assertContentEquals(golden(), codec.encode(read.archive))
    }
    @Test fun `mixed configurations never inherit an initial profile during decoding`() {
        val archive = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(golden())).archive
        val mixed = archive.sessions.first()
        assertEquals(listOf(18, 18, 70, 70, 18, 70), mixed.ends.map { it.configuration.distanceM })
        assertEquals(listOf(3, 3, 6, 6, null, 5), mixed.ends.map { it.configuration.arrowCountPerEnd })
        assertEquals(listOf(10, 9, 10), mixed.ends.filter { it.end.impacts.isNotEmpty() }.map { it.end.impacts.first().scoreFinal })
        assertNotEquals(mixed.initialConfiguration.targetType, mixed.ends[2].configuration.targetType)
        assertTrue(mixed.ends.any { it.end.impacts.isEmpty() }); assertTrue(mixed.ends.any { it.end.status == EndStatus.EDITING })
        assertTrue(mixed.ends.any { it.end.status == EndStatus.VALIDATED })
    }
    @Test fun `initial inherited explicit and historical origins are all preserved without invented events`() {
        val archive = accepted(tree()).archive
        val origins = archive.sessions.flatMap { it.ends }.map { it.origin }
        assertTrue(EndConfigurationOrigin.Initial in origins); assertTrue(EndConfigurationOrigin.Explicit in origins)
        assertTrue(EndConfigurationOrigin.LegacyDerived in origins); assertTrue(origins.any { it is EndConfigurationOrigin.Inherited })
        assertEquals(setOf(SessionStatus.DRAFT, SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED), archive.sessions.map { it.status }.toSet())
        val inherited = archive.sessions.first().ends[1]
        assertEquals(archive.sessions.first().ends[0].uuid, (inherited.origin as EndConfigurationOrigin.Inherited).previousEndUuid)
        assertEquals(setOf("kind", "previousEndUuid"), tree().captured(1)["origin"].asJsonObject.keySet())
    }
    @Test fun `profile definitions editions order and unreferenced profiles are not substituted`() {
        val source = f.rich(); val restored = accepted(tree()).archive
        assertEquals(source.profiles, restored.profiles)
        val effectiveIds = restored.sessions.flatMap { s -> listOf(s.initialConfiguration.targetType.uuid) + s.ends.map { it.configuration.targetType.uuid } }
        assertTrue(restored.profiles.any { it.definition!!.uuid !in effectiveIds })
        assertTrue(restored.profiles.any { it.regulatoryEdition == null })
        assertTrue(restored.profiles.any { it.regulatoryEdition == "2026-01-27" })
    }
    @Test fun `custom profiles of same geometry keep distinct exact editions`() {
        val a = f.config(ClassicTargetFixtures.classic40.copy(uuid = f.id(810), isBuiltIn = false), edition = "édition α")
        val b = f.config(a.targetType.copy(uuid = f.id(811)), edition = "édition β")
        val s = ConfiguredSession.create(f.id(1), f.metadata(), a, f.now).start(f.now).createNextEnd(f.id(10), f.now)
            .createNextEnd(f.id(11), f.now, NextEndConfigurationChoice.Replace(b))
        val source = f.accepted(snapshotArchiveV3(listOf(TargetProfileSnapshot(a.targetType, a.regulatoryEdition),
            TargetProfileSnapshot(b.targetType, b.regulatoryEdition)), listOf(s), emptyList(), emptyList()))
        assertEquals(source, assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive)
    }
    @Test fun `material-only empty and profiles-only archives retain explicit arrays`() {
        val source = f.rich()
        for (snapshot in listOf(
            ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), source.toSnapshot().bows, source.toSnapshot().arrowSets),
            ArchiveV3Snapshot(3, 1, emptyList(), emptyList(), emptyList(), emptyList()),
            ArchiveV3Snapshot(3, 1, source.toSnapshot().profiles, emptyList(), emptyList(), emptyList()))) {
            val domain = f.accepted(snapshot)
            assertEquals(snapshot, assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(domain))).snapshot)
        }
    }
    @Test fun `unassociated material and absent opaque references survive without repair`() {
        val root = tree(); root.add("bows", JsonArray()); root.add("arrowSets", JsonArray())
        root.session()["metadata"].asJsonObject.addProperty("bowUuid", f.id(899))
        root.session()["metadata"].asJsonObject.add("arrowSetUuid", JsonNull.INSTANCE)
        val source = accepted(root).archive
        val restored = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive
        assertEquals(f.id(899), restored.sessions[0].metadata.bowUuid); assertNull(restored.sessions[0].metadata.arrowSetUuid)
        assertTrue(restored.bows.isEmpty()); assertTrue(restored.arrowSets.isEmpty())
        assertEquals(2, f.rich().bows.size)
    }
    @Test fun `judgments exceptional correction X M and multiple invalidations remain exact`() {
        val restored = accepted(tree()).archive
        val impacts = restored.sessions.flatMap { s -> s.ends.flatMap { it.end.impacts } }
        assertTrue(impacts.any { it.isX }); assertTrue(impacts.any { it.isMiss })
        assertTrue(impacts.any { it.isCordDecisionManual })
        assertTrue(impacts.any { it.finalScoreDecision.provenance.isManual && !it.isCordDecisionManual })
        val traced = restored.sessions.first().ends.first().end.impacts.single { it.index == 2 }
        assertEquals(2, traced.repositions.size); assertTrue(traced.repositions.all { !it.reason.isNullOrBlank() })
        assertEquals(f.rich().sessions.first().ends.first().end, restored.sessions.first().ends.first().end)
    }
    @Test fun `civil timezone DST offset UTC nanos and system timezone independence`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Honolulu", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertContentEquals(golden(), codec.encode(f.rich()))
                val restored = accepted(tree()).archive
                assertEquals(123456789, restored.sessions.first().createdAt.nano)
                assertEquals("Europe/Paris", restored.sessions.first().metadata.date.zoneId.id)
                assertEquals("+02:00", restored.sessions.first().metadata.date.offset.id)
            }
        } finally { TimeZone.setDefault(original) }
        val offset = tree(); offset.bow().addProperty("createdAt", "2026-10-25T02:30:00.123456789+02:00")
        val restored = accepted(offset).archive
        assertEquals(f.now, restored.bows.first().createdAt)
        assertTrue(codec.encode(restored).toString(Charsets.UTF_8).contains("2026-10-25T00:30:00.123456789Z"))
    }
    @Test fun `optional strings exact whitespace valid Unicode control escapes and no unit interpretation`() {
        val root = tree(); val text = "  東京 🏹 é e\u0301 <>& \" \\ \b\t\r\n\u0000\u2028\u2029 "
        root.bow().addProperty("notes", text); root.session()["metadata"].asJsonObject.addProperty("notes", text)
        val source = accepted(root).archive
        val restored = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive
        assertEquals(source, restored); assertEquals(text, restored.bows.first().notes)
        assertEquals("500 / 1916", restored.arrowSets.single().spine); assertEquals("28 1/2 pouces", restored.arrowSets.single().length)
        assertEquals("", restored.bows.first().brand); assertEquals(" ", restored.arrowSets.single().vanes)
        assertNull(restored.arrowSets.single().material)
    }
    @Test fun `finite Float extremes adjacent values and signed zero retain their exact bits`() {
        val c = f.config(ClassicTargetFixtures.classic40, count = null)
        val points = listOf(-0.0f, Float.MIN_VALUE, Math.nextUp(Float.MIN_VALUE), Math.nextDown(.1f), .1f, Math.nextUp(.1f), Float.MAX_VALUE)
        var s = ConfiguredSession.create(f.id(1), f.metadata(), c, f.now).start(f.now).createNextEnd(f.id(10), f.now)
        points.forEachIndexed { i, x -> s = s.withImpact(f.id(10), f.arrow(100 + i, i + 1, c, x)) }
        val powers = listOf(Float.MIN_VALUE, Math.nextUp(Float.MIN_VALUE), Math.nextDown(35.5f), 35.5f, Math.nextUp(35.5f), Math.nextDown(Float.MAX_VALUE), Float.MAX_VALUE)
        val bows = powers.mapIndexed { i, p -> f.bow().let { com.lmarchery.core.model.equipment.Bow(f.id(700 + i), it.name,
            drawWeightLbs = p, createdAt = it.createdAt, updatedAt = it.updatedAt) } }
        val source = f.accepted(snapshotArchiveV3(listOf(TargetProfileSnapshot(c.targetType, c.regulatoryEdition)), listOf(s), bows, emptyList()))
        val restored = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive
        assertEquals(source, restored)
        assertEquals(points.map { it.toRawBits() }, restored.sessions.single().ends.single().end.impacts.map { it.point.x.toRawBits() })
        assertTrue(restored.sessions.single().ends.single().end.impacts.all { it.point.y.toRawBits() == (-0.0f).toRawBits() })
        assertEquals(powers.map { it.toRawBits() }, restored.bows.map { it.drawWeightLbs!!.toRawBits() })
    }
    @Test fun `decimal-equivalent Float lexemes and negative zero exponents are accepted`() {
        val text = golden().toString(Charsets.UTF_8)
        val equivalent = text.replace("\"drawWeightLbs\": 1.0000001", "\"drawWeightLbs\": 10000001e-7")
            .replace("\"y\": -0.0", "\"y\": -0.000e+20")
        assertNotEquals(text, equivalent)
        assertEquals(f.rich(), assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(equivalent.toByteArray())).archive)
    }
    @Test fun `extra decimal precision is rejected at each Float field instead of rounded`() {
        val root = tree()
        for (path in listOf("bows[0].drawWeightLbs", "profiles[2].definition.innerTenRadiusNormalized",
            "profiles[0].definition.zones[1].outerRadiusNormalized", "profiles[0].definition.zones[1].innerRadiusNormalized",
            "sessions[0].ends[0].end.impacts[0].point.x", "sessions[0].ends[0].end.impacts[0].point.y",
            "sessions[0].ends[0].end.impacts[0].decision.assertions.distanceFromCenter",
            "sessions[0].ends[0].end.impacts[1].repositions[0].previousDecision.boundary.radiusNormalized")) {
            val bad = root.deepCopy(); val (obj, key) = parent(bad, path)
            obj.add(key, JsonParser.parseString("0.10000000000000001"))
            // Preserve the rounded geometric value, so the specific precision check is exercised.
            if (!path.endsWith(".x") && !path.endsWith(".y"))
                obj.add(key, JsonParser.parseString(at(root, path).asString + "00000000000000001"))
            rejected(bad, "FLOAT_PRECISION", "$.$path")
        }
    }
    @Test fun `required nullable properties at every record are never defaulted when omitted`() {
        val original = tree()
        for (path in representativeObjects(original)) {
            for (field in at(original, path).asJsonObject.keySet()) {
                val bad = original.deepCopy(); at(bad, path).asJsonObject.remove(field)
                rejected(bad, "MISSING_FIELD", jsonPath(path, field))
            }
        }
    }
    @Test fun `unknown fields at every nested record are rejected`() {
        val original = tree()
        for (path in representativeObjects(original)) {
            val bad = original.deepCopy(); at(bad, path).asJsonObject.add("unexpected", JsonNull.INSTANCE)
            rejected(bad, "UNKNOWN_FIELD", jsonPath(path, "unexpected"))
        }
    }
    @Test fun `duplicate null and escaped-equivalent properties at all record shapes never overwrite`() {
        val original = tree(); val text = original.toString()
        for (path in representativeObjects(original)) {
            val record = at(original, path).asJsonObject; val key = record.keySet().first()
            val duplicate = "{\"$key\":null," + record.toString().drop(1)
            rejected(text.replaceFirst(record.toString(), duplicate).toByteArray(), "DUPLICATE_KEY")
        }
        rejected(text.replaceFirst("\"schemaVersion\":3", "\"schemaVersion\":3,\"\\u0073chemaVersion\":3").toByteArray(), "DUPLICATE_KEY", "$.schemaVersion")
    }
    @Test fun `null optional fields are preserved and null indispensable fields cannot reconstruct`() {
        val original = tree(); val nullable = listOf("sessions[1].startedAt", "sessions[1].endedAt",
            "sessions[1].metadata.plannedEndCount", "sessions[1].metadata.bowUuid", "sessions[1].metadata.arrowSetUuid",
            "sessions[1].metadata.locationName", "sessions[1].metadata.notes", "bows[0].drawWeightLbs", "bows[0].type",
            "bows[0].brand", "bows[0].model", "bows[0].notes", "arrowSets[0].spine", "arrowSets[0].length",
            "arrowSets[0].pointWeight", "arrowSets[0].nock", "arrowSets[0].vanes", "arrowSets[0].material", "arrowSets[0].notes")
        val valid = original.deepCopy(); nullable.forEach { val (p, key) = parent(valid, it); p.add(key, JsonNull.INSTANCE) }
        val read = accepted(valid); nullable.forEach { assertTrue(at(JsonParser.parseString(codec.encode(read.archive).toString(Charsets.UTF_8)), it).isJsonNull) }
        for (path in listOf("schemaVersion", "scoringContractVersion", "sessions[0].uuid", "sessions[0].metadata",
            "sessions[0].initialConfiguration", "sessions[0].initialConfigurationOrigin", "sessions[0].status",
            "sessions[0].createdAt", "sessions[0].ends[0].configuration", "sessions[0].ends[0].origin", "bows[0].name")) {
            val bad = original.deepCopy(); val (p, key) = parent(bad, path); p.add(key, JsonNull.INSTANCE)
            rejected(bad, path.let { if (it.endsWith("Origin") || it.endsWith("origin")) "MISSING_PROVENANCE" else "MISSING_FIELD" }, "$.$path")
        }
    }
    @Test fun `root collections reject null objects and scalar substitutes`() {
        for (field in listOf("profiles", "sessions", "bows", "arrowSets")) {
            for (value in listOf(JsonNull.INSTANCE, JsonObject(), JsonPrimitive("[]"), JsonPrimitive(false))) {
                val bad = tree(); bad.add(field, value); rejected(bad, "TYPE", "$.$field")
            }
        }
    }
    @Test fun `unknown enums are rejected with the exact nested path`() {
        for (path in listOf("sessions[0].status", "sessions[0].initialConfigurationOrigin",
            "sessions[0].initialConfiguration.arrowCountMode", "sessions[0].initialConfiguration.scoringMode",
            "sessions[0].ends[0].origin.kind", "sessions[0].ends[0].end.status",
            "sessions[0].ends[0].end.impacts[0].inputMode", "sessions[0].ends[0].end.impacts[0].decision.kind")) {
            val root = tree(); val (p, key) = parent(root, path); p.addProperty(key, "UNKNOWN")
            rejected(root, "UNKNOWN_ENUM", "$.$path")
        }
    }
    @Test fun `incompatible scalar and child types are rejected precisely`() {
        for ((path, value) in listOf("schemaVersion" to JsonPrimitive("3"), "sessions[0].metadata" to JsonArray(),
            "sessions[0].metadata.title" to JsonPrimitive(7), "sessions[0].initialConfiguration.distanceM" to JsonPrimitive(18.5),
            "sessions[0].initialConfiguration.isIndoor" to JsonPrimitive("true"), "bows[0].drawWeightLbs" to JsonPrimitive("35"))) {
            val root = tree(); val (p, key) = parent(root, path); p.add(key, value); rejected(root, "TYPE", "$.$path")
        }
    }
    @Test fun `invalid integer ranges numeric formats overflow and underflow are rejected`() {
        val text = golden().toString(Charsets.UTF_8)
        for (token in listOf("NaN", "Infinity", "-Infinity", "1e1000", "1e-1000", "01", "+1", ".1", "1.", "0x10", "--1")) {
            val bad = text.replace("\"drawWeightLbs\": 1.0000001", "\"drawWeightLbs\": $token")
            assertNotEquals(text, bad); rejected(bad.toByteArray())
        }
        for (token in listOf("2147483648", "-2147483649", "18e0", "18.0")) {
            val bad = text.replace("\"distanceM\": 18", "\"distanceM\": $token")
            assertNotEquals(text, bad); rejected(bad.toByteArray())
        }
    }
    @Test fun `business diagnostics retain enum DTO path detail and JSON path`() {
        val root = tree(); root.captured().addProperty("sessionUuid", f.id(899))
        val issue = rejected(root, "WRONG_OWNER", "$.sessions[0].ends[0].sessionUuid")
        assertEquals(ArchiveV3JsonIssueKind.DOMAIN, issue.kind)
        assertEquals(ArchiveV3IssueCode.WRONG_OWNER, issue.domainIssue!!.code)
        assertEquals("sessions[0].ends[0].sessionUuid", issue.domainIssue.path)
        assertEquals(issue.detail, issue.domainIssue.detail)
    }
    @Test fun `contradictory inheritance owner profile and effective configuration cannot be defaulted`() {
        val edits: List<Pair<String, (JsonObject) -> Unit>> = listOf(
            "INHERITANCE_CONFLICT" to { it.captured(1)["origin"].asJsonObject.addProperty("previousEndUuid", f.id(899)) },
            "INHERITANCE_CONFLICT" to { it.captured(1)["configuration"].asJsonObject.addProperty("distanceM", 71) },
            "PROFILE_IDENTITY_CONFLICT" to { it.captured()["configuration"].asJsonObject.addProperty("regulatoryEdition", "other") },
            "PROFILE_IDENTITY_CONFLICT" to { it.profile()["definition"].asJsonObject.addProperty("name", "tampered") },
            "UNKNOWN_PROFILE" to { it.captured()["configuration"].asJsonObject.addProperty("targetTypeUuid", f.id(899)) },
            "CONFIGURATION_CONFLICT" to { it.session()["initialConfiguration"].asJsonObject.addProperty("arrowCountPerEnd", 0) },
        )
        edits.forEach { (code, edit) -> val root = tree(); edit(root); rejected(root, code) }
    }
    @Test fun `global UUID collisions repeated entities and malformed canonical IDs all fail`() {
        val edits: List<(JsonObject) -> Unit> = listOf(
            { it.bow().addProperty("uuid", it.impact()["uuid"].asString) },
            { it.getAsJsonArray("arrowSets")[0].asJsonObject.addProperty("uuid", it.profile()["definition"].asJsonObject["uuid"].asString) },
            { it.getAsJsonArray("arrowSets")[0].asJsonObject.addProperty("uuid", it.bow()["uuid"].asString) },
            { it.getAsJsonArray("sessions").add(it.session().deepCopy()) },
        )
        edits.forEach { edit -> val root = tree(); edit(root); rejected(root, "DUPLICATE_UUID") }
        for (id in listOf("bad", f.id(1).uppercase(), f.id(1).replace("-4000-", "-1000-"))) {
            val root = tree(); root.session().addProperty("uuid", id); rejected(root, "INVALID_UUID", "$.sessions[0].uuid")
        }
    }
    @Test fun `invalid dates gaps offsets and chronologies never receive a default timezone`() {
        for (text in listOf("2026-10-25T00:30:00", "2026-02-30T00:00:00Z", "2026-10-25T00:30:00-00:00", "bad")) {
            val root = tree(); root.bow().addProperty("createdAt", text); rejected(root, "TEMPORAL_CONTEXT", "$.bows[0].createdAt")
        }
        val reverse = tree(); reverse.bow().addProperty("updatedAt", f.now.minusNanos(1).toString())
        rejected(reverse, "TEMPORAL_CONTEXT", "$.bows[0].updatedAt")
        for ((field, value) in listOf("zoneId" to "invalid/zone", "offset" to "+05:00", "localDateTime" to "2026-03-29T02:30:00")) {
            val root = tree(); root.session()["metadata"].asJsonObject["date"].asJsonObject.addProperty(field, value)
            rejected(root, "CIVIL_CONTEXT_CONFLICT", "$.sessions[0].metadata.date")
        }
    }
    @Test fun `tampered decisions derived assertions and discontinuous traces fail all-or-nothing`() {
        val edits: List<(JsonObject) -> Unit> = listOf(
            { it.impact()["decision"].asJsonObject["assertions"].asJsonObject.addProperty("scoreFinal", 99) },
            { it.impact(1)["decision"].asJsonObject.addProperty("reason", " ") },
            { it.impact(1)["repositions"].asJsonArray[1].asJsonObject["previousPoint"].asJsonObject.addProperty("x", .75f) },
            { it.impact(1)["repositions"].asJsonArray[0].asJsonObject.addProperty("reason", " ") },
        )
        edits.forEach { edit -> val root = tree(); edit(root); val issue = rejected(root); assertEquals(ArchiveV3JsonIssueKind.DOMAIN, issue.kind) }
        // A late malformed material entry must not return the valid sessions already present.
        val late = tree(); late.getAsJsonArray("arrowSets")[0].asJsonObject.addProperty("name", " ")
        rejected(late, "INVALID_DOMAIN_VALUE", "$.arrowSets[0].name")
    }
    @Test fun `malformed truncated comments suffixes BOM bad UTF8 and isolated surrogates are rejected`() {
        for (text in listOf("", "null", "[]", "{", "{\"schemaVersion\":3,}", "/*x*/{}", "{} {}", "{} trailing",
            "\uFEFF{}", "{\"x\":\"\\uD800\"}", "{\"x\":\"\\uDC00\"}")) rejected(text.toByteArray())
        rejected(byteArrayOf(0xc3.toByte(), 0x28)); rejected(byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))
        for (cut in listOf(1, golden().size / 2, golden().size - 1)) rejected(golden().copyOf(cut))
        rejected(golden() + "\ntrue".toByteArray())
        rejected(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + golden())
    }
    @Test fun `new standalone decoder refuses V1 V2 and unsupported scoring without conversion`() {
        for (name in listOf("v1-empty-golden.json", "v1-rich-golden.json"))
            rejected(f.golden(name), "UNSUPPORTED_VERSION", "$.schemaVersion")
        rejected(ArchiveJsonCodec().encode(f.v2()), "UNSUPPORTED_VERSION", "$.schemaVersion")
        val root = tree(); root.addProperty("scoringContractVersion", 2)
        val issue = rejected(root, "UNSUPPORTED_VERSION", "$.scoringContractVersion")
        assertEquals(ArchiveV3IssueCode.UNSUPPORTED_VERSION, issue.domainIssue!!.code)
        for (version in listOf(0, -1, 4, Int.MAX_VALUE)) {
            val bad = tree(); bad.addProperty("schemaVersion", version); rejected(bad, "UNSUPPORTED_VERSION", "$.schemaVersion")
        }
    }
    @Test fun `old decoders reject complete V3 and their public encode overloads are unchanged`() {
        val old = ArchiveJsonCodec()
        assertIs<ArchiveJsonRead.Rejected>(old.decode(golden()))
        val read = assertIs<VersionedArchiveJsonRead.Rejected>(old.decodeVersioned(golden()))
        assertEquals("UNSUPPORTED_VERSION", read.issue.code); assertEquals("$.schemaVersion", read.issue.path)
        assertEquals(setOf(DomainArchive::class.java, DomainArchiveV2::class.java), ArchiveJsonCodec::class.java.methods
            .filter { it.name == "encode" }.map { it.parameterTypes.single() }.toSet())
    }
    @Test fun `only complete V3 domain is encodable and partial historical content has no overload`() {
        val encoders = ArchiveV3JsonCodec::class.java.methods.filter { it.name == "encode" }
        assertEquals(listOf(DomainArchiveV3::class.java), encoders.map { it.parameterTypes.single() })
        assertFalse(encoders.any { it.parameterTypes.single().isAssignableFrom(LegacyV1ConfiguredContent::class.java) })
        assertEquals(EquipmentAvailability.NOT_PROVIDED, convertArchiveV1ToConfiguredContent(f.historical()).equipmentAvailability)
    }
    @Test fun `historical golden bytes and fixed V2 output remain identical after V3 encoding`() {
        val old = ArchiveJsonCodec()
        for (name in listOf("v1-empty-golden.json", "v1-rich-golden.json")) {
            val original = f.golden(name); val domain = assertIs<ArchiveJsonRead.Accepted>(old.decode(original)).archive
            assertContentEquals(original, old.encode(domain))
        }
        val source = f.v2(); val before = old.encode(source)
        val v3 = assertIs<ArchiveV3Reconstruction.Accepted>(convertArchiveV2ToV3(source)).archive
        codec.encode(v3)
        assertContentEquals(before, old.encode(source))
        assertEquals("ffde0b4cc134bedaf36eb837eac3c0868803d957003e1d5a9ebc63a36931bf1c", MessageDigest.getInstance("SHA-256").digest(before).joinToString("") { "%02x".format(it) })
    }
    @Test fun `decoded DTO mutations cannot alter the accepted domain`() {
        val read = accepted(tree()); val original = read.archive.toSnapshot()
        @Suppress("UNCHECKED_CAST")
        (read.snapshot.sessions as MutableList<ConfiguredSessionSnapshot>).clear()
        assertEquals(original, read.archive.toSnapshot())
    }
    @Test fun `deterministic Float sample preserves power bits across magnitudes`() {
        val random = java.util.Random(38)
        val powers = generateSequence { Float.fromBits(random.nextInt() and Int.MAX_VALUE) }
            .filter { it.isFinite() && it > 0f }.take(1024).toList()
        val bow = f.snapshot().bows!!.single()
        val source = f.accepted(ArchiveV3Snapshot(3, 1, emptyList(), emptyList(),
            powers.mapIndexed { i, p -> bow.copy(uuid = f.id(1000 + i), drawWeightLbs = p) }, emptyList()))
        val restored = assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(source))).archive
        assertEquals(powers.map { it.toRawBits() }, restored.bows.map { it.drawWeightLbs!!.toRawBits() })
    }
    @Test fun `extreme exponents are diagnosed without an exception escaping the decoder`() {
        val text = golden().toString(Charsets.UTF_8)
        for (token in listOf("1e2147483648", "1e-2147483648", "0e99999999999999999999", "1e00000000000000000000000001")) {
            val bad = text.replace("\"drawWeightLbs\": 1.0000001", "\"drawWeightLbs\": $token")
            when (val read = codec.decode(bad.toByteArray())) {
                is ArchiveV3JsonRead.Rejected -> assertTrue(read.issue.path.startsWith("$"))
                is ArchiveV3JsonRead.Accepted -> assertEquals(10f, read.archive.bows.first().drawWeightLbs)
            }
        }
    }
    @Test fun `fixed-seed byte mutations either reconstruct fully or return a typed rejection`() {
        val random = java.util.Random(3800); val source = golden()
        repeat(128) {
            val changed = source.copyOf(); changed[random.nextInt(changed.size)] = random.nextInt(256).toByte()
            when (val read = codec.decode(changed)) {
                is ArchiveV3JsonRead.Rejected -> assertTrue(read.issue.path.startsWith("$"))
                is ArchiveV3JsonRead.Accepted -> assertEquals(read.archive,
                    assertIs<ArchiveV3JsonRead.Accepted>(codec.decode(codec.encode(read.archive))).archive)
            }
        }
    }
}

internal fun at(root: JsonElement, path: String): JsonElement {
    var node = root
    path.replace("]", "").split('.', '[').filter { it.isNotEmpty() }.forEach { token ->
        node = token.toIntOrNull()?.let { node.asJsonArray[it] } ?: node.asJsonObject[token]
    }
    return node
}
internal fun parent(root: JsonElement, path: String): Pair<JsonObject, String> =
    at(root, path.substringBeforeLast('.', "")).asJsonObject to path.substringAfterLast('.')
private fun jsonPath(path: String, key: String) = if (path.isEmpty()) "$.$key" else "$.$path.$key"
private fun representativeObjects(root: JsonElement): List<String> {
    val signatures = mutableSetOf<Set<String>>(); val result = mutableListOf<String>()
    fun visit(node: JsonElement, path: String) {
        if (node.isJsonObject) {
            if (signatures.add(node.asJsonObject.keySet().toSet())) result += path
            node.asJsonObject.entrySet().forEach { (key, child) -> visit(child, if (path.isEmpty()) key else "$path.$key") }
        } else if (node.isJsonArray) node.asJsonArray.forEachIndexed { i, child -> visit(child, "$path[$i]") }
    }
    visit(root, ""); return result
}
