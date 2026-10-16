package com.lmarchery.feature.sessions

import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.SessionScoreSummary
import com.lmarchery.core.scoring.calculateEndScore
import com.lmarchery.core.scoring.summarizeSessionScore
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Read-only views of acknowledged domain values; never write or normalize a session. */
data class SessionHistory(
    val resumable: List<SessionEntry>,
    val completed: List<SessionEntry>,
    val intervention: List<SessionEntry>,
)

fun presentSessionHistory(entries: List<SessionEntry>): SessionHistory {
    val readable = entries.filter { it.session != null && it.problem == null }
        .sortedWith(compareByDescending<SessionEntry> { it.session!!.date.toInstant() }.thenBy { it.uuid })
    return SessionHistory(
        readable.filter { it.session!!.status != SessionStatus.COMPLETED },
        readable.filter { it.session!!.status == SessionStatus.COMPLETED },
        entries.filter { it.session == null || it.problem != null }.sortedBy { it.uuid },
    )
}

fun SessionStatus.label(): String = when (this) {
    SessionStatus.DRAFT -> "Brouillon"
    SessionStatus.IN_PROGRESS -> "En cours"
    SessionStatus.COMPLETED -> "Terminée"
}

fun Session.civilDateLabel(): String =
    "${date.localDateTime.format(DateTimeFormatter.ofPattern("d MMM uuuu 'à' HH:mm", Locale.FRANCE))} · ${date.zoneId.id} (${date.offset})"

fun Session.openActionLabel(): String =
    if (status != SessionStatus.COMPLETED && supportsSessionEditing()) "Reprendre" else "Consulter"

fun Session.supportsSessionEditing(): Boolean =
    IndoorProfile.entries.any { it.target == targetType } && arrowCountMode == ArrowCountMode.FIXED &&
        arrowCountPerEnd == 3 && scoringMode == ScoringMode.TRAINING &&
        ends.count { it.status == EndStatus.EDITING } <= 1 &&
        ends.all { end -> end.impacts.size <= 3 && end.impacts.map { it.index }.sorted() == (1..end.impacts.size).toList() }

fun ArrowImpact.scoreLabel(): String = when {
    isMiss -> "M"
    isX -> "${scoreFinal}X"
    else -> scoreFinal.toString()
}

data class EndScoreRow(
    val uuid: String,
    val index: Int,
    val status: EndStatus,
    val arrows: List<String>,
    /** Empty ends have no displayed total, even though calculateEndScore(empty) is zero. */
    val total: Int?,
) {
    val stateLabel get() = if (status == EndStatus.VALIDATED) "VALIDATED · Validée" else "EDITING · Provisoire"
    val arrowsLabel get() = arrows.joinToString(" · ").ifEmpty { "Vide · aucune flèche" }
    val totalLabel get() = total?.let { "Total : $it (${if (status == EndStatus.VALIDATED) "validé" else "provisoire"})" }
        ?: "Total : — (volée vide)"
}

fun presentEndScore(end: End): EndScoreRow = EndScoreRow(end.uuid, end.index, end.status,
    end.impacts.map { "#${it.index} : ${it.scoreLabel()}" },
    end.impacts.takeIf { it.isNotEmpty() }?.let { calculateEndScore(end) })

data class SessionScorePresentation(
    val summary: SessionScoreSummary,
    val heading: String,
    val maximumLabel: String,
    val maximumCountLabel: String,
    val showX: Boolean,
) {
    val averagePerArrowLabel get() = summary.averagePerArrow.averageLabel()
    val averagePerEndLabel get() = summary.averagePerEnd.averageLabel()
}

fun presentSessionScore(session: Session): SessionScorePresentation = SessionScorePresentation(
    summary = summarizeSessionScore(session),
    heading = if (session.status == SessionStatus.COMPLETED) "Bilan de la séance terminée · scores validés" else "Résumé en cours · scores validés",
    maximumLabel = when {
        session.arrowCountMode == ArrowCountMode.FREE -> "Maximum de référence (flèches enregistrées)"
        session.plannedEndCount != null -> "Maximum de référence (${session.plannedEndCount} volées prévues)"
        else -> "Maximum de référence (${session.ends.size} volées enregistrées, même vides ou provisoires)"
    },
    maximumCountLabel = "Nombre de ${session.targetType.maxScore}",
    showX = session.targetType.xRingEnabled,
)

private fun Double?.averageLabel(): String = this?.let { String.format(Locale.FRANCE, "%.2f", it) }
    ?: "Indisponible · aucune donnée validée"
