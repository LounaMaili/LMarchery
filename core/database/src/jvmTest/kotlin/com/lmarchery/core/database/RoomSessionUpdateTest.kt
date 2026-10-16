package com.lmarchery.core.database

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.target.TargetType
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import com.lmarchery.core.scoring.summarizeSessionScore
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RoomSessionUpdateTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("updates.db")
    private val now = Instant.parse("2026-10-25T00:00:00.123456789Z")
    private val target = ClassicTargetFixtures.classic40
    private val tables = listOf("entity_identities", "target_profiles", "target_zones", "sessions", "ends", "impacts", "score_decisions", "impact_repositions")
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun t(n: Long) = now.plusSeconds(n)
    private fun draft(profile: TargetType = target, uuid: String = id(1)) = Session(uuid,
        SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), SessionStatus.DRAFT,
        title = "Synthetic update", targetType = profile, distanceM = profile.defaultDistanceM,
        arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
        createdAt = now, updatedAt = now)
    private fun arrow(uuid: String = id(11), index: Int = 1, profile: TargetType = target,
        point: NormalizedPoint = NormalizedPoint(0.105f, 0f)) = ArrowImpact(uuid, index, point,
        inputMode = InputMode.MANUAL, createdAt = t(2), updatedAt = t(2), targetType = profile)
    private fun end(impacts: List<ArrowImpact> = listOf(arrow()), uuid: String = id(2), index: Int = 1) =
        End(uuid, index, EndStatus.EDITING, impacts, createdAt = t(1),
            updatedAt = impacts.maxOfOrNull { it.updatedAt } ?: t(1))
    private fun graph() = draft().start(t(1)).withEnd(end())
    private fun replace(s: Session, a: ArrowImpact) = s.withEnd(s.ends.first().withImpact(a))
    private fun judge(a: ArrowImpact, time: Long = 3) = a.withFinalScore(a.targetType,
        ScoreDecisionRequest.JudgeBoundary(BoundaryReference(BoundaryType.SCORE_ZONES,
            a.targetType.zones.first().outerRadiusNormalized), BoundaryValue(10), t(time)), t(time))
    private fun exceptional(a: ArrowImpact, time: Long = 4) = a.withFinalScore(a.targetType,
        ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Observed five", t(time)), t(time))
    private fun archive(s: Session, profile: TargetProfileSnapshot = TargetProfileSnapshot(s.targetType,
        BuiltInTargetRegistry.find(s.targetType.uuid)?.regulatoryEdition)): DomainArchive =
        assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(listOf(profile), listOf(s)))).archive
    private suspend fun create(store: RoomSessionStore, s: Session) {
        assertIs<SessionCreateResult.Created>(store.create(archive(s)))
    }
    private suspend fun update(store: RoomSessionStore, old: Session, next: Session) {
        assertEquals(SessionUpdateResult.Updated(old.uuid), store.update(archive(old), archive(next)))
        assertEquals(next, assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
    }
    private suspend fun reopen(expected: Session) {
        openSessionStore(path).use { store ->
            val found = assertIs<SessionReadResult.Found>(store.read(expected.uuid))
            assertEquals(archive(expected).toSnapshot(), archive(found.session, found.profile).toSnapshot())
        }
    }
    private suspend fun refused(store: RoomSessionStore, old: Session, next: Session) {
        val before = contents()
        val result = assertIs<SessionUpdateResult.NeedsIntervention>(store.update(archive(old), archive(next)))
        assertTrue(result.diagnostic.path.isNotBlank())
        assertTrue(result.diagnostic.detail.isNotBlank())
        assertEquals(before, contents(), "An invalid revision must leave every table unchanged.")
    }

    @Test fun `draft metadata then start survive closing the actual SQLite file`() = runBlocking<Unit> {
        val initial = draft()
        val metadata = initial.copy(title = "Renamed", notes = "Notes", locationName = "Range", updatedAt = t(1))
        val started = metadata.start(t(2))
        openSessionStore(path).use { store ->
            create(store, initial)
            update(store, initial, metadata)
            update(store, metadata, started)
        }
        assertEquals("SQLite format 3\u0000", Files.readAllBytes(path).take(16).toByteArray().toString(Charsets.US_ASCII))
        reopen(started)
        println("Transactional updates verified on real SQLite ${rows("SELECT sqlite_version()").single()}; file closed and reopened")
    }

    @Test fun `successive ends impacts validation and completion retain counters and scores`() = runBlocking<Unit> {
        var saved = draft().start(t(1))
        openSessionStore(path).use { store ->
            create(store, saved)
            val steps = listOf(
                saved.withEnd(end(emptyList())),
                saved.withEnd(end()),
                saved.withEnd(end(listOf(arrow(), arrow(id(12), 2, point = NormalizedPoint(0f, 0f)))))
                    .withEnd(end(listOf(arrow(id(13), point = NormalizedPoint(1.1f, 0f))), id(3), 2)),
            )
            steps.forEach { next -> update(store, saved, next); saved = next }
            val validated = saved.copy(ends = saved.ends.map { it.validate(t(3)) }, updatedAt = t(3))
            update(store, saved, validated)
            val completed = validated.complete(t(4))
            update(store, validated, completed)
            saved = completed
        }
        reopen(saved)
        val summary = summarizeSessionScore(saved)
        assertEquals(1, summary.xCount)
        assertEquals(1, summary.missCount)
        assertEquals("2", rows("SELECT endCount FROM sessions").single())
        assertEquals(listOf("2", "1"), rows("SELECT impactCount FROM ends ORDER BY position"))
    }

    @Test fun `boundary and exceptional decisions are updated independently of position provenance`() = runBlocking<Unit> {
        val old = graph()
        val judged = replace(old, judge(old.ends.single().impacts.single()))
        val corrected = replace(judged, exceptional(judged.ends.single().impacts.single()))
        openSessionStore(path).use { store ->
            create(store, old)
            update(store, old, judged)
            assertEquals(10, assertIs<SessionReadResult.Found>(store.read(old.uuid)).session.ends[0].impacts[0].scoreFinal)
            update(store, judged, corrected)
        }
        reopen(corrected)
        val impact = corrected.ends[0].impacts[0]
        assertEquals(InputMode.MANUAL, impact.inputMode)
        assertEquals(ScoreDecisionKind.EXCEPTIONAL_CORRECTION, impact.finalScoreDecision.provenance.kind)
        assertEquals("Observed five", rows("SELECT reason FROM score_decisions WHERE revision = -1").single())
    }

    @Test fun `same-score manual judgments and exceptional corrections survive independent store reopenings without any move`() = runBlocking<Unit> {
        val profile = WorldArcheryIndoorTargetProfiles.recurve40
        val original = arrow(profile = profile, point = NormalizedPoint(0.105f, -0.0f))
        val old = draft(profile).start(t(1)).withEnd(end(listOf(original)))
        val decided = t(3).plusNanos(17)
        val boundary = BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f)
        val judgedArrow = original.withFinalScore(profile,
            ScoreDecisionRequest.JudgeBoundary(boundary, BoundaryValue(9), decided), decided)
        val judged = replace(old, judgedArrow)
        openSessionStore(path).use { store -> create(store, old); update(store, old, judged) }
        reopen(judged)
        val position = rows("SELECT uuid, xBits, yBits, inputMode, created_epochSecond, created_nano, repositionCount FROM impacts")
        assertEquals(listOf("BOUNDARY_JUDGMENT|9|SCORE_ZONES|${0.1f.toRawBits()}|${decided.epochSecond}|${decided.nano}"),
            rows("SELECT kind, selectedScore, boundaryType, boundaryRadiusBits, decided_epochSecond, decided_nano FROM score_decisions WHERE revision = -1"))
        assertEquals(listOf("0"), rows("SELECT COUNT(*) FROM impact_repositions"))
        val correctedAt = t(4).plusNanos(23)
        val correctedArrow = judgedArrow.withFinalScore(profile,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(9), "Same score explicitly observed", correctedAt), correctedAt)
        val corrected = replace(judged, correctedArrow)
        openSessionStore(path).use { store -> update(store, judged, corrected) }
        reopen(corrected)
        assertEquals(position, rows("SELECT uuid, xBits, yBits, inputMode, created_epochSecond, created_nano, repositionCount FROM impacts"))
        assertEquals(listOf("EXCEPTIONAL_CORRECTION|9|Same score explicitly observed|${correctedAt.epochSecond}|${correctedAt.nano}"),
            rows("SELECT kind, selectedScore, reason, decided_epochSecond, decided_nano FROM score_decisions WHERE revision = -1"))
        assertEquals(listOf("1"), rows("SELECT COUNT(*) FROM score_decisions"), "Same-position judgments are current decisions, not a full journal.")
        val moved = replace(corrected, correctedArrow.reposition(NormalizedPoint(0.25f, 0f), t(5), "Position correction after judgment"))
        openSessionStore(path).use { store -> update(store, corrected, moved) }
        reopen(moved)
        assertEquals(correctedArrow.finalScoreDecision, moved.ends[0].impacts[0].repositions.single().invalidatedManualDecision)
        assertEquals(listOf("EXCEPTIONAL_CORRECTION|Same score explicitly observed"), rows("SELECT kind, reason FROM score_decisions WHERE revision = 0"))
    }

    @Test fun `score-only update retains all prior trace rows and decisions while later SQL failure rolls back everything`() = runBlocking<Unit> {
        val original = judge(arrow()).reposition(NormalizedPoint(0.25f, -0.0f), t(4), "Earlier position")
        val old = replace(graph(), original)
        val corrected = replace(old, original.withFinalScore(target,
            ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Observed five", t(5)), t(5)))
        openSessionStore(path).use { store ->
            create(store, old)
            val trace = rows("SELECT * FROM impact_repositions")
            val history = rows("SELECT * FROM score_decisions WHERE revision >= 0")
            rows("CREATE TRIGGER protect_trace BEFORE UPDATE ON impact_repositions BEGIN SELECT RAISE(ABORT, 'immutable trace'); END")
            rows("CREATE TRIGGER protect_decision BEFORE UPDATE ON score_decisions WHEN OLD.revision >= 0 BEGIN SELECT RAISE(ABORT, 'immutable decision'); END")
            rows("CREATE TRIGGER protect_trace_delete BEFORE DELETE ON impact_repositions BEGIN SELECT RAISE(ABORT, 'immutable trace'); END")
            rows("CREATE TRIGGER protect_decision_delete BEFORE DELETE ON score_decisions BEGIN SELECT RAISE(ABORT, 'immutable decision'); END")
            // The current decision is updated after the aggregate/impact rows, so this is a late failure.
            rows("CREATE TRIGGER fail_score BEFORE UPDATE ON score_decisions WHEN OLD.revision = -1 BEGIN SELECT RAISE(ABORT, 'synthetic score failure'); END")
            val before = contents()
            assertIs<SessionUpdateResult.TechnicalFailure>(store.update(archive(old), archive(corrected)))
            assertEquals(before, contents())
            assertEquals(old, assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
            rows("DROP TRIGGER fail_score")
            update(store, old, corrected)
            assertEquals(trace, rows("SELECT * FROM impact_repositions"))
            assertEquals(history, rows("SELECT * FROM score_decisions WHERE revision >= 0"))
        }
        reopen(corrected)
    }

    @Test fun `two manual score writers from one expected revision cannot overwrite each other`() = runBlocking<Unit> {
        val old = graph()
        val first = replace(old, judge(old.ends[0].impacts[0]))
        val second = replace(old, exceptional(old.ends[0].impacts[0]))
        openSessionStore(path).use { store -> create(store, old) }
        openSessionStore(path).use { one -> openSessionStore(path).use { two ->
            val expected = archive(old)
            val results = coroutineScope {
                listOf(async(Dispatchers.Default) { one.update(expected, archive(first)) },
                    async(Dispatchers.Default) { two.update(expected, archive(second)) }).awaitAll()
            }
            assertEquals(1, results.count { it is SessionUpdateResult.Updated })
            assertEquals(1, results.count { it is SessionUpdateResult.Conflict })
            val winner = if (results[0] is SessionUpdateResult.Updated) first else second
            assertEquals(winner, assertIs<SessionReadResult.Found>(one.read(old.uuid)).session)
            assertEquals(winner, assertIs<SessionReadResult.Found>(two.read(old.uuid)).session)
            assertEquals(listOf("0"), rows("SELECT COUNT(*) FROM impact_repositions"))
        } }
    }

    @Test fun `multiple moves append invalidated decisions without rewriting existing evidence`() = runBlocking<Unit> {
        val old = graph()
        val judged = replace(old, judge(old.ends[0].impacts[0]))
        val moved = replace(judged, judged.ends[0].impacts[0].reposition(NormalizedPoint(0.25f, -0.0f), t(4), "Correct position"))
        val corrected = replace(moved, exceptional(moved.ends[0].impacts[0], 5)
            .reposition(NormalizedPoint(Math.nextUp(0.1f), -0.0f), t(6), "Second position"))
        openSessionStore(path).use { store ->
            create(store, old)
            update(store, old, judged)
            update(store, judged, moved)
            val evidence = rows("SELECT * FROM impact_repositions WHERE sequence = 0")
            val decision = rows("SELECT * FROM score_decisions WHERE revision = 0")
            // Existing historical rows must be untouched, not reinserted or overwritten.
            rows("CREATE TRIGGER guard_trace_update BEFORE UPDATE ON impact_repositions BEGIN SELECT RAISE(ABORT, 'immutable trace'); END")
            rows("CREATE TRIGGER guard_old_decision BEFORE UPDATE ON score_decisions WHEN OLD.revision >= 0 BEGIN SELECT RAISE(ABORT, 'immutable decision'); END")
            update(store, moved, corrected)
            assertEquals(evidence, rows("SELECT * FROM impact_repositions WHERE sequence = 0"))
            assertEquals(decision, rows("SELECT * FROM score_decisions WHERE revision = 0"))
        }
        reopen(corrected)
        assertEquals(listOf("0", "1"), rows("SELECT sequence FROM impact_repositions ORDER BY sequence"))
        assertEquals(listOf("-1", "0", "1"), rows("SELECT revision FROM score_decisions ORDER BY revision"))
        assertTrue(corrected.ends[0].impacts[0].repositions.all { it.invalidatedManualDecision != null })
    }

    @Test fun `single selected indoor impact appends exactly one trace and preserves siblings and history on disk`() = runBlocking<Unit> {
        val profile = WorldArcheryIndoorTargetProfiles.recurve40
        val arrows = listOf(
            arrow(id(11), 1, profile, NormalizedPoint(0f, 0f)),
            judge(arrow(id(12), 2, profile)),
            arrow(id(13), 3, profile, NormalizedPoint(1.1f, 0f)),
        )
        val old = draft(profile).start(t(1)).withEnd(end(arrows))
        val changedAt = t(4).plusNanos(17)
        val moved = arrows[1].reposition(NormalizedPoint(Math.nextUp(0.35f), -0.0f), changedAt, "Wrong position")
        val next = replace(old, moved)
        openSessionStore(path).use { store ->
            create(store, old)
            update(store, old, next)
            assertEquals(listOf("1"), rows("SELECT COUNT(*) FROM impact_repositions"))
            assertEquals(listOf("1"), rows("SELECT repositionCount FROM impacts WHERE uuid = '${moved.uuid}'"))
            assertEquals(listOf("BOUNDARY_JUDGMENT|10"), rows("SELECT kind, selectedScore FROM score_decisions WHERE revision = 0"))
            assertEquals(listOf("GEOMETRY|7"), rows("SELECT kind, selectedScore FROM score_decisions WHERE impactUuid = '${moved.uuid}' AND revision = -1"))
            assertEquals(listOf("${moved.point.x.toRawBits()}|${(-0.0f).toRawBits()}|${changedAt.nano}"),
                rows("SELECT xBits, yBits, updated_nano FROM impacts WHERE uuid = '${moved.uuid}'"))
        }
        reopen(next)
        val firstTrace = rows("SELECT * FROM impact_repositions")
        val firstDecision = rows("SELECT * FROM score_decisions WHERE revision = 0")
        val siblings = rows("SELECT * FROM impacts WHERE uuid != '${moved.uuid}' ORDER BY uuid")
        val third = replace(next, moved.reposition(NormalizedPoint(1.01f, -0.0f), t(5).plusNanos(23)))
        openSessionStore(path).use { store ->
            rows("CREATE TRIGGER immutable_trace_update BEFORE UPDATE ON impact_repositions BEGIN SELECT RAISE(ABORT, 'old trace changed'); END")
            rows("CREATE TRIGGER immutable_trace_delete BEFORE DELETE ON impact_repositions BEGIN SELECT RAISE(ABORT, 'old trace deleted'); END")
            rows("CREATE TRIGGER immutable_decision_update BEFORE UPDATE ON score_decisions WHEN OLD.revision >= 0 BEGIN SELECT RAISE(ABORT, 'old decision changed'); END")
            rows("CREATE TRIGGER immutable_decision_delete BEFORE DELETE ON score_decisions BEGIN SELECT RAISE(ABORT, 'old decision deleted'); END")
            update(store, next, third)
            assertEquals(listOf("2"), rows("SELECT COUNT(*) FROM impact_repositions"))
            assertEquals(firstTrace, rows("SELECT * FROM impact_repositions WHERE sequence = 0"))
            assertEquals(firstDecision, rows("SELECT * FROM score_decisions WHERE revision = 0"))
            assertEquals(siblings, rows("SELECT * FROM impacts WHERE uuid != '${moved.uuid}' ORDER BY uuid"))
        }
        reopen(third)
        assertEquals(arrows[0], third.ends[0].impacts[0])
        assertEquals(arrows[2], third.ends[0].impacts[2])
        assertTrue(third.ends[0].impacts[1].isMiss)
        assertEquals(arrows[1].finalScoreDecision, moved.repositions.single().invalidatedManualDecision)
    }

    @Test fun `failure while appending next selected-impact trace rolls back current decision and leaves prior evidence untouched`() = runBlocking<Unit> {
        val old = graph()
        val moved = replace(old, arrow().reposition(NormalizedPoint(0.35f, 0f), t(3)))
        val next = replace(moved, moved.ends[0].impacts[0].reposition(NormalizedPoint(1.01f, 0f), t(4)))
        openSessionStore(path).use { store -> create(store, old); update(store, old, moved) }
        openSessionStore(path).use { store ->
            rows("CREATE TRIGGER reject_next_trace BEFORE INSERT ON impact_repositions WHEN NEW.sequence = 1 BEGIN SELECT RAISE(ABORT, 'synthetic trace failure'); END")
            val before = contents()
            assertIs<SessionUpdateResult.TechnicalFailure>(store.update(archive(moved), archive(next)))
            assertEquals(before, contents())
            assertEquals(moved, assertIs<SessionReadResult.Found>(store.read(moved.uuid)).session)
            rows("DROP TRIGGER reject_next_trace")
            update(store, moved, next)
        }
        reopen(next)
    }

    @Test fun `updated nanoseconds adjacent floats and signed zero remain bit exact`() = runBlocking<Unit> {
        val old = graph()
        val a = old.ends[0].impacts[0].reposition(NormalizedPoint(Math.nextUp(0.1f), -0.0f), t(3).plusNanos(7))
        val next = replace(old, a)
        openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
        reopen(next)
        assertEquals(listOf("${Math.nextUp(0.1f).toRawBits()}|${(-0.0f).toRawBits()}|123456796"),
            rows("SELECT xBits, yBits, updated_nano FROM impacts"))
        assertEquals(listOf("123456796"), rows("SELECT changed_nano FROM impact_repositions"))
    }

    @Test fun `unchanged update executes no INSERT UPDATE or DELETE even with rejecting triggers`() = runBlocking<Unit> {
        val old = graph()
        openSessionStore(path).use { store ->
            create(store, old)
            tables.forEach { table -> listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                rows("CREATE TRIGGER guard_${table}_$operation BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'no writes'); END")
            } }
            val before = contents()
            assertEquals(SessionUpdateResult.Unchanged(old.uuid), store.update(archive(old), archive(old)))
            assertEquals(before, contents())
        }
    }

    @Test fun `stale full expected state conflicts even if proposal already equals current`() = runBlocking<Unit> {
        val old = draft()
        val next = old.copy(title = "Next", updatedAt = t(1))
        openSessionStore(path).use { store ->
            create(store, old); update(store, old, next)
            val before = contents()
            assertIs<SessionUpdateResult.Conflict>(store.update(archive(old), archive(next)))
            assertIs<SessionUpdateResult.Conflict>(store.update(archive(old), archive(old)))
            assertEquals(before, contents())
        }
    }

    @Test fun `different contents with identical updatedAt are still conflicting revisions`() = runBlocking<Unit> {
        val old = graph()
        val first = old.copy(title = "Writer A")
        val second = old.copy(title = "Writer B")
        assertEquals(first.updatedAt, second.updatedAt)
        openSessionStore(path).use { store ->
            create(store, old); update(store, old, first)
            val before = contents()
            assertIs<SessionUpdateResult.Conflict>(store.update(archive(old), archive(second)))
            assertEquals(before, contents())
        }
    }

    @Test fun `two independent concurrent writers accept only one revision`() = runBlocking<Unit> {
        val old = graph()
        val alternatives = listOf(old.copy(title = "A"), old.copy(title = "B"))
        openSessionStore(path).use { create(it, old) }
        val start = CompletableDeferred<Unit>()
        val results = alternatives.map { next -> async(Dispatchers.Default) {
            openSessionStore(path).use { store -> start.await(); store.update(archive(old), archive(next)) }
        } }.also { start.complete(Unit) }.awaitAll()
        assertEquals(1, results.count { it is SessionUpdateResult.Updated })
        assertEquals(1, results.count { it is SessionUpdateResult.Conflict })
        openSessionStore(path).use { store ->
            assertTrue(assertIs<SessionReadResult.Found>(store.read(old.uuid)).session in alternatives)
        }
    }

    @Test fun `expected comparison includes descendants even when root and all timestamps match`() = runBlocking<Unit> {
        val old = graph()
        val childRevision = replace(old, arrow().copy(note = "Another writer's note"))
        assertEquals(old.updatedAt, childRevision.updatedAt)
        openSessionStore(path).use { store ->
            create(store, old)
            update(store, old, childRevision)
            val before = contents()
            assertIs<SessionUpdateResult.Conflict>(store.update(archive(old), archive(old.copy(title = "Root edit"))))
            assertEquals(before, contents())
        }
    }

    @Test fun `manual confirmation with unchanged numeric score is a persisted update`() = runBlocking<Unit> {
        val old = graph()
        val a = arrow().withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(
            BoundaryReference(BoundaryType.SCORE_ZONES, 0.1f), BoundaryValue(9), t(3)), t(3))
        val next = replace(old, a)
        assertEquals(9, a.scoreFinal)
        openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
        reopen(next)
        assertEquals(listOf("BOUNDARY_JUDGMENT"), rows("SELECT kind FROM score_decisions WHERE revision = -1"))
    }

    @Test fun `new end and impact UUID collisions with another session leave all tables unchanged`() = runBlocking<Unit> {
        val old = graph()
        val other = draft(uuid = id(100)).start(t(1)).withEnd(end(listOf(arrow(id(111))), id(102)))
        openSessionStore(path).use { store ->
            create(store, old); create(store, other)
            val before = contents()
            val endCollision = old.withEnd(end(emptyList(), id(102), 2))
            val impactCollision = old.withEnd(old.ends[0].withImpact(arrow(id(111), 2)))
            listOf(endCollision, impactCollision).forEach { next ->
                assertIs<SessionUpdateResult.Conflict>(store.update(archive(old), archive(next)))
                assertEquals(before, contents())
            }
        }
    }

    @Test fun `validated end cannot change its impacts or be reopened by reconstruction`() = runBlocking<Unit> {
        val old = graph().withEnd(end().validate(t(3)))
        val revised = arrow().copy(note = "Altered", updatedAt = t(3))
        val different = End(id(2), 1, EndStatus.VALIDATED, listOf(revised), createdAt = t(1), updatedAt = t(3), validatedAt = t(3))
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, ends = listOf(different)))
            refused(store, old, rebuild(old, ends = listOf(end().copy(updatedAt = t(3)))))
        }
    }

    @Test fun `completed session cannot reopen change closure or replace descendants`() = runBlocking<Unit> {
        val old = graph().complete(t(4))
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, status = SessionStatus.IN_PROGRESS, ended = null))
            refused(store, old, rebuild(old, ended = t(5), updated = t(5)))
            refused(store, old, rebuild(old, ends = listOf(end().copy(notes = "Changed"))))
        }
    }

    @Test fun `completed own metadata is allowed without editing children`() = runBlocking<Unit> {
        val old = graph().complete(t(4))
        val next = old.copy(notes = "Post-session note", updatedAt = t(5))
        openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
        reopen(next)
        assertEquals(old.ends, next.ends)
    }

    @Test fun `history removal cannot pass through an otherwise valid isolated snapshot`() = runBlocking<Unit> {
        val moved = arrow().reposition(NormalizedPoint(0.25f, 0f), t(3))
        val old = replace(graph(), moved)
        val untraced = ArrowImpact(moved.uuid, moved.index, moved.point, inputMode = moved.inputMode,
            createdAt = moved.createdAt, updatedAt = moved.updatedAt, targetType = target)
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, ends = listOf(end(listOf(untraced)))))
        }
    }

    @Test fun `profile definition or edition cannot change under its existing UUID`() = runBlocking<Unit> {
        val custom = target.copy(uuid = id(80), name = "Custom", isBuiltIn = false)
        val old = draft(custom)
        val modified = old.copy(targetType = custom.copy(name = "Reinterpreted"))
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, modified)
            val before = contents()
            val anotherEdition = archive(old, TargetProfileSnapshot(custom, "Another edition"))
            assertIs<SessionUpdateResult.NeedsIntervention>(store.update(archive(old), anotherEdition))
            assertIs<SessionUpdateResult.Conflict>(store.update(anotherEdition, archive(old)))
            assertEquals(before, contents())
        }
    }

    @Test fun `SQL corruption is diagnosed before update and is never repaired`() = runBlocking<Unit> {
        val old = graph()
        val corruptions = listOf("UPDATE impacts SET updated_nano = 1000000000",
            "UPDATE score_decisions SET selectedScore = 99", "UPDATE sessions SET endCount = 2",
            "UPDATE entity_identities SET kind = 'END' WHERE kind = 'IMPACT'")
        corruptions.forEachIndexed { index, sql ->
            val file = directory.resolve("corrupt-$index.db")
            openSessionStore(file).use { create(it, old) }
            rows(sql, file)
            val before = contents(file)
            openSessionStore(file).use { store ->
                assertIs<SessionUpdateResult.NeedsIntervention>(store.update(archive(old), archive(old.copy(title = "Next"))))
            }
            assertEquals(before, contents(file))
        }
    }

    @Test fun `late SQL failure rolls back identities counts updates decisions traces and temporary ordering`() = runBlocking<Unit> {
        val old = graph().withEnd(end(listOf(arrow(id(12))), id(3), 2))
        val changed = judge(arrow()).reposition(NormalizedPoint(0.25f, 0f), t(4), "First")
            .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Observed", t(5)), t(5))
            .reposition(NormalizedPoint(0f, 0f), t(6), "Second")
        val next = old.copy(ends = listOf(old.ends[1], end(listOf(changed, arrow(id(13), 2)))), updatedAt = t(6))
        openSessionStore(path).use { store ->
            create(store, old)
            rows("CREATE TRIGGER late_failure BEFORE INSERT ON impact_repositions WHEN NEW.sequence = 1 BEGIN SELECT RAISE(ABORT, 'late update failure'); END")
            val before = contents()
            assertIs<SessionUpdateResult.TechnicalFailure>(store.update(archive(old), archive(next)))
            assertEquals(before, contents())
            assertEquals(old, assertIs<SessionReadResult.Found>(store.read(old.uuid)).session)
            rows("DROP TRIGGER late_failure")
            update(store, old, next)
        }
        reopen(next)
    }

    @Test fun `post-write reread mismatch also rolls back an apparently successful SQL update`() = runBlocking<Unit> {
        val old = graph()
        val next = old.copy(title = "Expected next")
        openSessionStore(path).use { store ->
            create(store, old)
            rows("CREATE TRIGGER alter_title AFTER UPDATE ON sessions WHEN NEW.title != 'Unexpected' BEGIN UPDATE sessions SET title = 'Unexpected' WHERE uuid = NEW.uuid; END")
            val before = contents()
            assertIs<SessionUpdateResult.NeedsIntervention>(store.update(archive(old), archive(next)))
            assertEquals(before, contents())
        }
        reopen(old)
    }

    @Test fun `end and impact position and index permutations avoid UNIQUE collisions`() = runBlocking<Unit> {
        val first = end(listOf(arrow(), arrow(id(12), 2)))
        val second = end(emptyList(), id(3), 2)
        val old = graph().copy(ends = listOf(first, second))
        val swapped = first.copy(index = 2, impacts = listOf(first.impacts[1].copy(index = 1), first.impacts[0].copy(index = 2)))
        val next = old.copy(ends = listOf(second.copy(index = 1), swapped))
        openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
        reopen(next)
        assertEquals(listOf("0|1", "1|2"), rows("SELECT position, `index` FROM ends ORDER BY position"))
        assertEquals(listOf("0|1", "1|2"), rows("SELECT position, `index` FROM impacts ORDER BY position"))
    }

    @Test fun `untraced deletion and reparenting are forbidden with complete rollback`() = runBlocking<Unit> {
        val old = graph()
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, ends = emptyList()))
            refused(store, old, rebuild(old, ends = listOf(end(emptyList()))))
            refused(store, old, rebuild(old, ends = listOf(end(emptyList()), end(listOf(arrow()), id(3), 2))))
        }
    }

    @Test fun `identity creation time start time and original input mode are preserved`() = runBlocking<Unit> {
        val old = graph()
        val origin = ArrowImpact(id(11), 1, arrow().point, inputMode = InputMode.AUTOMATIC,
            createdAt = t(2), updatedAt = t(2), targetType = target)
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, uuid = id(99)))
            refused(store, old, rebuild(old, created = now.minusNanos(1)))
            refused(store, old, rebuild(old, started = t(2)))
            refused(store, old, rebuild(old, ends = listOf(end(listOf(origin)))))
        }
    }

    @Test fun `manual provenance and coordinate paths cannot be silently overwritten`() = runBlocking<Unit> {
        val old = replace(graph(), judge(arrow()))
        val silentMove = arrow(point = NormalizedPoint(0.25f, 0f)).copy(updatedAt = t(3))
        openSessionStore(path).use { store ->
            create(store, old)
            refused(store, old, rebuild(old, ends = listOf(end(listOf(arrow().copy(updatedAt = t(3)))))))
            refused(store, old, rebuild(old, ends = listOf(end(listOf(silentMove)))))
        }
    }

    @Test fun `all eight published profiles support contextual decision updates without reinterpretation`() = runBlocking<Unit> {
        BuiltInTargetRegistry.all.forEachIndexed { index, profile ->
            val old = draft(profile.definition, id(100 + index)).start(t(1))
                .withEnd(end(listOf(arrow(id(200 + index), profile = profile.definition)), id(300 + index)))
            val next = replace(old, judge(old.ends[0].impacts[0]))
            openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
            reopen(next)
        }
    }

    @Test fun `multiple valid lifecycle operations can be saved in one transaction`() = runBlocking<Unit> {
        val old = draft()
        val next = old.start(t(1)).withEnd(end().validate(t(3))).complete(t(4))
        openSessionStore(path).use { store -> create(store, old); update(store, old, next) }
        reopen(next)
    }

    @Test fun `absent sessions closed store and invalid archive scope have distinct outcomes`() = runBlocking<Unit> {
        val old = draft()
        val store = openSessionStore(path)
        assertIs<SessionUpdateResult.NotFound>(store.update(archive(old), archive(old)))
        create(store, old)
        val multi = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
            listOf(TargetProfileSnapshot(target, null)), listOf(old, draft(uuid = id(90)))))).archive
        val before = contents()
        assertIs<SessionUpdateResult.NeedsIntervention>(store.update(multi, multi))
        assertEquals(before, contents())
        store.close()
        assertIs<SessionUpdateResult.TechnicalFailure>(store.update(archive(old), archive(old)))
    }

    @Test fun `coroutine cancellation propagates instead of becoming a technical result`() = runBlocking<Unit> {
        val old = graph()
        openSessionStore(path).use { store ->
            create(store, old)
            val before = contents()
            var propagated = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { store.update(archive(old), archive(old.copy(title = "Cancelled"))) }
                catch (error: CancellationException) { propagated = true }
            }
            job.join()
            assertTrue(propagated)
            assertEquals(before, contents())
        }
    }

    @Test fun `cancellation observed after a real SQL write rolls back the complete revision`() = runBlocking<Unit> {
        val cancelOnTrace = AtomicReference<(() -> Unit)?>(null)
        val native = BundledSQLiteDriver()
        // Delegate every operation to native SQLite; cancel only after an actual trace INSERT.
        val driver = object : SQLiteDriver by native {
            override fun open(fileName: String): SQLiteConnection {
                val connection = native.open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                val result = statement.step()
                                if (sql.startsWith("INSERT") && sql.contains("impact_repositions")) cancelOnTrace.get()?.invoke()
                                return result
                            }
                        }
                    }
                }
            }
        }
        val database = Room.databaseBuilder<SessionDatabase>(path.toString()).setDriver(driver).build()
        val old = graph()
        val next = replace(old, arrow().reposition(NormalizedPoint(0.25f, 0f), t(3)))
        RoomSessionStore(database).use { store ->
            create(store, old)
            val before = contents()
            var propagated = false
            val updating = launch {
                val job = currentCoroutineContext().job
                cancelOnTrace.set { job.cancel() }
                try { store.update(archive(old), archive(next)) }
                catch (error: CancellationException) { propagated = true }
            }
            updating.join()
            cancelOnTrace.set(null)
            assertTrue(propagated, "Cancellation after the SQL INSERT must escape the store.")
            assertEquals(before, contents())
        }
        reopen(old)
    }

    // Fresh construction intentionally bypasses copy's previous-revision checks, as a decoded snapshot could.
    private fun rebuild(s: Session, uuid: String = s.uuid, status: SessionStatus = s.status,
        started: Instant? = s.startedAt, ended: Instant? = s.endedAt, ends: List<End> = s.ends,
        created: Instant = s.createdAt, updated: Instant = s.updatedAt) = Session(uuid, s.date, status,
        started, ended, s.plannedEndCount, s.title, s.targetType, s.distanceM, s.arrowCountMode, s.arrowCountPerEnd,
        s.isIndoor, s.scoringMode, s.bowUuid, s.arrowSetUuid, s.locationName, s.notes, ends, created, updated)

    private fun rows(sql: String, file: Path = path): List<String> = BundledSQLiteDriver().open(file.toString()).use { connection ->
        // This independent diagnostic/DDL connection can briefly contend with Room's connections.
        // Bound the lock wait; constraint/trigger failures still propagate and no assertion is retried.
        connection.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
        connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
        connection.prepare(sql).use { statement -> buildList {
            while (statement.step()) add((0 until statement.getColumnCount()).joinToString("|") { column ->
                if (statement.isNull(column)) "NULL" else statement.getText(column)
            })
        } }
    }
    private fun contents(file: Path = path) = tables.associateWith { rows("SELECT * FROM $it ORDER BY 1, 2", file) }
}
