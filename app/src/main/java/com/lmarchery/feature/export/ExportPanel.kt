package com.lmarchery.feature.export

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

@Composable
fun ExportDestinationEffect(coordinator: ExportCoordinator) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    var wasActive by rememberSaveable { mutableStateOf(false) }
    var pendingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val interruptedAtEntry = remember { wasActive && coordinator.state.value == ExportState.Available }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        val ticket = pendingRequestId
        pendingRequestId = null
        coordinator.destinationChosen(ticket, it?.toString())
    }
    LaunchedEffect(Unit) {
        if (interruptedAtEntry) coordinator.reportInterrupted()
    }
    SideEffect { wasActive = state.active }
    val request = state as? ExportState.Choosing
    LaunchedEffect(request?.requestId) {
        coordinator.claimDestinationRequest()?.let { name ->
            val ticket = request?.requestId
            if (pendingRequestId != null) coordinator.pickerUnavailable(ticket)
            else {
                pendingRequestId = ticket
                try { launcher.launch(name) } catch (_: Exception) { pendingRequestId = null; coordinator.pickerUnavailable(ticket) }
            }
        }
    }
}

@Composable
fun ExportPanel(coordinator: ExportCoordinator, sessionState: SessionUiState) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sauvegardes JSON V3 / V2 / V1", style = MaterialTheme.typography.titleMedium)
            Text("Le fichier est lisible en clair et peut contenir des informations personnelles. L’import contrôlé est accessible depuis la liste des séances.",
                style = MaterialTheme.typography.bodySmall)
            Text(when (val current = state) {
                ExportState.Available -> "V3 : sauvegarde complète incluant les configurations exactes de chaque volée. V1/V2 refusent toute séance configurable, même homogène. V2 : toutes les séances, tous les profils et le catalogue matériel, y compris sans séance. V1 : séances et profils associés uniquement, sans matériel."
                ExportState.Preparing -> "Préparation : lecture d’un instantané cohérent…"
                ExportState.Validating -> "Validation : encodage, décodage et reconstruction du contenu…"
                is ExportState.Choosing -> "Choisissez la destination du fichier ${current.fileName}."
                ExportState.Writing -> "Écriture et relecture du document… Sauvegarde non encore confirmée."
                is ExportState.Succeeded -> "Export V${current.schemaVersion} confirmé après relecture : ${current.sessionCount} séance(s), ${current.profileCount} profil(s), ${if (current.schemaVersion == 3) "${current.endCount} volée(s), ${current.impactCount} impact(s), " else ""}${current.bowCount} arc(s), ${current.arrowSetCount} jeu(x) de flèches, ${current.receipt.byteCount} octets.\nFichier : ${current.receipt.displayName ?: "destination choisie"}.\nL’import contrôlé est accessible depuis la liste des séances."
                ExportState.Cancelled -> "Export annulé : aucun contenu écrit par LMarchery."
                is ExportState.Failed -> current.diagnostic + if (current.potentiallyIncomplete)
                    "\nLe document peut être vide ou incomplet. Ne l’utilisez pas comme sauvegarde ; choisissez un nouveau document lors d’une nouvelle tentative." else ""
            })
            if (state.active && state !is ExportState.Choosing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (sessionState.busy || sessionState.pending != null || sessionState.impactEdit != null)
                Text("Pour exporter, terminez l’opération en cours, résolvez toute sauvegarde non confirmée et fermez la sélection/correction. Un candidat non enregistré n’est pas exporté.")
            Button(onClick = coordinator::beginV3,
                enabled = !state.active && sessionState.ready && sessionState.impactEdit == null) { Text("Exporter la sauvegarde complète V3") }
            OutlinedButton(onClick = coordinator::beginV2,
                enabled = !state.active && sessionState.ready && sessionState.impactEdit == null) { Text("Exporter V2 — séances historiques et matériel") }
            OutlinedButton(onClick = coordinator::begin,
                enabled = !state.active && sessionState.ready && sessionState.impactEdit == null) { Text("Exporter V1 — sans catalogue matériel") }
        }
    }
}
