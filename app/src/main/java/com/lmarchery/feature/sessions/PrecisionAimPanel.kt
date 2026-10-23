package com.lmarchery.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrecisionAimPanel(state: SessionUiState, controller: SessionController) {
    PrecisionAimFields(state.newImpactCandidate, state.activeAim?.candidate, presentNewImpactNotice(state),
        state.canConfirmNewImpact, state.ready, controller::confirmNewImpactCandidate, controller::cancelNewImpactCandidate)
}

@Composable
fun PrecisionAimFields(candidate: PositionCandidate?, active: PositionCandidate?, notice: NewImpactNotice,
    canConfirm: Boolean, canCancel: Boolean, confirm: () -> Unit, cancel: () -> Unit) {
    if (active != null) Text("Visée en cours · relâchez pour proposer, sans enregistrer.")
    candidate?.let { candidate ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice.heading, style = MaterialTheme.typography.titleMedium)
                Text("Position proposée : (${candidate.point.x}, ${candidate.point.y}) · score géométrique ${candidate.scoreLabel()}")
                Text(notice.guidance)
                Button(onClick = confirm, enabled = canConfirm) { Text("Confirmer la nouvelle flèche") }
                TextButton(onClick = cancel, enabled = canCancel) { Text("Annuler la nouvelle flèche") }
            }
        }
    }
    active?.let { candidate ->
        Text("Aperçu géométrique : ${candidate.scoreLabel()} · non enregistré", style = MaterialTheme.typography.bodySmall)
    }
}
