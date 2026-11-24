package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.scoring.FinalScoreDecision
import com.lmarchery.core.model.session.End
import com.lmarchery.core.model.session.EndStatus
import com.lmarchery.core.scoring.configured.*
import com.lmarchery.core.scoring.mixed.calculateConfiguredEndScore

@Composable
fun ConfiguredSessionSummary(session: ConfiguredSession, readOnly: Boolean = true) {
    val view = remember(session) { presentConfiguredScore(session) }
    val configurations = remember(session) { session.effectiveConfigurations() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(session.metadata.title, style = MaterialTheme.typography.titleLarge)
        Text(session.metadata.date.shortLabel())
        Text("${session.status.label()}${if (readOnly) " · Lecture seule" else ""}")
        Text(session.configurationLabel())
        val validated = session.ends.count { it.end.status == EndStatus.VALIDATED }
        val endLabel = if (session.ends.size == 1) "volée" else "volées"
        Text("${session.ends.size} $endLabel, dont $validated ${if (validated == 1) "validée" else "validées"}")
        view.summary?.let { summary ->
            Text("Score validé · ${summary.totals.validated.score.pointsLabel()}", style = MaterialTheme.typography.titleMedium)
            if (summary.totals.provisional.impactCount > 0) {
                Text("Score provisoire · ${summary.totals.provisional.score.pointsLabel()}")
            }
        }
        if (view.diagnostic != null) Text("Score indisponible pour cette séance.", color = MaterialTheme.colorScheme.error)
        if (configurations.size > 1) {
            Text("Les totaux additionnent des points obtenus avec des réglages différents.",
                style = MaterialTheme.typography.bodySmall)
        }
        session.metadata.locationName?.let { Text("Note de lieu · $it") }
        session.metadata.notes?.let { Text(it) }
        TechnicalDetails("séance-${session.uuid}") {
            Text("Séance · ${session.uuid}")
            Text(session.civilDateLabel())
            Text("Création ${session.createdAt} · révision ${session.updatedAt}")
            Text("Configuration initiale · ${session.initialConfigurationOrigin}")
            Text(session.initialConfiguration.shootingLabel())
            Text(session.initialConfiguration.profileDetails())
            Text(session.initialConfiguration.capacityLabel())
            session.metadata.plannedEndCount?.let { Text("Volées prévues · $it") }
            Text("Arc · ${session.metadata.bowUuid ?: "aucun"}")
            Text("Jeu de flèches · ${session.metadata.arrowSetUuid ?: "aucun"}")
            view.diagnostic?.let { Text(it) }
            view.summary?.groups?.forEach { group ->
                HorizontalDivider()
                if (configurations.size > 1 || group.configuration != session.initialConfiguration) {
                    Text(group.configuration.shootingLabel())
                    Text(group.configuration.profileDetails())
                    Text(group.configuration.capacityLabel())
                }
                val totals = group.totals.recorded
                if (configurations.size > 1) {
                    Text("Validé · ${group.totals.validated.score.pointsLabel()}. Provisoire · ${group.totals.provisional.score.pointsLabel()}.")
                }
                Text("Flèches comptabilisées · ${totals.impactCount}")
                scoreCountLabels(group.configuration.targetType.maxScore, totals.maxScoreCount, totals.nineCount,
                    group.configuration.targetType.xRingEnabled, totals.xCount, totals.missCount).forEach { Text(it) }
                Text("Maximum des flèches présentes · ${totals.recordedArrowMaximum.pointsLabel()}")
                if (totals.recordedCapacityMaximum != totals.recordedArrowMaximum) {
                    Text("Maximum selon la capacité des volées présentes · ${totals.recordedCapacityMaximum.pointsLabel()}")
                }
            }
        }
    }
}

