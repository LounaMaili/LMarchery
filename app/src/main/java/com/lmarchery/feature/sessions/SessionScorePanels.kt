package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.session.Session

@Composable
fun SessionHistoryCard(entry: SessionEntry, enabled: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val session = entry.session
            if (session != null && entry.problem == null) {
                val summary = presentSessionScore(session).summary
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                Text(session.civilDateLabel(), style = MaterialTheme.typography.bodySmall)
                Text("${session.targetType.name} · ${session.distanceM} m")
                Text("${session.status.label()} · ${summary.validatedEndCount} volée(s) validée(s)")
                Text("Score validé : ${summary.totalScore}", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Séance nécessitant une intervention", style = MaterialTheme.typography.titleMedium)
                Text("UUID : ${entry.uuid}")
                Text(entry.problem?.message ?: "Lecture non résolue.", color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = onOpen, enabled = enabled) {
                Text(if (session == null || entry.problem != null) "Réessayer la lecture" else session.openActionLabel())
            }
        }
    }
}

/** A row wraps on narrow displays and with large text; no fixed column widths. */
@Composable
fun EndScoreCard(row: EndScoreRow, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Volée ${row.index} · ${row.stateLabel}", style = MaterialTheme.typography.titleSmall)
            Text(row.arrowsLabel)
            Text(row.totalLabel)
            TextButton(onClick = onSelect, enabled = enabled) {
                Text(if (selected) "Blason affiché · volée ${row.index}" else "Voir le blason · volée ${row.index}")
            }
        }
    }
}

@Composable
fun SessionScorePanel(session: Session) {
    val presentation = presentSessionScore(session)
    val score = presentation.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(presentation.heading, style = MaterialTheme.typography.titleMedium)
            Text("Total validé : ${score.totalScore}")
            Text("Volées validées : ${score.validatedEndCount} · Flèches validées : ${score.validatedImpactCount}")
            Text("${presentation.maximumCountLabel} : ${score.maxScoreCount} · Nombre de 9 : ${score.nineCount}")
            if (presentation.showX) Text("Nombre de X : ${score.xCount}")
            Text("Nombre de M : ${score.missCount}")
            Text("Moyenne par flèche : ${presentation.averagePerArrowLabel}")
            Text("Moyenne par volée : ${presentation.averagePerEndLabel}")
            Text("${presentation.maximumLabel} : ${score.maxPossibleScore}", style = MaterialTheme.typography.bodySmall)
            Text("Les volées en saisie sont exclues du total et des moyennes. Le maximum de référence peut les inclure ; ce n’est pas un pourcentage de réussite.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
