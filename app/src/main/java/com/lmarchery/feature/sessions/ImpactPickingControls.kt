package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.lmarchery.core.model.session.ArrowImpact

@Composable
fun ImpactPickingControls(
    picking: ImpactPicking?, impacts: List<ArrowImpact>, canBegin: Boolean,
    begin: () -> Unit, cancel: (ImpactPickContext) -> Unit, choose: (ImpactPickContext, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Both slots remain present, even while disabled or a correction is open.
        // Keep all variable messages/candidates BELOW them.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            impactPickingActions(picking, canBegin).forEach { slot ->
                OutlinedButton(onClick = {
                    when (slot.action) {
                        ImpactPickingAction.BEGIN -> begin()
                        ImpactPickingAction.CANCEL -> picking?.let { cancel(it.context) }
                    }
                }, enabled = slot.enabled, modifier = Modifier.weight(1f)) { Text(slot.action.label) }
            }
        }
        if (picking == null) return@Column
        Text("Sélection sur le blason · touchez un marqueur. Aucun impact ne sera ajouté.")
        if (picking.hasTapped && picking.candidates.isEmpty()) Text("Aucune flèche à cet endroit. Touchez un marqueur ou annulez.")
        if (picking.candidates.size > 1) {
            Text("${picking.candidates.size} flèches proches : choisissez le numéro.")
            // FREE can contain many coincident arrows. Keep this non-lazy panel bounded.
            picking.candidates.take(20).forEach { uuid ->
                impacts.find { it.uuid == uuid }?.let { impact ->
                    OutlinedButton(onClick = { choose(picking.context, uuid) }) {
                        Text("Choisir flèche #${impact.index} : ${impact.scoreLabel()}")
                    }
                }
            }
            if (picking.candidates.size > 20) Text("20 choix affichés. Annulez ce mode pour accéder à la liste numérotée complète.")
        }
    }
}

/** Opening commands and abandonment use different columns, including after a message disappears. */
@Composable
fun ImpactEditActions(
    canReposition: Boolean, canDecide: Boolean, canClose: Boolean,
    reposition: () -> Unit, decide: () -> Unit, close: () -> Unit,
    canDrag: Boolean = false, drag: () -> Unit = {},
    canConfirmDrag: Boolean = false, confirmDrag: () -> Unit = {},
    canCancelImpact: Boolean = false, cancelImpact: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(reposition, enabled = canReposition, modifier = Modifier.fillMaxWidth()) { Text("Repositionner") }
            OutlinedButton(drag, enabled = canDrag, modifier = Modifier.fillMaxWidth()) { Text("Déplacer sur le blason") }
            OutlinedButton(decide, enabled = canDecide, modifier = Modifier.fillMaxWidth()) { Text("Corriger le score") }
            OutlinedButton(cancelImpact, enabled = canCancelImpact, modifier = Modifier.fillMaxWidth()) { Text("Supprimer cette flèche") }
        }
        OutlinedButton(close, enabled = canClose, modifier = Modifier.weight(1f)) {
            Text("Fermer la sélection / annuler la correction")
        }
    }
    // This slot stays present and disabled after acknowledgement: a second tap cannot hit a replacement action.
    OutlinedButton(confirmDrag, enabled = canConfirmDrag, modifier = Modifier.fillMaxWidth()) { Text("Confirmer le déplacement") }
    }
}

