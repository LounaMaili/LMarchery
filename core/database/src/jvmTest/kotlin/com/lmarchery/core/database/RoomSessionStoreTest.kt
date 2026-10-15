package com.lmarchery.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.BoundaryReference
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue
import com.lmarchery.core.model.scoring.ScoreDecisionRequest
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.summarizeSessionScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import kotlin.test.*

class RoomSessionStoreTest {
    @TempDir lateinit var directory: Path
    private val target = ClassicTargetFixtures.classic40
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private val tables = listOf("entity_identities", "target_profiles", "target_zones", "sessions", "ends", "impacts", "score_decisions", "impact_repositions")
    private val path get() = directory.resolve("sessions.db")

    @Test
    fun `real SQLite disk file closes completely and reopens an identical draft`() = runBlocking<Unit> {
        val source = draft()
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(source)))
            assertEquals(source, assertIs<SessionReadResult.Found>(store.read(source.uuid)).session)
        }
        assertEquals("SQLite format 3\u0000", Files.readAllBytes(path).take(16).toByteArray().toString(Charsets.US_ASCII))
        val version = scalar("SELECT sqlite_version()")
        assertTrue(version.startsWith("3."))
        assertEquals("1", scalar("PRAGMA user_version"))
        println("Real SQLite $version; on-disk header and user_version=1 verified; database closed and reopened")
        openSessionStore(path).use { store ->
            assertEquals(source, assertIs<SessionReadResult.Found>(store.read(source.uuid)).session)
        }
    }

    @Test
    fun `in progress session retains editing ends multiple impacts and their list order`() = runBlocking<Unit> {
        roundTrip(populated())
    }

    @Test
    fun `completed session retains validated ends final scores M X and statistics`() = runBlocking<Unit> {
        val source = populated(completed = true)
        val result = roundTrip(source)
        assertEquals(SessionStatus.COMPLETED, result.status)
        assertTrue(result.ends.all { it.status == EndStatus.VALIDATED })
        assertEquals(summarizeSessionScore(source), summarizeSessionScore(result))
        assertTrue(summarizeSessionScore(result).xCount > 0)
        assertTrue(summarizeSessionScore(result).missCount > 0)
    }

    @Test
    fun `all eight complete built in definitions are stored without substitution`() = runBlocking<Unit> {
        BuiltInTargetRegistry.all.forEachIndexed { index, profile ->
            val source = populated(profile.definition, seed = index * 100)
            val file = directory.resolve("profile-$index.db")
            openSessionStore(file).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
            openSessionStore(file).use { store ->
                val found = assertIs<SessionReadResult.Found>(store.read(source.uuid))
                assertEquals(source, found.session)
                assertEquals(profile.definition, found.profile.definition)
                assertEquals(profile.regulatoryEdition, found.profile.regulatoryEdition)
            }
        }
    }

    @Test
    fun `compound indoor retains no X and distinct scoring after disk reopen`() = runBlocking<Unit> {
        val source = populated(WorldArcheryIndoorTargetProfiles.compound40, completed = true)
        val restored = roundTrip(source)
        assertTrue(restored.ends.flatMap { it.impacts }.none { it.isX })
        assertEquals(0, summarizeSessionScore(restored).xCount)
        assertEquals(9, restored.ends[1].impacts[0].scoreCalculated)
    }

    @Test
    fun `custom complete definition edition and opaque equipment references are preserved`() = runBlocking<Unit> {
        val custom = target.copy(uuid = id(80), name = "Synthetic custom profile", isBuiltIn = false)
        val source = populated(custom).copy(bowUuid = id(81), arrowSetUuid = id(82))
        val profile = TargetProfileSnapshot(custom, "Club edition 1")
        openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(source, profile))) }
        openSessionStore(path).use { store ->
            val restored = assertIs<SessionReadResult.Found>(store.read(source.uuid))
            assertEquals(source, restored.session)
            assertEquals(profile, restored.profile)
        }
    }

    @Test
    fun `boundary judgment exceptional correction and multiple invalidated decisions survive SQLite`() = runBlocking<Unit> {
        val source = populated()
        val restored = roundTrip(source)
        val originalImpact = source.ends[0].impacts[1]
        val impact = restored.ends[0].impacts[1]
        assertEquals(2, impact.repositions.size)
        assertEquals(originalImpact.repositions, impact.repositions)
        assertTrue(impact.repositions.all { it.invalidatedManualDecision != null })
        assertEquals("Observed five", impact.repositions[1].previousDecision.provenance.reason)
        assertTrue(impact.isCordDecisionManual)
        assertEquals(10, impact.scoreFinal)
    }

    @Test
    fun `nanosecond timestamps are integer second nano pairs across parents decisions and traces`() = runBlocking<Unit> {
        val source = populated()
        val restored = roundTrip(source)
        assertEquals(source, restored)
        assertEquals("123456789", scalar("SELECT created_nano FROM sessions"))
        assertEquals(now.epochSecond.toString(), scalar("SELECT created_epochSecond FROM sessions"))
        assertEquals("123456789", scalar("SELECT changed_nano FROM impact_repositions LIMIT 1"))
        assertEquals("123456789", scalar("SELECT decided_nano FROM score_decisions WHERE decided_nano IS NOT NULL LIMIT 1"))
    }

    @Test
    fun `autumn civil overlap and either explicit offset survive a changed default time zone`() = runBlocking<Unit> {
        val previous = TimeZone.getDefault()
        try {
            listOf("+01:00", "+02:00").forEachIndexed { index, offset ->
                val source = draft(seed = index * 100).copy(date = SessionDateTime.of(
                    LocalDateTime.parse("2026-10-25T02:30:00.987654321"), ZoneId.of("Europe/Paris"), ZoneOffset.of(offset)))
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
                openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
                openSessionStore(path).use {
                    assertEquals(source, assertIs<SessionReadResult.Found>(it.read(source.uuid)).session)
                }
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test
    fun `negative zero and adjacent Float coordinates and profile radii retain their exact bits`() = runBlocking<Unit> {
        val custom = target.copy(uuid = id(80), name = "Bit exact profile", isBuiltIn = false,
            zones = target.zones.mapIndexed { i, zone -> if (i == 0) zone.copy(innerRadiusNormalized = -0.0f) else zone })
        val source = populated(custom)
        val end = source.ends[1]
        val impact = ArrowImpact(id(99), 3, NormalizedPoint(Math.nextUp(0.1f), -0.0f), inputMode = InputMode.MANUAL,
            createdAt = now.plusSeconds(2), updatedAt = now.plusSeconds(2), targetType = custom)
        val changed = source.withEnd(end.withImpact(impact, source.updatedAt), source.updatedAt)
        val result = roundTrip(changed)
        assertEquals((-0.0f).toRawBits(), result.ends[1].impacts[1].point.y.toRawBits())
        assertEquals((-0.0f).toRawBits(), result.targetType.zones[0].innerRadiusNormalized.toRawBits())
    }

    @Test
    fun `identical creation is idempotent and leaves every stored row unchanged`() = runBlocking<Unit> {
        val source = populated()
        openSessionStore(path).use { store ->
            val archive = archive(source)
            assertIs<SessionCreateResult.Created>(store.create(archive))
            val before = contents()
            assertIs<SessionCreateResult.AlreadyExists>(store.create(archive))
            assertEquals(before, contents())
        }
    }

    @Test
    fun `different same UUID session is an explicit conflict regardless of updatedAt`() = runBlocking<Unit> {
        val source = populated()
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(source)))
            val before = contents()
            listOf(source.updatedAt, source.updatedAt.plusSeconds(100)).forEach { date ->
                assertIs<SessionCreateResult.Conflict>(store.create(archive(source.copy(title = "Different title", updatedAt = date))))
                assertEquals(before, contents())
                assertEquals(source, assertIs<SessionReadResult.Found>(store.read(source.uuid)).session)
            }
        }
    }

    @Test
    fun `custom profile identity with different definition conflicts without modifying storage`() = runBlocking<Unit> {
        val custom = target.copy(uuid = id(80), isBuiltIn = false)
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(draft(custom))))
            val before = contents()
            val other = draft(custom.copy(name = "Different definition"), seed = 100)
            assertIs<SessionCreateResult.Conflict>(store.create(archive(other)))
            assertEquals(before, contents())
        }
    }

    @Test
    fun `impact UUID already owned by another session conflicts transactionally`() = runBlocking<Unit> {
        val source = populated()
        val another = populated(seed = 100)
        val end = another.ends[1]
        val collision = end.impacts.single().copy(uuid = source.ends[1].impacts.single().uuid)
        val changed = another.copy(ends = listOf(another.ends[0], end.copy(impacts = listOf(collision))))
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(source)))
            val before = contents()
            assertIs<SessionCreateResult.Conflict>(store.create(archive(changed)))
            assertEquals(before, contents())
            assertIs<SessionReadResult.NotFound>(store.read(changed.uuid))
        }
    }

    @Test
    fun `identity collisions across entity kinds do not bypass global UUID ownership`() = runBlocking<Unit> {
        val source = draft()
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(source)))
            val custom = target.copy(uuid = source.uuid, isBuiltIn = false)
            assertIs<SessionCreateResult.Conflict>(store.create(archive(draft(custom, 100))))
        }
    }

    @Test
    fun `late SQLite trigger failure rolls back profile identities session children decisions and traces`() = runBlocking<Unit> {
        val source = populated()
        openSessionStore(path).use { store ->
            assertIs<SessionReadResult.NotFound>(store.read(source.uuid)) // Create the actual Room schema.
            execute("CREATE TRIGGER fail_late BEFORE INSERT ON impact_repositions WHEN NEW.sequence = 1 BEGIN SELECT RAISE(ABORT, 'synthetic late failure'); END")
            assertIs<SessionCreateResult.TechnicalFailure>(store.create(archive(source)))
            tables.forEach { assertEquals("0", scalar("SELECT count(*) FROM $it"), it) }
            assertIs<SessionReadResult.NotFound>(store.read(source.uuid))
            execute("DROP TRIGGER fail_late")
            assertIs<SessionCreateResult.Created>(store.create(archive(source)))
        }
        openSessionStore(path).use { assertEquals(source, assertIs<SessionReadResult.Found>(it.read(source.uuid)).session) }
    }

    @Test
    fun `concurrent identical creators serialize and leave exactly one complete session`() = runBlocking<Unit> {
        val source = populated()
        openSessionStore(path).use { store ->
            val input = archive(source)
            val results = listOf(async(Dispatchers.Default) { store.create(input) }, async(Dispatchers.Default) { store.create(input) }).awaitAll()
            assertEquals(1, results.count { it is SessionCreateResult.Created })
            assertEquals(1, results.count { it is SessionCreateResult.AlreadyExists })
            assertEquals("1", scalar("SELECT count(*) FROM sessions"))
            assertEquals(source, assertIs<SessionReadResult.Found>(store.read(source.uuid)).session)
        }
    }

    @Test
    fun `missing current decision is intervention and never an implicit geometry or empty session`() = runBlocking<Unit> {
        corrupt("DELETE FROM score_decisions WHERE revision = -1")
    }

    @Test
    fun `removing the last reposition cannot silently remove invalidation history`() = runBlocking<Unit> {
        corrupt("DELETE FROM impact_repositions WHERE sequence = 1")
    }

    @Test
    fun `derived score corruption and illegal manual choices are rejected contextually`() = runBlocking<Unit> {
        corrupt("UPDATE score_decisions SET assertions_final = 99 WHERE revision = -1")
    }

    @Test
    fun `unknown enum and noncanonical boolean do not acquire silent default values`() = runBlocking<Unit> {
        listOf("UPDATE sessions SET scoringMode = 'UNKNOWN'", "UPDATE sessions SET isIndoor = 2").forEachIndexed { index, sql ->
            corrupt(sql, file = directory.resolve("corrupt-$index.db"))
        }
    }

    @Test
    fun `invalid nano and partial timestamp are refused without normalization`() = runBlocking<Unit> {
        listOf("UPDATE sessions SET created_nano = 1000000000", "UPDATE sessions SET created_nano = NULL").forEachIndexed { index, sql ->
            corrupt(sql, file = directory.resolve("time-$index.db"))
        }
    }

    @Test
    fun `unknown archive or scoring versions stored in SQLite require intervention`() = runBlocking<Unit> {
        listOf("UPDATE sessions SET archiveSchemaVersion = 99", "UPDATE sessions SET scoringContractVersion = 99").forEachIndexed { index, sql ->
            corrupt(sql, file = directory.resolve("version-$index.db"))
        }
    }

    @Test
    fun `tampered official target definition is never replaced by the current registry`() = runBlocking<Unit> {
        corrupt("UPDATE target_profiles SET diameterCm = 60")
    }

    @Test
    fun `missing child counter and noncontiguous child position are detected`() = runBlocking<Unit> {
        listOf("UPDATE sessions SET endCount = endCount + 1", "UPDATE ends SET position = 7 WHERE position = 1").forEachIndexed { index, sql ->
            corrupt(sql, file = directory.resolve("count-$index.db"))
        }
    }

    @Test
    fun `missing identity registry or wrong ownership kind is diagnosed`() = runBlocking<Unit> {
        corrupt("UPDATE entity_identities SET kind = 'END' WHERE kind = 'SESSION'")
    }

    @Test
    fun `registered session with missing root row is distinguished from an absent UUID`() = runBlocking<Unit> {
        val source = draft()
        openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
        execute("DELETE FROM sessions")
        openSessionStore(path).use { store ->
            assertIs<SessionReadResult.NeedsIntervention>(store.read(source.uuid))
            assertIs<SessionReadResult.NotFound>(store.read(id(999)))
        }
    }

    @Test
    fun `foreign keys and unique positions are enforced by the real SQLite engine`() = runBlocking<Unit> {
        openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(populated()))) }
        assertFails { execute("DELETE FROM target_profiles") }
        assertFails { execute("UPDATE ends SET position = 0 WHERE position = 1") }
        assertFails { execute("UPDATE impacts SET profileUuid = '${id(999)}'") }
        assertEquals("", rows("PRAGMA foreign_key_check").joinToString())
    }

    @Test
    fun `absent record technical IO failure and closed store have distinct results`() = runBlocking<Unit> {
        val store = openSessionStore(path)
        assertIs<SessionReadResult.NotFound>(store.read(id(999)))
        store.close()
        assertIs<SessionReadResult.TechnicalFailure>(store.read(id(999)))
        assertIs<SessionCreateResult.TechnicalFailure>(store.create(archive(draft())))
        val invalidParent = directory.resolve("not-a-directory")
        Files.writeString(invalidParent, "Synthetic file blocking the database directory")
        openSessionStore(invalidParent.resolve("sessions.db")).use {
            assertIs<SessionReadResult.TechnicalFailure>(it.read(id(999)))
        }
    }

    @Test
    fun `active exceptional correction is retained separately from position origin`() = runBlocking<Unit> {
        val source = populated()
        val end = source.ends[1]
        val revised = end.impacts.single().withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "Observed miss", now.plusSeconds(3)), now.plusSeconds(3))
        val restored = roundTrip(source.withEnd(end.withImpact(revised)))
        val impact = restored.ends[1].impacts.single()
        assertTrue(impact.isMiss)
        assertFalse(impact.isCordDecisionManual)
        assertEquals(InputMode.AUTOMATIC, impact.inputMode)
        assertEquals("Observed miss", impact.finalScoreDecision.provenance.reason)
    }

    @Test
    fun `manual provenance invalidation reason and actual boundary are revalidated`() = runBlocking<Unit> {
        listOf("UPDATE score_decisions SET kind = 'GEOMETRY' WHERE decided_epochSecond IS NOT NULL",
            "UPDATE impact_repositions SET reason = NULL WHERE sequence = 0",
            "UPDATE score_decisions SET boundaryRadiusBits = ${0.15f.toRawBits()} WHERE boundaryType IS NOT NULL"
        ).forEachIndexed { index, sql -> corrupt(sql, directory.resolve("provenance-$index.db")) }
    }

    @Test
    fun `parent chronology and impossible final X miss flags remain intervention`() = runBlocking<Unit> {
        listOf("UPDATE sessions SET updated_epochSecond = created_epochSecond, updated_nano = created_nano",
            "UPDATE score_decisions SET selectedIsX = 1 WHERE selectedScore = 0"
        ).forEachIndexed { index, sql -> corrupt(sql, directory.resolve("invariant-$index.db")) }
    }

    @Test
    fun `creation refuses a multi session archive without writing partial data`() = runBlocking<Unit> {
        val source = draft()
        val input = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(target, null)), listOf(source, draft(seed = 100))))).archive
        openSessionStore(path).use { store ->
            assertIs<SessionReadResult.NotFound>(store.read(source.uuid))
            val before = contents()
            assertIs<SessionCreateResult.NeedsIntervention>(store.create(input))
            assertEquals(before, contents())
        }
    }

    @Test
    fun `unsupported Room schema is not destructively recreated`() = runBlocking<Unit> {
        val source = draft()
        openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
        execute("PRAGMA user_version = 2")
        openSessionStore(path).use { assertIs<SessionReadResult.TechnicalFailure>(it.read(source.uuid)) }
        assertEquals("1", scalar("SELECT count(*) FROM sessions"))
        assertEquals("2", scalar("PRAGMA user_version"))
    }

    private suspend fun roundTrip(source: Session): Session {
        openSessionStore(path).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
        return openSessionStore(path).use { store ->
            val result = assertIs<SessionReadResult.Found>(store.read(source.uuid))
            assertEquals(source, result.session)
            assertEquals(archive(source).toSnapshot(), archive(result.session, result.profile).toSnapshot())
            result.session
        }
    }

    @Test
    fun `discovery is deterministic read only and includes ongoing and completed sessions after reopen`() = runBlocking<Unit> {
        val ongoing = populated(seed = 100)
        val completed = populated(completed = true)
        openSessionStore(path).use { store ->
            assertEquals(emptyList(), assertIs<SessionDiscoveryResult.Found>(store.discover()).uuids)
            assertIs<SessionCreateResult.Created>(store.create(archive(ongoing)))
            assertIs<SessionCreateResult.Created>(store.create(archive(completed)))
        }
        openSessionStore(path).use { store ->
            val before = contents()
            tables.forEach { table -> listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                execute("CREATE TRIGGER discovery_${table}_$operation BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'read only'); END")
            } }
            assertEquals(listOf(completed.uuid, ongoing.uuid), assertIs<SessionDiscoveryResult.Found>(store.discover()).uuids)
            assertEquals(completed, assertIs<SessionReadResult.Found>(store.read(completed.uuid)).session)
            assertEquals(ongoing, assertIs<SessionReadResult.Found>(store.read(ongoing.uuid)).session)
            assertEquals(before, contents())
        }
    }

    @Test
    fun `discovery retains corrupt entries and orphan identities for explicit read diagnostics`() = runBlocking<Unit> {
        val missing = draft()
        val corrupt = draft(seed = 100)
        openSessionStore(path).use { store ->
            assertIs<SessionCreateResult.Created>(store.create(archive(missing)))
            assertIs<SessionCreateResult.Created>(store.create(archive(corrupt)))
        }
        execute("DELETE FROM sessions WHERE uuid = '${missing.uuid}'")
        execute("UPDATE sessions SET status = 'UNKNOWN' WHERE uuid = '${corrupt.uuid}'")
        val before = contents()
        openSessionStore(path).use { store ->
            assertEquals(listOf(missing.uuid, corrupt.uuid), assertIs<SessionDiscoveryResult.Found>(store.discover()).uuids)
            assertIs<SessionReadResult.NeedsIntervention>(store.read(missing.uuid))
            assertIs<SessionReadResult.NeedsIntervention>(store.read(corrupt.uuid))
        }
        assertEquals(before, contents())
    }

    @Test
    fun `closed store discovery reports technical failure`() = runBlocking<Unit> {
        val store = openSessionStore(path)
        store.close()
        assertIs<SessionDiscoveryResult.TechnicalFailure>(store.discover())
    }

    private suspend fun corrupt(sql: String, file: Path = path) {
        val source = populated()
        openSessionStore(file).use { assertIs<SessionCreateResult.Created>(it.create(archive(source))) }
        execute(sql, file)
        val before = contents(file)
        openSessionStore(file).use { store ->
            val result = assertIs<SessionReadResult.NeedsIntervention>(store.read(source.uuid))
            assertTrue(result.diagnostic.path.isNotBlank())
            assertTrue(result.diagnostic.detail.isNotBlank())
            assertIs<SessionCreateResult.NeedsIntervention>(store.create(archive(source)))
        }
        assertEquals(before, contents(file), "Reading or conflicting creation must not repair/overwrite corrupt data.")
    }

    private fun rows(sql: String, file: Path = path): List<String> = BundledSQLiteDriver().open(file.toString()).use { connection ->
        connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
        connection.prepare(sql).use { statement -> buildList {
            while (statement.step()) add((0 until statement.getColumnCount()).joinToString("|") { column ->
                if (statement.isNull(column)) "NULL" else statement.getText(column)
            })
        } }
    }
    private fun execute(sql: String, file: Path = path) { rows(sql, file) }
    private fun scalar(sql: String, file: Path = path) = rows(sql, file).single()
    private fun contents(file: Path = path) = tables.associateWith { rows("SELECT * FROM $it ORDER BY 1, 2", file) }

    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun date() = SessionDateTime.of(LocalDateTime.parse("2026-10-25T02:30:00.987654321"),
        ZoneId.of("Europe/Paris"), ZoneOffset.of("+01:00"))

    private fun draft(profile: TargetType = target, seed: Int = 0) = Session(uuid = id(seed + 1), date = date(),
        status = SessionStatus.DRAFT, title = "Synthetic draft", targetType = profile, distanceM = profile.defaultDistanceM,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.TRAINING,
        createdAt = now, updatedAt = now)

    private fun populated(profile: TargetType = target, seed: Int = 0, completed: Boolean = false): Session {
        fun impact(index: Int, radius: Float, serial: Int) = ArrowImpact(id(seed + serial), index, NormalizedPoint(radius, 0f),
            inputMode = InputMode.AUTOMATIC, note = "Synthetic impact", createdAt = now.plusSeconds(2), updatedAt = now.plusSeconds(2), targetType = profile)
        val tenBoundary = BoundaryReference(BoundaryType.SCORE_ZONES, profile.zones.first().outerRadiusNormalized)
        val judged = impact(1, 0.105f, 11).withFinalScore(profile,
            ScoreDecisionRequest.JudgeBoundary(tenBoundary, BoundaryValue(10), now.plusSeconds(3)), now.plusSeconds(4))
            .reposition(NormalizedPoint(0.25f, 0f), now.plusSeconds(5), "Correct first position")
            .withFinalScore(profile, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Observed five", now.plusSeconds(6)), now.plusSeconds(7))
            .reposition(NormalizedPoint(0f, 0f), now.plusSeconds(8), "Correct second position")
            .withFinalScore(profile, ScoreDecisionRequest.JudgeBoundary(tenBoundary, BoundaryValue(10), now.plusSeconds(9)), now.plusSeconds(9))
        val end1 = End(id(seed + 2), 1, EndStatus.EDITING,
            listOf(impact(2, 0f, 12), judged, impact(3, 1.1f, 13)), "Synthetic end", now.plusSeconds(1), now.plusSeconds(9))
        val end2 = End(id(seed + 3), 2, EndStatus.EDITING, listOf(impact(1, 0.15f, 14)),
            null, now.plusSeconds(1), now.plusSeconds(9))
        val ends = listOf(end1, end2).map { if (completed) it.validate(now.plusSeconds(10)) else it }
        val session = Session(uuid = id(seed + 1), date = date(), status = SessionStatus.IN_PROGRESS, startedAt = now,
            title = "Synthetic complete graph", targetType = profile, distanceM = profile.defaultDistanceM,
            arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
            locationName = "Synthetic range", notes = "Synthetic session", ends = ends, createdAt = now, updatedAt = now.plusSeconds(11))
        return if (completed) session.complete(now.plusSeconds(12)) else session
    }

    private fun archive(session: Session, profile: TargetProfileSnapshot = TargetProfileSnapshot(session.targetType,
        BuiltInTargetRegistry.find(session.targetType.uuid)?.regulatoryEdition)): DomainArchive =
        assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(listOf(profile), listOf(session)))).archive
}
