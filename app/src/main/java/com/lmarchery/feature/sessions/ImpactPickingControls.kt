package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.lmarchery.core.model.session.ArrowImpact

@Composable
fun ImpactPickingControls(
    picking: ImpactPicking?, impacts: List<ArrowImpact>, canBegin: Boolean,
    begin: () -> Unit, cancel: (ImpactPickContext) -> Unit, choose: (ImpactPickContext, String) -> Unit,
) {
    if (picking == null) {
        if (canBegin) OutlinedButton(onClick = begin) { Text("Sélectionner sur le blason") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        TextButton(onClick = { cancel(picking.context) }) { Text("Annuler la sélection sur le blason") }
    }
}
