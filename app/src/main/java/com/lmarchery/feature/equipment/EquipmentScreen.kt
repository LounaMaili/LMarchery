package com.lmarchery.feature.equipment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.core.model.equipment.Equipment
import com.lmarchery.data.EquipmentKind

@Composable
fun EquipmentScreen(controller: EquipmentController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = {
        if (!state.busy) when (state.page) {
            EquipmentPage.LIST -> controller.close()
            EquipmentPage.DETAIL -> controller.list()
            EquipmentPage.FORM -> discard = true
        }
    }
    BackHandler(onBack = back)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Matériel", style = MaterialTheme.typography.headlineMedium)
            Text("Catalogue local. Utilisez la sauvegarde complète V2 pour conserver les fiches.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.diagnostic?.let {
                Text(when (state.problem) {
                    EquipmentProblem.CONFLICT -> "Conflit de révision. $it"
                    EquipmentProblem.INTERVENTION -> "Intervention nécessaire. $it"
                    else -> it
                }, color = MaterialTheme.colorScheme.error)
            }
            state.confirmation?.let { Text(it) }
            if (state.pending != null) {
                Text("Action non confirmée. La proposition et son état attendu sont conservés ; ils ne sont pas remplacés par la version relue.")
                if (state.problem == EquipmentProblem.TECHNICAL)
                    OutlinedButton(onClick = controller::retry, enabled = !state.busy) { Text("Réessayer exactement la même proposition") }
                OutlinedButton(onClick = controller::inspectStored, enabled = !state.busy) { Text("Consulter la version enregistrée") }
                state.storedDiagnostic?.let { Text("Relecture : $it", color = MaterialTheme.colorScheme.error) }
                state.stored?.let { EquipmentDetails(it, "Version relue — proposition locale conservée") }
            }
            when (state.page) {
                EquipmentPage.LIST -> {
                    OutlinedButton(onClick = controller::refresh, enabled = state.editable) { Text("Actualiser le catalogue") }
                    EquipmentKind.entries.forEach { kind ->
                        Text(kind.label(), style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { controller.add(kind) }, enabled = state.editable) { Text(if (kind == EquipmentKind.BOW) "Ajouter un arc" else "Ajouter un jeu de flèches") }
                        val entries = state.entries.filter { it.kind == kind }
                        if (entries.isEmpty() && !state.busy && state.problem == null) Text("Aucune fiche.")
                        entries.forEach { entry ->
                            OutlinedButton(onClick = { controller.show(kind, entry.uuid) }, enabled = state.editable, modifier = Modifier.fillMaxWidth()) {
                                Column { Text(entry.value?.name ?: "Fiche illisible")
                                    Text(entry.uuid, style = MaterialTheme.typography.bodySmall)
                                    entry.diagnostic?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                    TextButton(onClick = back, enabled = !state.busy) { Text("Retour aux séances") }
                }
                EquipmentPage.DETAIL -> {
                    state.original?.let { EquipmentDetails(it, "Fiche confirmée") }
                    Button(onClick = controller::edit, enabled = state.editable) { Text("Modifier la fiche") }
                    TextButton(onClick = back, enabled = !state.busy) { Text("Retour au catalogue") }
                }
                EquipmentPage.FORM -> {
                    state.draft?.let { draft ->
                        Text(if (state.original == null) "Nouvelle fiche" else "Modifier la fiche", style = MaterialTheme.typography.titleLarge)
                        Text("Les textes sont conservés tels quels. Pour les champs facultatifs, décocher signifie non renseigné ; un texte coché vide reste vide. Puissance : nombre avec point décimal.", style = MaterialTheme.typography.bodySmall)
                        draft.fields.forEach { field ->
                            val value = draft.value(field)
                            if (field != EquipmentField.NAME) Row {
                                Checkbox(checked = value != null, enabled = state.editable,
                                    onCheckedChange = { controller.setField(field, if (it) "" else null) })
                                Text("Renseigner : ${field.label} (facultatif)", modifier = Modifier.padding(top = 12.dp))
                            }
                            OutlinedTextField(value = value.orEmpty(), onValueChange = { controller.setField(field, it) },
                                enabled = state.editable && (field == EquipmentField.NAME || value != null),
                                label = { Text(field.label) }, singleLine = field != EquipmentField.NOTES,
                                minLines = if (field == EquipmentField.NOTES) 3 else 1, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Button(onClick = controller::save, enabled = state.editable) { Text("Confirmer l’enregistrement") }
                    TextButton(onClick = back, enabled = !state.busy) { Text("Abandonner la saisie") }
                    Text("La saisie est conservée pendant une rotation, pas après l’arrêt complet du processus.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text("Abandonner la proposition locale ?") },
        text = { Text("Aucune donnée confirmée ne sera supprimée. Une écriture non confirmée a pu être enregistrée ; relisez le catalogue pour le constater.") },
        confirmButton = { TextButton(onClick = { discard = false; controller.discardAndReload() }, enabled = !state.busy) { Text("Abandonner et relire") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Conserver la saisie") } })
}

@Composable
private fun EquipmentDetails(value: Equipment, heading: String) {
    Text(heading, style = MaterialTheme.typography.titleMedium)
    Text("UUID : ${value.uuid}", style = MaterialTheme.typography.bodySmall)
    val draft = EquipmentDraft.from(value)
    draft.fields.forEach { field -> Text("${field.label} : ${draft.value(field) ?: "Non renseigné"}" +
        if (draft.value(field) == "") " (chaîne vide)" else "") }
    Text("Création : ${value.createdAt}\nRévision : ${value.updatedAt}", style = MaterialTheme.typography.bodySmall)
}
