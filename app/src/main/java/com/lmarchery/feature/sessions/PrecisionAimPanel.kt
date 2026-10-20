package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrecisionAimPanel(state: SessionUiState, controller: SessionController) {
    if (state.activeAim != null) Text("Visée en cours · relâchez pour proposer, sans enregistrer.")
    state.newImpactCandidate?.let { candidate ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val notice = presentNewImpactNotice(state)
                Text(notice.heading, style = MaterialTheme.typography.titleMedium)
                Text("Position proposée : (${candidate.point.x}, ${candidate.point.y}) · score géométrique ${candidate.scoreLabel()}")
                Text(notice.guidance)
                Button(onClick = controller::confirmNewImpactCandidate, enabled = state.canConfirmNewImpact) { Text("Confirmer la nouvelle flèche") }
                TextButton(onClick = controller::cancelNewImpactCandidate, enabled = state.ready) { Text("Annuler la nouvelle flèche") }
            }
        }
    }
    state.activeAim?.candidate?.let { candidate ->
        Text("Aperçu géométrique : ${candidate.scoreLabel()} · non enregistré", style = MaterialTheme.typography.bodySmall)
    }
}