@Composable
fun ImpactCancellationConfirmation(dialog: ImpactCancellationDialog, busy: Boolean, pending: Boolean,
    diagnostic: String?, canRetry: Boolean, comment: (Long, String) -> Unit, close: (Long) -> Unit,
    confirm: (Long) -> Unit, reconcile: () -> Unit, retry: () -> Unit, abandonDisplay: (Long) -> Unit) {
    val commentProblem = remember(dialog.comment) { cancellationCommentProblem(dialog.comment) }
    val local = dialog.phase == CancellationPhase.LOCAL
    val confirmed = dialog.phase == CancellationPhase.CONFIRMED
    AlertDialog(onDismissRequest = { if (!busy && !pending) close(dialog.token) },
        title = { Text(if (confirmed) "Saisie de la flèche annulée" else "Supprimer cette flèche ?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cette flèche ne comptera plus dans les scores. Sa saisie, ses décisions et ses repositionnements resteront conservés. Cette annulation ne pourra pas être inversée en V1.")
            // Keep the same field and status slots after acknowledgement. A second activation
            // must not encounter a different command because the dialog shrank.
            OutlinedTextField(dialog.comment, { comment(dialog.token, it) }, enabled = local && !busy,
                label = { Text("Commentaire facultatif") }, maxLines = 4, isError = commentProblem != null,
                supportingText = { Text(commentProblem ?: "Commentaire conservé intégralement.", minLines = 2) })
            Text(when (dialog.phase) {
                CancellationPhase.LOCAL -> "Une seule confirmation sera enregistrée. Annuler ferme sans modifier la séance."
                CancellationPhase.SAVING -> "Enregistrement en cours ; attendez l’acquittement."
                CancellationPhase.CONFIRMED -> "Annulation enregistrée. Les autres flèches sont conservées."
                CancellationPhase.FAILED -> "Enregistrement non confirmé. Conservez l’intention et relisez l’état réel."
            }, minLines = 3)
            diagnostic?.let { Text(it) }
            if (dialog.phase == CancellationPhase.FAILED && pending) {
                Text("Intention exacte conservée. Aucune répétition automatique ; fermer ne pourrait pas annuler un commit.")
                OutlinedButton(reconcile, enabled = !busy) { Text("Relire et comparer l’état enregistré") }
                OutlinedButton(retry, enabled = !busy && canRetry) { Text("Confirmer à nouveau la même intention") }
                // This opens the existing explicit navigation warning; it never cancels a SQL commit.
                OutlinedButton({ abandonDisplay(dialog.token) }, enabled = !busy) { Text("Quitter l’affichage de cette intention…") }
            }
        } },
        confirmButton = { TextButton({ confirm(dialog.token) }, enabled = local && !busy && commentProblem == null) {
            Text("Confirmer la suppression")
        } },
        dismissButton = { TextButton({ close(dialog.token) }, enabled = !busy && !pending) { Text(if (local) "Annuler" else "Fermer") } })
}

@Composable
fun ImpactCancellationHistory(end: com.lmarchery.core.model.session.End, regulatoryEdition: String? = null) {
    if (end.cancellations.isEmpty()) return
    Text("Saisies annulées · conservées dans l’historique", style = MaterialTheme.typography.titleSmall)
    end.cancellations.forEach { proof ->
        val impact = end.impacts.single { it.uuid == proof.impactUuid }
        Text("Flèche #${impact.index} · ANNULÉE · ne compte plus. Ancien score : ${impact.scoreLabel()} ; position : ${impact.point}.")
        Text("Profil conservé : ${impact.targetType.name} · édition ${regulatoryEdition ?: "non renseignée"}. Score géométrique : ${impact.scoreCalculated}. Origine : ${impact.inputMode}.", style = MaterialTheme.typography.bodySmall)
        Text("Saisie ${impact.createdAt} · dernière révision de l’impact ${impact.updatedAt}.", style = MaterialTheme.typography.bodySmall)
        Text("${proof.cancelledAt} · Saisie par erreur${proof.comment?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        Text("Décision conservée : ${impact.finalScoreDecision.provenance.kind} · ${impact.finalScoreDecision.provenance.decidedAt ?: "géométrie"}${impact.finalScoreDecision.provenance.reason?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        impact.finalScoreDecision.provenance.boundary?.let { Text("Limite conservée : ${it.type} · rayon ${it.radiusNormalized}", style = MaterialTheme.typography.bodySmall) }
        impact.repositions.forEachIndexed { i, trace ->
            Text("Déplacement ${i + 1} : ${trace.previousPoint} → ${trace.newPoint} · ${trace.changedAt} · ${trace.reason ?: "sans motif"}. Ancienne décision : ${trace.previousDecision.provenance.kind}, ${trace.previousDecision.scoreFinal}.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
