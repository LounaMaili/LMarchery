package com.lmarchery.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.model.equipment.ArrowSet
import com.lmarchery.core.model.equipment.Bow
import com.lmarchery.core.model.scoring.*
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.time.SessionDateTime
import com.lmarchery.core.scoring.archive.*
import com.lmarchery.core.scoring.fixtures.ClassicTargetFixtures
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertIs

internal object EquipmentTestData {
    val now: Instant = Instant.parse("2026-10-17T10:00:00.123456789Z")
    fun id(n: Int) = "70000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    fun bow(n: Int = 800) = Bow(id(n), " Synthetic bow 🏹 ", "custom", Float.MIN_VALUE,
        "", " modèle ", "Synthetic notes\n", now, now)
    fun arrows(n: Int = 801) = ArrowSet(id(n), "Synthetic arrows", "500 / 1916", "28 1/2 pouces", "100 gr?",
        "", " ", "carbone/alu", "Synthetic notes\n", now, now)
    val oldTables = listOf("entity_identities", "target_profiles", "target_zones", "sessions", "ends", "impacts",
        "score_decisions", "impact_repositions")
    val tables = oldTables + listOf("bows", "arrow_sets")

    fun emptySession(target: com.lmarchery.core.model.target.TargetType = ClassicTargetFixtures.classic40) =
        Session(id(1), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")), SessionStatus.IN_PROGRESS,
            startedAt = now, title = "Synthetic empty session", targetType = target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
            createdAt = now, updatedAt = now.plusSeconds(9))

    fun session(seed: Int = 0, completed: Boolean = false): Session {
        val target = ClassicTargetFixtures.classic40
        val boundary = target.scoreBoundaries().first { it.reference.type == BoundaryType.SCORE_ZONES }
        fun arrow(n: Int, radius: Float) = ArrowImpact(id(seed + 10 + n), n, NormalizedPoint(radius, -0.0f),
            inputMode = InputMode.AUTOMATIC, note = "Synthetic impact", createdAt = now.plusSeconds(2),
            updatedAt = now.plusSeconds(2), targetType = target)
        val judged = arrow(1, .105f)
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.inner, now.plusSeconds(3)), now.plusSeconds(3))
            .reposition(NormalizedPoint(.25f, -0.0f), now.plusSeconds(4), "First correction")
            .withFinalScore(target, ScoreDecisionRequest.ExceptionalCorrection(BoundaryValue(5), "Observed five", now.plusSeconds(5)), now.plusSeconds(5))
            .reposition(NormalizedPoint(.15f, -0.0f), now.plusSeconds(6), "Second correction")
            .withFinalScore(target, ScoreDecisionRequest.JudgeBoundary(boundary.reference, boundary.outer, now.plusSeconds(7)), now.plusSeconds(7))
        val end = End(id(seed + 2), 1, EndStatus.EDITING, listOf(judged, arrow(2, 0f), arrow(3, 1.1f)),
            "Synthetic end", now.plusSeconds(1), now.plusSeconds(7))
        val value = Session(id(seed + 1), SessionDateTime.fromInstant(now, ZoneId.of("Europe/Paris")),
            SessionStatus.IN_PROGRESS, startedAt = now, title = "Synthetic session", targetType = target, distanceM = 18,
            arrowCountMode = ArrowCountMode.FREE, isIndoor = true, scoringMode = ScoringMode.COMPETITION,
            bowUuid = id(800), arrowSetUuid = id(801), notes = "Synthetic archive", ends = listOf(if (completed) end.validate(now.plusSeconds(8)) else end),
            createdAt = now, updatedAt = now.plusSeconds(9))
        return if (completed) value.complete(now.plusSeconds(10)) else value
    }
    fun archive(session: Session) = assertIs<ArchiveReconstruction.Accepted>(reconstructArchive(snapshotArchive(
        listOf(TargetProfileSnapshot(session.targetType, null)), listOf(session)))).archive
    fun rows(path: Path, sql: String): List<String> = BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA foreign_keys = ON").use { it.step() }
        connection.prepare("PRAGMA busy_timeout = 5000").use { it.step() }
        connection.prepare(sql).use { statement -> buildList {
            while (statement.step()) add((0 until statement.getColumnCount()).joinToString("|") {
                if (statement.isNull(it)) "<null>" else statement.getText(it)
            })
        } }
    }
    fun contents(path: Path, tables: List<String> = this.tables) = tables.associateWith { rows(path, "SELECT * FROM $it ORDER BY 1,2") }
    fun denyWrites(path: Path, tables: List<String> = this.tables) {
        tables.forEach { table -> listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            rows(path, "CREATE TRIGGER guard_${table}_$operation BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'read only'); END")
        } }
    }
}
