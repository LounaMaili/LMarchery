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
    var pendingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val interruptedAtEntry = remember { wasActive && coordinator.state.value == ImportState.Available }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        val ticket = pendingRequestId
        pendingRequestId = null
        coordinator.selected(ticket, it?.toString())
    }
    LaunchedEffect(Unit) { if (interruptedAtEntry) coordinator.reportInterrupted() }
    SideEffect { wasActive = state.active }
    val request = state as? ImportState.Choosing
    LaunchedEffect(request?.requestId) {
        if (coordinator.claimSelection()) {
            // MIME/name are not trust signals. Providers may mislabel valid JSON files.
            val ticket = request?.requestId
            if (pendingRequestId != null) coordinator.pickerUnavailable(ticket)
            else {
                pendingRequestId = ticket
                try { launcher.launch(arrayOf("*/*")) } catch (_: Exception) { pendingRequestId = null; coordinator.pickerUnavailable(ticket) }
            }
        }
    }
}

@Composable
fun ImportPanel(coordinator: ImportCoordinator, sessionState: SessionUiState) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    var confirmDismiss by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Restaurer une sauvegarde JSON", style = MaterialTheme.typography.titleMedium)
            Text("V1 : séances et profils associés ; matériel non fourni. V2 : séances historiques, profils et matériel. V3 : configurations exactes par volée, historiques et matériel. Les séances configurables restaurées utilisent leur éditeur dédié lorsque leur statut l’autorise ; les séances terminées restent en lecture seule. Aucun écrasement, fusion ou suppression.", style = MaterialTheme.typography.bodySmall)
            Text(when (val current = state) {
                ImportState.Available -> "Import disponible depuis un fichier .lmarchery.json."
                is ImportState.Choosing -> "Sélectionnez explicitement votre sauvegarde."
                ImportState.Reading -> "Lecture bornée du fichier…"
                ImportState.Validating -> "Validation du contenu et comparaison avec les données locales…"
                is ImportState.Preview -> if (current.plan.newSessionUuids.isEmpty())
                    "Toutes les séances existent déjà à l’identique. Aucune importation nécessaire."
                    else "Prévisualisation prête. Confirmez pour ajouter uniquement les nouvelles séances."
                is ImportState.PreviewV2 -> when {
                    current.plan.isEmptyArchive -> "Archive V2 vide explicitement déclarée. La confirmation vérifie l’état local sans écriture."
                    !current.plan.hasAdditions -> "V2 : tous les éléments existent à l’identique. La confirmation vérifie l’état local sans écriture."
                    else -> "Aperçu V2 prêt. Confirmez l’ajout atomique des éléments absents."
                }
                is ImportState.PreviewV3 -> when {
                    current.plan.isEmptyArchive -> "Archive V3 vide explicite : vérification sans effacement."
                    !current.plan.hasAdditions -> "V3 déjà présente à l’identique : confirmation sans écriture."
                    else -> "Aperçu V3 prêt : ajout atomique de tous les éléments absents."
                }
                is ImportState.ConflictV3 -> current.diagnostic
                is ImportState.SucceededV3 -> v3SuccessText(current)
                is ImportState.UnconfirmedV3 -> current.diagnostic + " Aucune relance automatique. Le candidat exact est conservé ; relisez explicitement l’état avant toute nouvelle confirmation."
                is ImportState.ConflictV2 -> current.diagnostic
                is ImportState.SucceededV2 -> v2SuccessText(current)
                ImportState.Confirming -> "Confirmation reçue ; préparation de la transaction…"
                ImportState.Importing -> "Importation en cours… Résultat SQL non encore confirmé."
                is ImportState.Conflict -> current.diagnostic
                is ImportState.Succeeded -> "Import confirmé : ${current.added} séance(s) ajoutée(s), ${current.identical} déjà présente(s) à l’identique." +
                    if (current.refreshFailed) " Actualisation impossible : utilisez Actualiser la liste." else ""
                ImportState.Cancelled -> "Sélection ou aperçu fermé : aucune nouvelle écriture demandée. Cela n’annule aucune transaction antérieure."
                is ImportState.Failed -> current.diagnostic
                is ImportState.Unconfirmed -> if (current.mayHaveCommitted)
                    "Importation non confirmée après erreur ou interruption. Relisez la liste puis sélectionnez à nouveau le fichier pour constater les éléments identiques. Aucune relance automatique."
                    else "Lecture interrompue. Aucune écriture demandée ; sélectionnez à nouveau le fichier."
            })
            val plan = (state as? ImportState.Preview)?.plan ?: (state as? ImportState.Conflict)?.plan
            if (plan != null) {
                Text("Fichier : ${plan.sessionCount} séance(s), ${plan.newSessionUuids.size} nouvelle(s), ${plan.identicalSessionUuids.size} identique(s), ${plan.conflicts.size} conflit(s), ${plan.profileCount} profil(s).")
                plan.conflicts.take(20).forEach { Text("${it.code} · ${it.uuid ?: "archive"} · ${it.path} : ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                if (plan.conflicts.size > 20) Text("${plan.conflicts.size - 20} autre(s) conflit(s). Aucun import possible.")
            }
            val planV2 = (state as? ImportState.PreviewV2)?.plan ?: (state as? ImportState.ConflictV2)?.plan
            if (planV2 != null) {
                v2PlanLines(planV2).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                planV2.conflicts.take(20).forEach { Text("${it.code} · ${it.uuid ?: "archive"} · ${it.path} : ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                if (planV2.conflicts.size > 20) Text("${planV2.conflicts.size - 20} autre(s) conflit(s). Aucun import possible.")
            }
            val planV3 = (state as? ImportState.PreviewV3)?.plan ?: (state as? ImportState.ConflictV3)?.plan
            if (planV3 != null) {
                v3PlanLines(planV3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                planV3.conflicts.take(20).forEach { Text("${it.code} · ${it.path} : ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                if (planV3.conflicts.size > 20) Text("${planV3.conflicts.size - 20} autre(s) conflit(s). Aucun import possible.")
            }
            (state as? ImportState.PreviewV3)?.let { preview ->
                Button(onClick = { coordinator.confirmV3(preview.confirmationId) }) { Text(if (preview.plan.hasAdditions) "Confirmer la restauration V3 complète" else "Confirmer la vérification sans écriture") }
                TextButton(onClick = coordinator::cancel) { Text("Fermer l’aperçu sans importer") }
            }
            if (state is ImportState.UnconfirmedV3) {
                Button(onClick = coordinator::repreviewV3) { Text("Relire et préparer un nouvel aperçu V3") }
                TextButton(onClick = { confirmDismiss = true }) { Text("Fermer sans relancer la restauration") }
            }
            if (state is ImportState.PreviewV2) {
                Button(onClick = coordinator::confirm) { Text(if (planV2!!.hasAdditions) "Confirmer l’import V2 complet" else "Confirmer la vérification sans écriture") }
                TextButton(onClick = coordinator::cancel) { Text("Fermer l’aperçu sans importer") }
            }
            if (state is ImportState.Preview) {
                if (plan!!.canImport) Button(onClick = coordinator::confirm) { Text("Importer les ${plan.newSessionUuids.size} nouvelles séances") }
                TextButton(onClick = coordinator::cancel) { Text("Fermer l’aperçu sans importer") }
            }
            if (state.active && state !is ImportState.Choosing && state !is ImportState.Preview && state !is ImportState.PreviewV2 && state !is ImportState.PreviewV3 && state !is ImportState.UnconfirmedV3) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (sessionState.busy || sessionState.pending != null || sessionState.impactEdit != null || sessionState.archiveOperationLocked && !state.active)
                Text("Terminez l’opération en cours et résolvez toute sauvegarde ou correction avant d’importer.")
            Button(onClick = coordinator::begin, enabled = !state.active && sessionState.canNavigate) { Text("Importer une sauvegarde") }
        }
    }
    if (confirmDismiss) AlertDialog(onDismissRequest = { confirmDismiss = false },
        title = { Text("Fermer un résultat non confirmé ?") },
        text = { Text("La transaction a pu être enregistrée. Fermer ne l’annule pas et ne la rejoue pas. Une nouvelle prévisualisation explicite sera nécessaire pour constater l’état réel.") },
        confirmButton = { TextButton(onClick = { confirmDismiss = false; coordinator.dismissUnconfirmedV3() }) { Text("Fermer sans relancer") } },
        dismissButton = { TextButton(onClick = { confirmDismiss = false }) { Text("Conserver le candidat") } })
}
