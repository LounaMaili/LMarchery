package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.mixed.calculateConfiguredEndScore
import com.lmarchery.feature.scoring.TargetCanvas
import com.lmarchery.feature.scoring.TargetInteraction

@Composable
fun ConfiguredSessionSummary(session: ConfiguredSession) {
    val view = presentConfiguredScore(session)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(session.metadata.title, style = MaterialTheme.typography.titleLarge)
        Text("${session.status.label()} · séance configurable · lecture seule")
        Text(session.civilDateLabel())
        Text("Aucune édition, correction, validation ou association de matériel n’est disponible pour cette séance dans cet écran.")
        Text("Configuration initiale · ${session.initialConfigurationOrigin}")
        configurationLines(session.initialConfiguration).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Les configurations effectives sont celles de chaque volée. Une distance personnalisée ne constitue pas un règlement officiel.")
        view.diagnostic?.let { Text("Calcul indisponible : $it", color = MaterialTheme.colorScheme.error) }
        view.summary?.let { summary ->
            Text("Somme des scores validés : ${summary.totals.validated.score}", style = MaterialTheme.typography.titleMedium)
            Text("Somme provisoire : ${summary.totals.provisional.score} · ${summary.homogeneity}")
            Text("Sommes techniques ; aucune comparabilité réglementaire entre configurations différentes.")
            summary.groups.forEach { group ->
                configurationLines(group.configuration).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Validé : ${group.totals.validated.score} · provisoire : ${group.totals.provisional.score}")
                Text("Flèches enregistrées : ${group.totals.recorded.impactCount} · maxima : ${group.totals.recorded.maxScoreCount} · 9 : ${group.totals.recorded.nineCount} · X : ${group.totals.recorded.xCount} · M : ${group.totals.recorded.missCount}")
                Text("Maximum des capacités enregistrées : ${group.totals.recorded.recordedCapacityMaximum} ; maximum des flèches présentes : ${group.totals.recorded.recordedArrowMaximum}")
            }
            Text("FIXED : capacité déclarée par volée enregistrée. FREE : seules les flèches présentes comptent. Aucun maximum planifié ni pourcentage réglementaire déduit.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Arc (référence conservée) : ${session.metadata.bowUuid ?: "aucun"}")
        Text("Flèches (référence conservée) : ${session.metadata.arrowSetUuid ?: "aucun"}")
        session.metadata.locationName?.let { Text("Lieu : $it") }
        session.metadata.notes?.let { Text("Notes : $it") }
        Text("UUID : ${session.uuid} · création : ${session.createdAt} · révision : ${session.updatedAt}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ConfiguredEndCard(owner: ConfiguredSession, captured: ConfiguredEnd) {
    // Ownership and effective target are checked by the same pure adapter as PR35.
    val totals = calculateConfiguredEndScore(configuredEndScoringContext(owner, captured)).totals
    val end = captured.end
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Volée ${end.index} · ${end.status} · lecture seule", style = MaterialTheme.typography.titleMedium)
            configurationLines(captured.configuration).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(captured.origin.label(), style = MaterialTheme.typography.bodySmall)
            TargetCanvas(captured.configuration.targetType, end.impacts, TargetInteraction.READ_ONLY,
                end.uuid, {}, Modifier.widthIn(max = 480.dp).fillMaxWidth().aspectRatio(1f))
            if (end.impacts.isEmpty()) Text("Vide · aucune flèche · total : —")
            else Text("Total ${if (end.status == com.lmarchery.core.model.session.EndStatus.VALIDATED) "validé" else "provisoire"} : ${totals.score}")
            end.impacts.forEach { impact ->
                Text("#${impact.index} : ${impact.scoreLabel()} · géométrie ${impact.scoreCalculated}${if (impact.finalScoreDecision.geometricValue.isX) "X" else ""} · position ${impact.inputMode}")
                Text("Décision conservée : ${impact.finalScoreDecision.provenance}", style = MaterialTheme.typography.bodySmall)
                Text("${impact.uuid} · position (${impact.point.x}, ${impact.point.y}) · révision ${impact.updatedAt}", style = MaterialTheme.typography.bodySmall)
                impact.repositions.forEach { trace ->
                    Text("Repositionnement : ${trace.previousPoint} → ${trace.newPoint} · ${trace.changedAt} · motif : ${trace.reason ?: "non fourni"}", style = MaterialTheme.typography.bodySmall)
                    trace.invalidatedManualDecision?.let { decision ->
                        Text("Décision manuelle invalidée : ${decision.scoreFinal}${if (decision.isX) "X" else ""} · ${decision.provenance}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            end.notes?.let { Text("Notes : $it") }
            Text("UUID : ${end.uuid} · propriétaire : ${captured.sessionUuid}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
