package com.lmarchery.core.database

import androidx.room.Room
import androidx.sqlite.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.export.*
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles as WA
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class RoomArchiveExportTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("source.db")
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private fun t(n: Long) = now.plusSeconds(n)
    private fun id(n: Int) = "20000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private val tables = listOf("entity_identities", "target_profiles", "target_zones", "sessions", "ends", "impacts", "score_decisions", "impact_repositions")
    private fun seed(index: Int, populated: Boolean = false, completed: Boolean = false): Session {
        val target = if (completed) WA.compound60 else WA.recurve40
        var session = Session(id(index), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
            title = "Synthetic archive $index", targetType = target, distanceM = target.defaultDistanceM,
            arrowCountMode = ArrowCountMode.FIXED, arrowCountPerEnd = 3, isIndoor = true,
            scoringMode = ScoringMode.TRAINING, createdAt = now, updatedAt = now)
        if (!populated) return session
        fun arrow(n: Int, p: NormalizedPoint) = ArrowImpact(id(index * 100 + n), n, p, inputMode = InputMode.MANUAL,
            createdAt = t(3), updatedAt = t(3), targetType = target)
        val first = target.scoreBoundaries().first()
        val impact = arrow(1, NormalizedPoint(.105f, -0.0f))
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(first.reference, first.inner, t(4)), t(4))
            .reposition(NormalizedPoint(.25f, -0.0f), t(5), "Wrong position")
            .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(0), "Observed miss", t(6)), t(6))
        val arrows = listOf(impact, arrow(2, NormalizedPoint(.2f, 0f))) +
            if (completed) listOf(arrow(3, NormalizedPoint(0f,0f))) else emptyList()
        val end = End(id(index * 100 + 90), 1, EndStatus.EDITING, arrows, createdAt = t(2), updatedAt = t(6))
        session = session.start(t(1)).withEnd(if (completed) end.validate(t(7)) else end)
        return if (completed) session.complete(t(8)) else session
    }
    private fun archive(session: Session): DomainArchive {
        val profile = BuiltInTargetRegistry.find(session.targetType.uuid)!!
        return assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(profile.definition, profile.regulatoryEdition)), listOf(session)))).archive
    }
    private suspend fun create(store: RoomSessionStore, session: Session) {
        assertIs<SessionCreateResult.Created>(store.create(archive(session)))
    }
    private fun rows(sql: String): List<String> = BundledSQLiteDriver().open(path.toString()).use { c ->
        c.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
        c.prepare(sql).use { s -> buildList {
            while (s.step()) add((0 until s.getColumnCount()).joinToString("|") { if (s.isNull(it)) "<null>" else s.getText(it) })
        } }
    }
    private fun contents() = tables.associateWith { rows("SELECT * FROM $it ORDER BY 1,2") }

    @Test fun `global reader exports every session once with deduplicated profiles and no SQL writes`() = runBlocking<Unit> {
        val sessions = listOf(seed(1), seed(2, true), seed(3, true, true))
        var snapshot: ArchiveSnapshot? = null
        openSessionStore(path).use { store ->
            sessions.forEach { create(store, it) }
            tables.forEach { table -> listOf("INSERT","UPDATE","DELETE").forEach { op ->
                rows("CREATE TRIGGER guard_${table}_$op BEFORE $op ON $table BEGIN SELECT RAISE(ABORT, 'export must be read-only'); END")
            } }
            val before = contents()
            val result = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertEquals(sessions, result.sessions)
            assertEquals(2, result.profiles.size)
            assertEquals(2, result.sessions[1].ends[0].impacts.size)
            assertEquals(SessionStatus.COMPLETED, result.sessions[2].status)
            val bytes = ArchiveJsonCodec().encode(result)
            val decoded = assertIs<ArchiveJsonRead.Accepted>(ArchiveJsonCodec().decode(bytes)).archive
            assertEquals(result.toSnapshot(), decoded.toSnapshot())
            assertEquals(before, contents())
            snapshot = decoded.toSnapshot()
        }
        openSessionStore(path).use { store -> assertEquals(snapshot, assertIs<ArchiveReadResult.Found>(store.readArchive()).archive.toSnapshot()) }
        println("Global export: native SQLite ${rows("SELECT sqlite_version()").single()}, real disk close/reopen, all tables unchanged")
    }

    @Test fun `decoded archive can create sessions separately in an independent temporary database`() = runBlocking<Unit> {
        val sessions = listOf(seed(1), seed(2, true), seed(3, true, true))
        val source = openSessionStore(path).use { store -> sessions.forEach { create(store,it) }; assertIs<ArchiveReadResult.Found>(store.readArchive()).archive }
        val decoded = assertIs<ArchiveJsonRead.Accepted>(ArchiveJsonCodec().decode(ArchiveJsonCodec().encode(source))).archive
        val target = directory.resolve("independent.db")
        openSessionStore(target).use { store -> decoded.sessions.forEach { s ->
            val single = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
                decoded.profiles.filter { it.definition!!.uuid == s.targetType.uuid }, listOf(s)))).archive
            assertIs<SessionCreateResult.Created>(store.create(single))
        } }
        openSessionStore(target).use { store ->
            assertEquals(source.toSnapshot(), assertIs<ArchiveReadResult.Found>(store.readArchive()).archive.toSnapshot())
        }
    }

    @Test fun `empty store is explicitly empty and closed store is a technical failure`() = runBlocking<Unit> {
        val store = openSessionStore(path)
        val archive = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
        assertTrue(archive.sessions.isEmpty()); assertTrue(archive.profiles.isEmpty())
        store.close(); assertIs<ArchiveReadResult.TechnicalFailure>(store.readArchive())
    }

    @Test fun `orphan root identity fails global export with its UUID without any partial result or repair`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            create(store, seed(1, true))
            rows("INSERT INTO entity_identities(uuid,kind) VALUES('${id(99)}','SESSION')")
            val before = contents()
            val result = assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertTrue(result.diagnostic.path.contains(id(99)))
            assertEquals(before, contents())
        }
    }

    @Test fun `one corrupt session blocks the complete archive and missing profile is never substituted`() = runBlocking<Unit> {
        openSessionStore(path).use { store ->
            create(store,seed(1,true)); create(store,seed(2,true,true))
            rows("UPDATE sessions SET status='UNKNOWN' WHERE uuid='${id(2)}'")
            val before = contents()
            val result = assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertTrue(result.diagnostic.path.contains(id(2)))
            assertEquals(before, contents())
            rows("UPDATE sessions SET status='COMPLETED' WHERE uuid='${id(2)}'")
            rows("DELETE FROM target_zones WHERE profileUuid='${WA.compound60.uuid}'")
            val incomplete = contents()
            assertIs<ArchiveReadResult.NeedsIntervention>(store.readArchive())
            assertEquals(incomplete, contents())
        }
    }

    @Test fun `reader snapshot stays coherent when a writer updates and adds sessions after discovery`() = runBlocking<Unit> {
        val old = seed(1, true)
        openSessionStore(path).use { create(it,old) }
        val readStarted = CountDownLatch(1); val continueRead = CountDownLatch(1); val once = AtomicBoolean(false)
        val native = BundledSQLiteDriver()
        val driver = object : SQLiteDriver by native {
            override fun open(fileName: String): SQLiteConnection {
                val c = native.open(fileName)
                return object : SQLiteConnection by c {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = c.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                val result = statement.step()
                                if (sql.contains("SELECT uuid FROM sessions UNION") && result && once.compareAndSet(false,true)) {
                                    readStarted.countDown()
                                    check(continueRead.await(20, TimeUnit.SECONDS)) { "Test reader barrier timed out" }
                                }
                                return result
                            }
                        }
                    }
                }
            }
        }
        val reader = RoomSessionStore(Room.databaseBuilder<SessionDatabase>(path.toString()).setDriver(driver).build())
        reader.use { store -> openSessionStore(path).use { writer ->
            val reading = async(Dispatchers.IO) { store.readArchive() }
            try {
                assertTrue(readStarted.await(20, TimeUnit.SECONDS))
                val changed = old.copy(title = "Concurrent confirmed revision", updatedAt = t(9))
                withTimeout(15_000) { withContext(Dispatchers.IO) {
                    assertIs<SessionUpdateResult.Updated>(writer.update(archive(old),archive(changed)))
                    create(writer,seed(2))
                } }
            } finally { continueRead.countDown() }
            val snapshot = assertIs<ArchiveReadResult.Found>(reading.await()).archive
            assertEquals(listOf(old),snapshot.sessions, "Every read must observe the same pre-write snapshot")
            val after = assertIs<ArchiveReadResult.Found>(store.readArchive()).archive
            assertEquals(2, after.sessions.size); assertEquals("Concurrent confirmed revision",after.sessions[0].title)
        } }
    }
}
