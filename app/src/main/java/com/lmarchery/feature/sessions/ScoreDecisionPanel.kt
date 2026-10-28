package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.scoring.BoundaryHint
import com.lmarchery.core.model.scoring.BoundaryType
import com.lmarchery.core.model.scoring.BoundaryValue

@Composable
fun ScoreDecisionPanel(state: SessionUiState, controller: SessionController) {
    val view = ScoreDecisionView(state.selectedImpact ?: return, state.impactEdit ?: return,
        state.scoreOptions ?: return, state.selectedBoundary, state.proposedScore, state.canProposeScoreDecision, state.canConfirmScoreDecision)
    ScoreDecisionPanel(view, ScoreDecisionActions(controller::selectBoundary, controller::selectScoreValue,
        controller::showOtherBoundaries, controller::chooseExceptionalCorrection, controller::chooseBoundaryJudgment,
        controller::setExceptionalReason, controller::confirmScoreDecision))
}

data class ScoreDecisionView(val impact: com.lmarchery.core.model.session.ArrowImpact, val edit: ImpactEdit,
    val options: ScoreDecisionOptions, val selectedBoundary: BoundaryHint?, val proposedScore: BoundaryValue?,
    val enabled: Boolean, val canConfirm: Boolean)
data class ScoreDecisionActions(val boundary: (com.lmarchery.core.model.scoring.BoundaryReference) -> Unit,
    val value: (BoundaryValue) -> Unit, val other: () -> Unit, val exceptional: () -> Unit,
    val judgment: () -> Unit, val reason: (String) -> Unit, val confirm: () -> Unit)

/** Shared fields; callers supply the exact selected impact/profile and their own guarded callbacks. */
@Composable
fun ScoreDecisionPanel(view: ScoreDecisionView, actions: ScoreDecisionActions) {
    val edit = view.edit
    val impact = view.impact
    val options = view.options
    val enabled = view.enabled
    Text("Score géométrique : ${impact.finalScoreDecision.geometricValue.label()}")
    Text("Score final enregistré : ${impact.finalScoreDecision.value.label()}")
    Text("Valeur proposée : ${view.proposedScore?.label() ?: "aucun choix"} · non enregistrée")
    Text("La position reste inchangée. La proximité tactile ne prouve aucun contact du tube et n’attribue aucun score.",
        style = MaterialTheme.typography.bodySmall)
    when (val proposal = edit.proposal) {
        is ImpactProposal.BoundaryJudgment -> {
            Text("Jugement de cordon", style = MaterialTheme.typography.titleMedium)
            Text("Choisissez explicitement la limite, puis le côté constaté.")
            if (options.nearby.isEmpty()) Text("Aucune limite proche suggérée.")
            else {
                Text("Limites proches suggérées (aide tactile uniquement)")
                options.nearby.forEach { hint -> BoundaryChoice(hint, proposal, enabled, actions) }
            }
            if (!proposal.showOtherBoundaries) {
                TextButton(onClick = actions.other, enabled = enabled) { Text("Choisir une autre limite réelle du profil") }
            } else {
                Text("Autres limites du profil — jugement possible hors tolérance tactile")
                options.other.forEach { hint -> BoundaryChoice(hint, proposal, enabled, actions) }
            }
            view.selectedBoundary?.let { boundary ->
                OutlinedButton(onClick = { actions.value(boundary.inner) }, enabled = enabled) {
                    Text("${if (proposal.selected == boundary.inner) "✓ " else ""}Côté intérieur : ${boundary.inner.label()}")
                }
                OutlinedButton(onClick = { actions.value(boundary.outer) }, enabled = enabled) {
                    Text("${if (proposal.selected == boundary.outer) "✓ " else ""}Côté extérieur : ${boundary.outer.label()}")
                }
            }
            TextButton(onClick = actions.exceptional, enabled = enabled) { Text("Correction exceptionnelle") }
        }
        is ImpactProposal.ExceptionalCorrection -> {
            Text("Correction exceptionnelle", style = MaterialTheme.typography.titleMedium)
            Text("Choisissez une valeur légale et expliquez la correction. Ce choix ne constitue pas un jugement de trait.")
            // Wrap on small screens instead of squeezing all values into one row.
            options.legalValues.chunked(4).forEach { values ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    values.forEach { value ->
                        OutlinedButton(onClick = { actions.value(value) }, enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("${if (proposal.selected == value) "✓ " else ""}${value.label()}")
                        }
                    }
                }
            }
            OutlinedTextField(proposal.reason, actions.reason, enabled = enabled,
                label = { Text("Motif obligatoire de la correction exceptionnelle") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = actions.judgment, enabled = enabled) { Text("Revenir au jugement de cordon") }
        }
        else -> return
    }
    Button(onClick = actions.confirm, enabled = view.canConfirm) { Text("Confirmer la décision de score") }
}

@Composable
private fun BoundaryChoice(hint: BoundaryHint, proposal: ImpactProposal.BoundaryJudgment,
    enabled: Boolean, actions: ScoreDecisionActions) {
    OutlinedButton(onClick = { actions.boundary(hint.reference) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Text("${if (proposal.boundary == hint.reference) "✓ " else ""}Limite ${hint.inner.label()} / ${hint.outer.label()}")
            Text("${when (hint.type) {
                BoundaryType.SCORE_ZONES -> "Zones de score"
                BoundaryType.X_RING -> "X / non-X"
                BoundaryType.TARGET_EDGE -> "Bord de cible / M"
            }} · r = ${hint.radiusNormalized}")
            Text("Écart radial normalisé : ${hint.distanceToBoundaryNormalized}")
        }
    }
}

private fun BoundaryValue.label() = when {
    isMiss -> "M (0)"
    isX -> "${score}X"
    else -> score.toString()
}
