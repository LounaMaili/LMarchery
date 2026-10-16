package com.lmarchery.feature.archiveimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.feature.sessions.SessionUiState

/** Root-level launcher: survives scrolling the panel out of LazyColumn composition. */
@Composable
fun ImportSelectionEffect(coordinator: ImportCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    var wasActive by rememberSaveable { mutableStateOf(false) }
    val interruptedAtEntry = remember { wasActive && coordinator.state.value == ImportState.Available }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        coordinator.selected(it?.toString())
    }
    LaunchedEffect(Unit) { if (interruptedAtEntry) coordinator.reportInterrupted() }
    SideEffect { wasActive = state.active }
    val request = state as? ImportState.Choosing
    LaunchedEffect(request?.requestId) {
        if (coordinator.claimSelection()) {
            // MIME/name are not trust signals. Providers may mislabel valid JSON files.
            try { launcher.launch(arrayOf("*/*")) } catch (_: Exception) { coordinator.pickerUnavailable() }
        }
    }
}

@Composable
fun ImportPanel(coordinator: ImportCoordinator, sessionState: SessionUiState) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Restaurer une sauvegarde JSON", style = MaterialTheme.typography.titleMedium)
            Text("Ajout de séances absentes uniquement. Aucun écrasement, fusion ou suppression.", style = MaterialTheme.typography.bodySmall)
            Text(when (val current = state) {
                ImportState.Available -> "Import disponible depuis un fichier .lmarchery.json."
                is ImportState.Choosing -> "Sélectionnez explicitement votre sauvegarde."
                ImportState.Reading -> "Lecture bornée du fichier…"
                ImportState.Validating -> "Validation du contenu et comparaison avec les données locales…"
                is ImportState.Preview -> if (current.plan.newSessionUuids.isEmpty())
                    "Toutes les séances existent déjà à l’identique. Aucune importation nécessaire."
                    else "Prévisualisation prête. Confirmez pour ajouter uniquement les nouvelles séances."
                ImportState.Confirming -> "Confirmation reçue ; préparation de la transaction…"
                ImportState.Importing -> "Importation en cours… Résultat SQL non encore confirmé."
                is ImportState.Conflict -> current.diagnostic
                is ImportState.Succeeded -> "Import confirmé : ${current.added} séance(s) ajoutée(s), ${current.identical} déjà présente(s) à l’identique." +
                    if (current.refreshFailed) " Actualisation impossible : utilisez Actualiser la liste." else ""
                ImportState.Cancelled -> "Import annulé : aucune écriture demandée."
                is ImportState.Failed -> current.diagnostic
                is ImportState.Unconfirmed -> if (current.mayHaveCommitted)
                    "Importation non confirmée après erreur ou interruption. Relisez la liste puis sélectionnez à nouveau le fichier pour constater les séances identiques. Aucune relance automatique."
                    else "Lecture interrompue. Aucune écriture demandée ; sélectionnez à nouveau le fichier."
            })
            val plan = (state as? ImportState.Preview)?.plan ?: (state as? ImportState.Conflict)?.plan
            if (plan != null) {
                Text("Fichier : ${plan.sessionCount} séance(s), ${plan.newSessionUuids.size} nouvelle(s), ${plan.identicalSessionUuids.size} identique(s), ${plan.conflicts.size} conflit(s), ${plan.profileCount} profil(s).")
                plan.conflicts.take(20).forEach { Text("${it.code} · ${it.uuid ?: "archive"} · ${it.path} : ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                if (plan.conflicts.size > 20) Text("${plan.conflicts.size - 20} autre(s) conflit(s). Aucun import possible.")
            }
            if (state is ImportState.Preview) {
                if (plan!!.canImport) Button(onClick = coordinator::confirm) { Text("Importer les ${plan.newSessionUuids.size} nouvelles séances") }
                TextButton(onClick = coordinator::cancel) { Text("Fermer l’aperçu sans importer") }
            }
            if (state.active && state !is ImportState.Choosing && state !is ImportState.Preview) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (sessionState.busy || sessionState.pending != null || sessionState.impactEdit != null || sessionState.archiveOperationLocked && !state.active)
                Text("Terminez l’opération en cours et résolvez toute sauvegarde ou correction avant d’importer.")
            Button(onClick = coordinator::begin, enabled = !state.active && sessionState.canNavigate) { Text("Importer une sauvegarde") }
        }
    }
}
