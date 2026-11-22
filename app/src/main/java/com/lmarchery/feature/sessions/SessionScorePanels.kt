package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.session.Session

@Composable
fun SessionHistoryCard(entry: SessionEntry, enabled: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val session = entry.session
            if (session != null && entry.readable) {
                val summary = remember(session) { presentSessionScore(session).summary }
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                Text(session.date.shortLabel(), style = MaterialTheme.typography.bodySmall)
                Text(session.shootingLabel())
                Text(session.status.label())
                Text("Score validé · ${summary.totalScore.pointsLabel()}")
            } else if (entry.configured != null && entry.readable) {
                val configured = entry.configured
                val view = remember(configured) { presentConfiguredScore(configured) }
                Text(configured.metadata.title, style = MaterialTheme.typography.titleMedium)
                Text(configured.metadata.date.shortLabel(), style = MaterialTheme.typography.bodySmall)
                Text(configured.configurationLabel())
                Text(configuredHistoryLabels(configured.status).statusLine)
                view.summary?.let { Text("Score validé · ${it.totals.validated.score.pointsLabel()}") }
                if (view.diagnostic != null) Text("Score indisponible")
            } else {
                Text("Séance nécessitant une intervention", style = MaterialTheme.typography.titleMedium)
                TechnicalDetails(entry.uuid) {
                    Text("UUID · ${entry.uuid}")
                    Text(entry.problem?.message ?: "Lecture non résolue.")
                }
            }
            OutlinedButton(onClick = onOpen, enabled = enabled) {
                Text(if (!entry.readable) "Réessayer la lecture"
                    else if (entry.configured != null) configuredHistoryLabels(entry.configured.status).openActionLabel
                    else session!!.openActionLabel())
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
    val presentation = remember(session) { presentSessionScore(session) }
    val score = presentation.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Statistiques des volées validées", style = MaterialTheme.typography.titleMedium)
            if (score.validatedEndCount == 0) Text("Aucune volée validée.")
            else {
                scoreCountLabels(session.targetType.maxScore, score.maxScoreCount.toLong(), score.nineCount.toLong(),
                    presentation.showX, score.xCount.toLong(), score.missCount.toLong()).forEach { Text(it) }
                Text("Moyenne par flèche · ${presentation.averagePerArrowLabel}")
                Text("Moyenne par volée · ${presentation.averagePerEndLabel}")
            }
            TechnicalDetails("séance-${session.uuid}") {
                Text("Séance · ${session.uuid}")
                Text(session.civilDateLabel())
                Text("Profil ${session.targetType.uuid} · création ${session.createdAt} · révision ${session.updatedAt}")
                Text("${presentation.maximumLabel} · ${score.maxPossibleScore.pointsLabel()}")
                Text("Le maximum de référence peut inclure les volées encore en saisie.")
            }
        }
    }
}
