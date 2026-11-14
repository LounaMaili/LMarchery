package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(reposition, enabled = canReposition, modifier = Modifier.fillMaxWidth()) { Text("Repositionner") }
            OutlinedButton(decide, enabled = canDecide, modifier = Modifier.fillMaxWidth()) { Text("Corriger le score") }
        }
        OutlinedButton(close, enabled = canClose, modifier = Modifier.weight(1f)) {
            Text("Fermer la sélection / annuler la correction")
        }
    }
}
