package com.lmarchery.core.export

import com.google.gson.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import java.time.*
import kotlin.test.*

class ArchiveJsonCodecTest {
    private val codec = ArchiveJsonCodec()
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private var next = 1
    private fun id() = "10000000-0000-4000-8000-${(next++).toString().padStart(12, '0')}"
    private fun t(n: Long) = now.plusSeconds(n)
    private fun session(target: TargetType = WA.recurve40, populated: Boolean = true, completed: Boolean = false): Session {
        var s = Session(id(), SessionDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00"),
            ZoneId.of("Europe/Paris"), ZoneOffset.ofHours(2)), SessionStatus.DRAFT,
            title = "Séance synthétique — 東京 🎯 \"quotes\"\nligne", targetType = target, distanceM = target.defaultDistanceM,
            arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
            bowUuid = id(), arrowSetUuid = id(), locationName = "Terrain synthétique", notes = "éà<&>\\\t", createdAt = now, updatedAt = now)
        if (!populated) return s
        s = s.start(t(1))
        val first = target.scoreBoundaries().first { it.reference.type == BoundaryType.SCORE_ZONES }
        fun arrow(index: Int, point: NormalizedPoint) = ArrowImpact(id(), index, point,
            inputMode = InputMode.CORRECTED, note = "Flèche synthétique 🎯", createdAt = t(3), updatedAt = t(3), targetType = target)
        val a = arrow(1, NormalizedPoint(Math.nextUp(first.reference.radiusNormalized), -0.0f)).let { original ->
            original.withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(first.reference,
                original.finalScoreDecision.value, t(4)), t(4))
        }
        val b = arrow(2, NormalizedPoint(first.reference.radiusNormalized, -0.0f))
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(first.reference, first.inner, t(4)), t(4))
            .reposition(NormalizedPoint(.25f, -0.0f), t(5), "Position initiale erronée")
            .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(target.maxScore), "Observation exceptionnelle 東京", t(6)), t(6))
            .reposition(NormalizedPoint(.35f, -0.0f), t(7), "Deuxième repositionnement")
        val points = listOf(NormalizedPoint(1.1f, 0f), NormalizedPoint(0f, -0.0f),
            NormalizedPoint(Float.MIN_VALUE, -0.0f), NormalizedPoint(Float.MAX_VALUE / 2, 0f),
            NormalizedPoint(Math.nextDown(first.reference.radiusNormalized), 0f),
            NormalizedPoint(first.reference.radiusNormalized, 0f))
        val end = End(id(), 1, EndStatus.EDITING, listOf(a, b) + points.mapIndexed { index, point -> arrow(index + 3, point) },
            notes = "Volée synthétique", createdAt = t(2), updatedAt = t(7))
        s = s.withEnd(if (completed) end.validate(t(10)) else end)
        return if (completed) s.complete(t(11)) else s
    }
    private fun archive(vararg sessions: Session): DomainArchive {
        val profiles = sessions.map { it.targetType }.distinct().map {
            TargetProfileSnapshot(it, BuiltInTargetRegistry.find(it.uuid)?.regulatoryEdition)
        }
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(profiles, sessions.toList()))).archive
    }
    private fun json(source: DomainArchive = archive(session())) = JsonParser.parseString(codec.encode(source).toString(Charsets.UTF_8)).asJsonObject
    private fun JsonObject.s() = getAsJsonArray("sessions")[0].asJsonObject
    private fun JsonObject.impact() = s().getAsJsonArray("ends")[0].asJsonObject.getAsJsonArray("impacts")[0].asJsonObject
    private fun rejected(json: JsonElement, code: String? = null): JsonIssue = rejected(json.toString().toByteArray(), code)
    private fun rejected(bytes: ByteArray, code: String? = null): JsonIssue {
        val issue = assertIs<ArchiveJsonRead.Rejected>(codec.decode(bytes)).issue
        assertTrue(issue.path.isNotBlank()); assertTrue(issue.detail.isNotBlank())
        if (code != null) assertEquals(code, issue.code)
        return issue
    }
    private fun floatBits(snapshot: ArchiveSnapshot): List<Int> = buildList {
        fun f(value: Float?) { value?.let { add(it.toRawBits()) } }
        fun point(p: NormalizedPoint) { f(p.x); f(p.y) }
        fun decision(d: DecisionSnapshot?) { f(d?.boundary?.radiusNormalized); f(d?.assertions?.distanceFromCenter) }
        snapshot.profiles.forEach { profile -> profile.definition!!.let { p ->
            f(p.innerTenRadiusNormalized); p.zones.forEach { f(it.innerRadiusNormalized); f(it.outerRadiusNormalized) }
        } }
        snapshot.sessions.flatMap { it.ends }.flatMap { it.impacts }.forEach { a ->
            point(a.point); decision(a.decision)
            a.repositions.forEach { point(it.previousPoint); point(it.newPoint); decision(it.previousDecision) }
        }
    }

    @Test fun `independent fixtures preserve entire domain Float bits nanos Unicode and DST civil context`() {
        val custom = ClassicTargetFixtures.classic40.copy(uuid = id(), name = "Custom synthétique", isBuiltIn = false)
        val fixtures = listOf(
            archive(), archive(session(populated = false)), archive(session()), archive(session(completed = true)),
            archive(session(), session()), archive(session(WA.recurve40), session(WA.compound60)),
            archive(session(custom)), *WA.all.map { archive(session(it)) }.toTypedArray(),
            archive(session(ClassicTargetFixtures.classic40)),
        )
        // Captured with the unchanged V1 codec at de06429, before adding the V2 contract.
        val historicalSha256 = listOf(
            "f6bcea794f8aeb411d5cca6109d0039ee14fd0d5bffe669508f1f07ffaab553c",
            "e09d35f09e8e3dcfcc539bd885e8eedf9539bb80a1f5fdc2a908422bde9636bc",
            "ca7f0de865cc8371850dcf053653f8733759b7348a5adf0995e669f2565d959c",
            "5dcd11afd947e6802f65f4b54e1c43e57244d2256d523ea55d1d2cd7cc15708c",
            "7a71cdab4d7dfaef0c56c082ffe2dfef81aab19b004c6edd76c001e4140d3964",
            "72af22e85f08b13025da8acd2f6128db505c49686b1540e9ef38d806de354d42",
            "8771737c39ac8c173c902e80d3e0409629c4207beb24e6b78a9a7dd5fa2d8da4",
            "02bd405bc0adb3e382b0501f0ef105f7c1f9fb7a446cd124c8f344e56a03dbd8",
            "ced7cb8a23118c61fc0c2da72ca69183f660b9c6b7978c47481bf5830b7183e8",
            "4dca1fb4fb3e50e78fed1e355de55aa9dcca251bd80b6115e25c5505bcef9d68",
            "f16fac6841fe43508d92ad7e71df0dedf0043c64ac84b36cc548a3dbab2c60cf",
            "8dbd82d18e36436d9d6bbfbfe847c132d31eecdc6c7eca780da8b5cfb2c7db9e",
        )
        fixtures.forEachIndexed { index, source ->
            val expected = source.toSnapshot()
            val bytes = codec.encode(source)
            assertEquals(historicalSha256[index], java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
            val accepted = assertIs<ArchiveJsonRead.Accepted>(codec.decode(bytes))
            assertEquals(expected, accepted.snapshot)
            assertEquals(expected, accepted.archive.toSnapshot())
            assertEquals(floatBits(expected), floatBits(accepted.archive.toSnapshot()))
            assertContentEquals(bytes, codec.encode(source))
            assertContentEquals(bytes, codec.encode(accepted.archive))
        }
    }

    @Test fun `current and invalidated decisions include same-score judgment exceptional reason and all ordered moves`() {
        val result = assertIs<ArchiveJsonRead.Accepted>(codec.decode(codec.encode(archive(session(ClassicTargetFixtures.classic40)))))
        val arrows = result.archive.sessions[0].ends[0].impacts
        assertEquals(ScoreDecisionKind.BOUNDARY_JUDGMENT, arrows[0].finalScoreDecision.provenance.kind)
        assertEquals(arrows[0].scoreCalculated, arrows[0].scoreFinal)
        assertEquals(listOf(ScoreDecisionKind.BOUNDARY_JUDGMENT, ScoreDecisionKind.EXCEPTIONAL_CORRECTION),
            arrows[1].repositions.map { it.invalidatedManualDecision!!.provenance.kind })
        assertEquals("Observation exceptionnelle 東京", arrows[1].repositions[1].previousDecision.provenance.reason)
        assertTrue(arrows.any { it.isMiss }); assertTrue(arrows.any { it.isX })
        assertEquals(123456789, arrows[1].repositions.last().changedAt.nano)
    }

    @Test fun `nullable properties remain explicit and missing is not interpreted as null`() {
        val root = json(); assertTrue(root.s()["endedAt"].isJsonNull)
        val snapshot = codec.decodeSnapshot(root.toString().toByteArray())
        assertNull(snapshot.sessions[0].endedAt)
        root.s().remove("endedAt"); rejected(root, "MISSING_FIELD")
        val other = json(); other.impact()["decision"].asJsonObject.remove("reason")
        rejected(other, "MISSING_FIELD")
    }

    @Test fun `all required properties including nested target fields and assertions are checked`() {
        val examples = listOf<Pair<(JsonObject) -> JsonObject, String>>(
            ({ it: JsonObject -> it } to "schemaVersion"), ({ it: JsonObject -> it.s() } to "title"), ({ it: JsonObject -> it.impact() } to "point"),
            ({ it: JsonObject -> it.impact()["decision"].asJsonObject } to "kind"),
            ({ it: JsonObject -> it.impact()["decision"].asJsonObject["assertions"].asJsonObject } to "isMiss"),
            ({ it: JsonObject -> it["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject } to "zones"))
        examples.forEach { (parent, field) -> val root = json(); parent(root).remove(field); rejected(root, "MISSING_FIELD") }
    }

    @Test fun `unknown properties at root profile decision and historical provenance are rejected`() {
        listOf<(JsonObject) -> JsonObject>(
            { it }, { it["profiles"].asJsonArray[0].asJsonObject }, { it.impact()["decision"].asJsonObject },
            { it.s()["ends"].asJsonArray[0].asJsonObject["impacts"].asJsonArray[1].asJsonObject["repositions"].asJsonArray[0].asJsonObject["previousDecision"].asJsonObject }
        ).forEach { parent -> val root = json(); parent(root).addProperty("futureProvenance", true); rejected(root, "UNKNOWN_FIELD") }
    }

    @Test fun `duplicate keys including null and escaped equivalent names never overwrite the first value`() {
        val text = codec.encode(archive(session())).toString(Charsets.UTF_8)
        listOf(text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
            text.replaceFirst("\"reason\": null", "\"reason\": null, \"reason\": null"),
            text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schema\\u0056ersion\": 1")
        ).forEach { rejected(it.toByteArray(), "DUPLICATE_KEY") }
    }

    @Test fun `versions types enums references UUID collisions and immutable profiles cannot be guessed`() {
        listOf<(JsonObject) -> Unit>(
            { it.addProperty("schemaVersion", 2) }, { it.addProperty("scoringContractVersion", 99) },
            { it.addProperty("schemaVersion", "1") }, { it.addProperty("schemaVersion", 1.0) },
            { it.s().addProperty("isIndoor", 1) }, { it.s().addProperty("status", "FUTURE") },
            { it.impact().addProperty("inputMode", "UNKNOWN") }, { it.s().addProperty("targetTypeUuid", id()) },
            { it.s().addProperty("uuid", "not-a-uuid") }, { it["sessions"].asJsonArray.add(it.s().deepCopy()) },
            { it["profiles"].asJsonArray.add(it["profiles"].asJsonArray[0].deepCopy()) },
            { it["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject.addProperty("name", "Altered") },
            { it["profiles"].asJsonArray[0].asJsonObject["definition"].asJsonObject["zones"].asJsonArray[0].asJsonObject.addProperty("outerRadiusNormalized", -1) },
        ).forEach { mutate -> val root = json(); mutate(root); rejected(root) }
    }

    @Test fun `provenance scores dates civil context and historical contradictions fail without partial archive`() {
        listOf<(JsonObject) -> Unit>(
            { it.impact().add("decision", JsonNull.INSTANCE) },
            { it.impact()["decision"].asJsonObject.add("kind", JsonNull.INSTANCE) },
            { it.impact()["decision"].asJsonObject["selected"].asJsonObject.addProperty("score", 99) },
            { it.impact()["decision"].asJsonObject.addProperty("reason", "Unexpected") },
            { it.impact()["decision"].asJsonObject.addProperty("decidedAt", "2026-10-25T01:00:00") },
            { it.s()["date"].asJsonObject.addProperty("offset", "+03:00") },
            { it.impact().addProperty("createdAt", "2026-10-25T01:00:00") },
            { it.s()["ends"].asJsonArray[0].asJsonObject["impacts"].asJsonArray[1].asJsonObject["repositions"].asJsonArray[0].asJsonObject["previousPoint"].asJsonObject.addProperty("x", .99) },
            { it.impact()["decision"].asJsonObject["assertions"].asJsonObject.addProperty("scoreFinal", 99) },
        ).forEach { mutate -> val root = json(); mutate(root); rejected(root) }
    }

    @Test fun `malformed truncated trailing nonfinite bad UTF8 BOM and unpaired Unicode are rejected`() {
        val text = codec.encode(archive(session())).toString(Charsets.UTF_8)
        listOf("", "{", text.dropLast(1), "$text {}", "{unquoted:true}", "// comment\n$text",
            text.replaceFirst("\"x\": 0.10000001", "\"x\": NaN"), text.replaceFirst("\"x\": 0.10000001", "\"x\": Infinity"),
            text.replaceFirst("\"title\":", "\"title\":\"\\ud800\",\"bad\":"), "\uFEFF$text"
        ).forEach { rejected(it.toByteArray()) }
        rejected(byteArrayOf(0xc3.toByte(),0x28))
        val root = json(); root.impact()["point"].asJsonObject.addProperty("x", 1e100); rejected(root)
        val underflow = text.replaceFirst("\"x\": 0.10000001", "\"x\": 1e-1000")
        assertNotEquals(text, underflow); rejected(underflow.toByteArray())
    }

    @Test fun `bytes depth values array and string budgets bound untrusted input and generated output`() {
        val source = archive(session()); val bytes = codec.encode(source)
        listOf(JsonLimits(maxBytes = bytes.size - 1), JsonLimits(maxDepth = 3), JsonLimits(maxValues = 5),
            JsonLimits(maxArrayElements = 2), JsonLimits(maxStringChars = 8)).forEach { limits ->
            val bounded = ArchiveJsonCodec(limits)
            assertIs<ArchiveJsonRead.Rejected>(bounded.decode(bytes))
            assertFailsWith<ArchiveJsonException> { bounded.encode(source) }
        }
        rejected(("[".repeat(40) + "0" + "]".repeat(40)).toByteArray())
    }
}
