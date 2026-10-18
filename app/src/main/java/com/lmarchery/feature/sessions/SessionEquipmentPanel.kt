package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lmarchery.data.EquipmentKind

@Composable
fun EquipmentChoiceFields(choices: EquipmentChoices, selection: EquipmentSelection, enabled: Boolean,
    original: EquipmentSelection = EquipmentSelection(), choose: (EquipmentKind, String?) -> Unit) {
    Text("Matériel facultatif", style = MaterialTheme.typography.titleMedium)
    choices.diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    EquipmentKind.entries.forEach { kind ->
        val selected = if (kind == EquipmentKind.BOW) selection.bowUuid else selection.arrowSetUuid
        val old = if (kind == EquipmentKind.BOW) original.bowUuid else original.arrowSetUuid
        Text(if (kind == EquipmentKind.BOW) "Arc" else "Jeu de flèches")
        OutlinedButton(onClick = { choose(kind, null) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("${if (selected == null) "✓ " else ""}Aucun")
        }
        if (old != null && choices.values(kind).none { it.uuid == old }) {
            OutlinedButton(onClick = { choose(kind, old) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("${if (selected == old) "✓ " else ""}Conserver : ${choices.label(kind, old)}")
            }
        }
        choices.values(kind).forEach { value ->
            OutlinedButton(onClick = { choose(kind, value.uuid) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("${if (selected == value.uuid) "✓ " else ""}${value.name} · ${value.uuid}")
            }
        }
    }
}

@Composable
fun SessionEquipmentPanel(state: SessionUiState, controller: SessionController) {
    val session = state.session ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Matériel associé", style = MaterialTheme.typography.titleMedium)
            equipmentAssociationLines(state.equipmentChoices, EquipmentSelection(session.bowUuid, session.arrowSetUuid)).forEach { Text(it) }
            if (state.equipmentEdit == null) {
                OutlinedButton(onClick = controller::beginEquipmentEdit, enabled = state.canEditEquipment) { Text("Consulter / modifier les associations") }
            } else {
                Text("Métadonnées uniquement : les scores, impacts, décisions et la clôture restent inchangés.")
                EquipmentChoiceFields(state.equipmentChoices, state.equipmentEdit, state.ready,
                    EquipmentSelection(session.bowUuid, session.arrowSetUuid), controller::chooseEquipment)
                Button(onClick = controller::confirmEquipment, enabled = state.ready) { Text("Confirmer les associations") }
                TextButton(onClick = controller::cancelEquipmentEdit, enabled = !state.busy && state.pending == null) { Text("Annuler sans modifier") }
            }
        }
    }
}