@Composable
fun ConfiguredEndCard(owner: ConfiguredSession, captured: ConfiguredEnd, actions: @Composable ColumnScope.() -> Unit = {}) {
    val totals = remember(owner, captured) {
        calculateConfiguredEndScore(configuredEndScoringContext(owner, captured)).totals
    }
    val end = captured.end
    val cancelled = remember(end) { end.cancellations.mapTo(hashSetOf()) { it.impactUuid } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Volée ${end.index} · ${end.status.label()}", style = MaterialTheme.typography.titleMedium)
            Text(captured.configuration.shootingLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(captured.configuration.capacityLabel(), style = MaterialTheme.typography.bodySmall)
            Text(if (end.activeImpacts.isEmpty()) "Aucune flèche comptabilisée" else totals.score.pointsLabel(),
                style = MaterialTheme.typography.titleSmall)
            end.impacts.forEach { impact ->
                val isCancelled = impact.uuid in cancelled
                val label = if (isCancelled) "annulée · hors score" else impact.scoreDescription()
                val correction = if (!isCancelled && impact.finalScoreDecision.provenance.isManual) " · score décidé manuellement" else ""
                Text("Flèche ${impact.index} · $label$correction", style = MaterialTheme.typography.bodyMedium)
            }
            end.notes?.let { Text(it) }
            actions()
            TechnicalDetails(end.uuid) {
                Text("Volée ${end.uuid} · séance ${captured.sessionUuid}")
                Text("Création ${end.createdAt} · révision ${end.updatedAt}")
                Text(captured.configuration.profileDetails())
                Text(captured.origin.label())
                ImpactTechnicalContent(end)
            }
        }
    }
}

/** Used by the historical editor alongside the already visible arrow scores. */
@Composable
internal fun EndImpactHistory(end: End, regulatoryEdition: String?) {
    if (end.impacts.isEmpty()) return
    end.cancellations.forEach { proof ->
        val impact = end.impacts.single { it.uuid == proof.impactUuid }
        Text("Flèche ${impact.index} annulée · hors score", style = MaterialTheme.typography.bodyMedium)
    }
    TechnicalDetails(end.uuid) {
        Text("Volée ${end.uuid} · création ${end.createdAt} · révision ${end.updatedAt}")
        Text("Édition du profil · ${regulatoryEdition ?: "non renseignée"}")
        ImpactTechnicalContent(end)
    }
}

@Composable
internal fun TechnicalDetails(key: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    OutlinedButton({ expanded = !expanded }) {
        Text(if (expanded) "Masquer les informations techniques" else "Informations techniques")
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun ImpactTechnicalContent(end: End) {
    val cancelled = end.cancellations.associateBy { it.impactUuid }
    end.impacts.forEach { impact ->
        HorizontalDivider()
        Text("Flèche ${impact.index} · ${impact.uuid}")
        Text("Profil ${impact.targetType.uuid} · position ${impact.point} · origine ${impact.inputMode}")
        Text("Création ${impact.createdAt} · révision ${impact.updatedAt}")
        impact.note?.let { Text(it) }
        Text("Décision conservée · ${impact.finalScoreDecision.auditLabel()}")
        cancelled[impact.uuid]?.let { proof ->
            Text("Annulation ${proof.cancelledAt} · ${proof.reason}${proof.comment?.let { " · $it" }.orEmpty()}")
        }
        impact.repositions.forEachIndexed { index, trace ->
            Text("Déplacement ${index + 1} · ${trace.previousPoint} → ${trace.newPoint} · ${trace.changedAt}")
            trace.reason?.let { Text("Motif · $it") }
            Text("Ancienne révision · ${trace.previousUpdatedAt}")
            Text("Décision précédente · ${trace.previousDecision.auditLabel()}")
        }
    }
}

private fun FinalScoreDecision.auditLabel(): String = buildList {
    add("Score calculé $scoreCalculated${if (geometricValue.isX) " (X)" else ""}")
    add("Score final $scoreFinal${if (isX) " (X)" else ""}")
    add(provenance.kind.name)
    provenance.decidedAt?.let { add(it.toString()) }
    provenance.reason?.let { add(it) }
    provenance.boundary?.let { add("Limite ${it.type} · rayon ${it.radiusNormalized}") }
}.joinToString(" · ")
