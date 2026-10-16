package com.lmarchery.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import com.lmarchery.core.scoring.calculateEndScore
import com.lmarchery.feature.scoring.TargetCanvas
import com.lmarchery.feature.scoring.TargetInteraction
import com.lmarchery.feature.sessions.*
import com.lmarchery.feature.archiveimport.ImportCoordinator
import com.lmarchery.feature.archiveimport.ImportPanel
import com.lmarchery.feature.archiveimport.ImportSelectionEffect
import com.lmarchery.feature.export.ExportCoordinator
import com.lmarchery.feature.export.ExportPanel
import com.lmarchery.feature.export.ExportDestinationEffect

@Composable
fun LMarcheryApp(controller: SessionController, exportCoordinator: ExportCoordinator, importCoordinator: ImportCoordinator) {
    ExportDestinationEffect(exportCoordinator)
    ImportSelectionEffect(importCoordinator)
    val state by controller.state.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmCancelCorrection by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = {
        if (!state.busy && !state.archiveOperationLocked) {
            when {
                state.pending != null -> confirmLeave = true
                state.impactEdit != null -> confirmCancelCorrection = true
                else -> controller.returnToList()
            }
        }
    }
    BackHandler(enabled = state.screen != SessionScreen.LIST || state.busy || state.pending != null || state.archiveOperationLocked, onBack = back)
    Scaffold { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item(key = "header") {
                Text("LMarchery", style = MaterialTheme.typography.headlineMedium)
                Text("Séances locales sur cet appareil · Export et import JSON contrôlé disponibles.", style = MaterialTheme.typography.bodySmall)
            }
            item(key = "export") { ExportPanel(exportCoordinator, state) }
            if (state.screen == SessionScreen.LIST) item(key = "import") { ImportPanel(importCoordinator, state) }
            if (state.busy) item(key = "loading") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (state.pending != null) "Enregistrement en cours…" else "Chargement…")
            }
            state.problem?.let { problem -> item(key = "problem") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (problem.kind) {
                            ProblemKind.CONFLICT -> "Conflit : une action explicite est nécessaire."
                            ProblemKind.INTERVENTION -> "Données nécessitant une intervention. Aucune réparation automatique."
                            else -> "Opération non confirmée."
                        }, style = MaterialTheme.typography.titleSmall)
                        Text(problem.message)
                        if (state.pending == null && state.impactEdit == null && problem.retryable) {
                            TextButton(enabled = !state.busy, onClick = {
                                if (state.screen == SessionScreen.LIST) controller.refresh()
                                else state.session?.uuid?.let(controller::openSession)
                            }) { Text("Réessayer la lecture") }
                        }
                    }
                }
            } }
            state.pending?.let { pending -> item(key = "pending") {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Action non confirmée : ${pending.description}")
                        Text("Les impacts et totaux ci-dessous sont ceux du dernier enregistrement confirmé.")
                        if (state.problem?.retryable == true) Button(onClick = controller::retrySave, enabled = !state.busy) { Text("Réessayer la même sauvegarde") }
                        OutlinedButton(onClick = controller::previewStored, enabled = !state.busy) { Text("Consulter la version enregistrée") }
                        TextButton(onClick = { confirmLeave = true }, enabled = !state.busy) { Text("Abandonner l’action locale et revenir") }
                    }
                }
            } }
            state.confirmation?.let { message -> item(key = "confirmation") { Text(message, color = MaterialTheme.colorScheme.primary) } }
            when (state.screen) {
                SessionScreen.LIST -> {
                    item(key = "list-actions") {
                        Button(onClick = controller::configure, enabled = state.ready) { Text("Nouvelle séance") }
                        OutlinedButton(onClick = controller::refresh, enabled = state.ready) { Text("Actualiser la liste") }
                        if (!state.busy && state.entries.isEmpty() && state.problem == null) Text("Aucune séance enregistrée.")
                    }
                    items(state.entries, key = { it.uuid }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                entry.session?.let { s ->
                                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${s.date.localDateTime} · ${s.date.zoneId.id} (${s.date.offset})")
                                    Text("${s.targetType.name} · ${s.distanceM} m")
                                    Text(s.status.label())
                                } ?: run {
                                    Text("Séance illisible : ${entry.uuid}")
                                    Text(entry.problem?.message ?: "Lecture non résolue.", color = MaterialTheme.colorScheme.error)
                                }
                                OutlinedButton(onClick = { controller.openSession(entry.uuid) }, enabled = state.ready) {
                                    Text(if (entry.session == null) "Réessayer la lecture" else "Ouvrir")
                                }
                            }
                        }
                    }
                }
                SessionScreen.CREATE -> item(key = "configuration") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Créer et démarrer une séance", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(value = state.title, onValueChange = controller::setTitle, enabled = state.ready,
                            label = { Text("Titre facultatif") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("3 flèches par volée · Entraînement · Date dans le fuseau du téléphone à la création.")
                        IndoorProfile.entries.forEach { profile ->
                            OutlinedButton(onClick = { controller.selectProfile(profile) }, enabled = state.ready,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("${if (state.selectedProfile == profile) "✓ " else ""}${profile.label}")
                            }
                        }
                        Text("Sans titre : « Séance salle — ${state.selectedProfile.label} ».", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = controller::createSession, enabled = state.ready) { Text("Créer et démarrer") }
                        TextButton(onClick = back, enabled = !state.busy && !state.archiveOperationLocked) { Text("Retour aux séances") }
                    }
                }
                SessionScreen.SESSION -> {
                    val session = state.session
                    if (session != null) {
                        item(key = "session-summary") {
                            Text(session.title, style = MaterialTheme.typography.titleLarge)
                            Text("${session.targetType.name} · ${session.distanceM} m · ${session.status.label()}")
                            Text("${session.date.localDateTime} · ${session.date.zoneId.id} (${session.date.offset})", style = MaterialTheme.typography.bodySmall)
                            Text("Total des volées validées : ${state.validatedTotal}", style = MaterialTheme.typography.titleMedium)
                            Text("Total provisoire (volée non validée) : ${state.provisionalTotal}")
                            if (session.status == SessionStatus.COMPLETED) Text("Consultation en lecture seule. La séance est terminée.")
                            else if (!state.supportedConfiguration) Text("Configuration consultable ; la saisie de ce premier parcours est limitée aux profils indoor proposés, en entraînement, à trois flèches.")
                        }
                        item(key = "navigation") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = back, enabled = !state.busy && !state.archiveOperationLocked) { Text("Retour aux séances") }
                                if (session.status == SessionStatus.DRAFT) Button(onClick = controller::startSession,
                                    enabled = state.ready && state.supportedConfiguration) { Text("Démarrer") }
                            }
                        }
                        state.displayedEnd?.let { end -> item(key = "target-${end.uuid}") {
                            Text("Volée ${end.index} · ${if (end.status == EndStatus.VALIDATED) "Validée" else "En saisie"}", style = MaterialTheme.typography.titleMedium)
                            if (state.impactEdit != null) ImpactEditPanel(state, controller)
                            val interaction = when {
                                state.canProposeReposition -> TargetInteraction.REPOSITION
                                state.canAddImpact -> TargetInteraction.ADD_IMPACT
                                else -> TargetInteraction.READ_ONLY
                            }
                            TargetCanvas(session.targetType, end.impacts, interaction, end.uuid, controller::targetTapped,
                                Modifier.fillMaxWidth().aspectRatio(1f), state.selectedImpact?.uuid, state.impactEdit?.candidate)
                            Text(when {
                                state.canProposeReposition -> "Touchez pour proposer une position. Aucun impact ne sera ajouté."
                                state.canAddImpact -> "Touchez le blason pour enregistrer la flèche ${end.impacts.size + 1}/3. Les coins extérieurs au cercle comptent M."
                                else -> "${end.impacts.size} impact(s) enregistré(s)."
                            })
                            Text(end.impacts.joinToString(" · ") { "#${it.index} : ${it.scoreLabel()}" }.ifEmpty { "Aucun impact enregistré." })
                            Text("Total de cette volée : ${calculateEndScore(end)}${if (end.status == EndStatus.EDITING) " (provisoire)" else " (validé)"}")
                        } }
                        if (state.supportedConfiguration && session.status == SessionStatus.IN_PROGRESS && state.isEditingDisplayed) {
                            items(state.displayedEnd!!.impacts, key = { "select-${it.uuid}" }) { impact ->
                                OutlinedButton(onClick = { controller.selectImpact(state.displayedEnd!!.uuid, impact.uuid) },
                                    enabled = state.canSelectImpact, modifier = Modifier.fillMaxWidth()) {
                                    Text("Sélectionner flèche #${impact.index} : ${impact.scoreLabel()}")
                                }
                            }
                        }
                        if (session.status != SessionStatus.COMPLETED) item(key = "session-actions") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = controller::validateEnd, enabled = state.canValidate) { Text("Valider la volée (3 flèches)") }
                                Button(onClick = controller::newEnd, enabled = state.canNewEnd) { Text("Nouvelle volée") }
                                OutlinedButton(onClick = controller::completeSession, enabled = state.canComplete) { Text("Terminer la séance") }
                                if (state.impactEdit != null) Text("Fermez la sélection ou annulez la correction avant de valider ou changer de volée.")
                                if (state.editingEnd?.impacts?.isNotEmpty() == true) Text("Validez les trois flèches avant de terminer la séance.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        items(session.ends, key = { it.uuid }) { end ->
                            OutlinedButton(onClick = { controller.selectEnd(end.uuid) }, enabled = state.canNavigate,
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Volée ${end.index} · ${end.impacts.size} flèches · ${calculateEndScore(end)} · ${if (end.status == EndStatus.VALIDATED) "validée" else "provisoire"}")
                            }
                        }
                        item(key = "limitations") { Text("Corrections explicites avant validation : position ou score. Aucune suppression ni modification après validation.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("Abandonner l’action locale ?") },
        text = { Text("Son enregistrement n’est pas confirmé. Revenir à la liste abandonne la proposition locale et conserve le contenu présent dans la base.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; controller.discardAndReturnToList() }) { Text("Abandonner et revenir") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Conserver l’action") } })
    if (confirmCancelCorrection) AlertDialog(onDismissRequest = { confirmCancelCorrection = false },
        title = { Text("Annuler la correction et revenir ?") },
        text = { Text("La sélection et la proposition non soumise seront abandonnées. Aucune écriture n’a été demandée.") },
        confirmButton = { TextButton(onClick = {
            confirmCancelCorrection = false
            controller.cancelImpactEdit()
            controller.returnToList()
        }) { Text("Annuler et revenir") } },
        dismissButton = { TextButton(onClick = { confirmCancelCorrection = false }) { Text("Conserver la correction") } })
    state.storedPreview?.let { archive ->
        val stored = archive.sessions.single()
        AlertDialog(onDismissRequest = controller::closePreview, title = { Text("Version enregistrée") },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${stored.title} · ${stored.status.label()}\nRecharger abandonnera la proposition locale non confirmée.")
                if (state.busy) Text("Relecture en cours…")
                state.problem?.let { problem -> Text(problem.message, color = MaterialTheme.colorScheme.error) }
                stored.ends.forEach { end -> Text("Volée ${end.index} (${end.status}) : ${end.impacts.joinToString { "#${it.index} ${it.scoreLabel()}" }}") }
            } },
            confirmButton = { TextButton(onClick = controller::reloadAndDiscard, enabled = !state.busy) { Text("Abandonner l’action et recharger") } },
            dismissButton = { TextButton(onClick = controller::closePreview, enabled = !state.busy) { Text("Conserver l’action locale") } })
    }
}

@Composable
private fun ImpactEditPanel(state: SessionUiState, controller: SessionController) {
    val edit = state.impactEdit ?: return
    val impact = state.selectedImpact ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Flèche #${impact.index} sélectionnée", style = MaterialTheme.typography.titleMedium)
            Text("Position enregistrée : (${impact.point.x}, ${impact.point.y}) · ${impact.scoreLabel()}")
            val provenance = impact.finalScoreDecision.provenance
            Text("Décision enregistrée : ${when (provenance.kind) {
                ScoreDecisionKind.GEOMETRY -> "Géométrie"
                ScoreDecisionKind.BOUNDARY_JUDGMENT -> "Jugement de cordon"
                ScoreDecisionKind.EXCEPTIONAL_CORRECTION -> "Correction exceptionnelle"
            }}")
            provenance.decidedAt?.let { Text("Date UTC : $it") }
            provenance.boundary?.let { Text("Limite enregistrée : ${it.type} · r = ${it.radiusNormalized}") }
            provenance.reason?.let { Text("Motif enregistré : $it") }
            when (edit.phase) {
                ImpactEditPhase.SELECTED -> Text("Choisissez Repositionner ou Corriger le score.")
                ImpactEditPhase.AWAITING_POINT -> Text("Repositionnement : choisissez une nouvelle position sur le blason.")
                ImpactEditPhase.CANDIDATE -> Text("Proposition non enregistrée : confirmez ou annulez.")
                ImpactEditPhase.SAVING -> Text("Sauvegarde en cours. Position, scores et totaux confirmés restent affichés.")
                ImpactEditPhase.FAILED -> Text("Correction non confirmée. La proposition soumise est conservée.")
                ImpactEditPhase.CONFIRMED -> Text("Correction enregistrée sur cet appareil.")
            }
            edit.preview?.let { preview ->
                Text("Position proposée : (${preview.point.x}, ${preview.point.y}) · ${when {
                    preview.isMiss -> "M"
                    preview.isX -> "${preview.scoreFinal}X"
                    else -> preview.scoreFinal.toString()
                }} (aperçu géométrique)")
                Text("L’ancienne décision de score sera invalidée et conservée dans l’historique.")
                OutlinedTextField(edit.reason, controller::setRepositionReason, enabled = state.canProposeReposition,
                    label = { Text(if (impact.finalScoreDecision.provenance.isManual) "Motif obligatoire (décision manuelle)" else "Motif facultatif") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (edit.phase in listOf(ImpactEditPhase.SELECTED, ImpactEditPhase.CONFIRMED)) {
                Button(onClick = controller::beginReposition, enabled = state.canBeginReposition) { Text("Repositionner") }
                OutlinedButton(onClick = controller::beginScoreCorrection, enabled = state.canBeginScoreCorrection) { Text("Corriger le score") }
            }
            if (edit.proposal is ImpactProposal.BoundaryJudgment || edit.proposal is ImpactProposal.ExceptionalCorrection)
                ScoreDecisionPanel(state, controller)
            if (edit.candidate != null) Button(onClick = controller::confirmReposition,
                enabled = state.canConfirmReposition) { Text("Confirmer le repositionnement") }
            TextButton(onClick = controller::cancelImpactEdit, enabled = state.ready) {
                Text(when {
                    edit.phase in listOf(ImpactEditPhase.SELECTED, ImpactEditPhase.CONFIRMED) -> "Fermer la sélection"
                    edit.proposal is ImpactProposal.Reposition -> "Annuler le repositionnement"
                    else -> "Annuler la décision de score"
                })
            }
            Text("Cercle orange : flèche sélectionnée. Croix violette : proposition non enregistrée.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun SessionStatus.label() = when (this) {
    SessionStatus.DRAFT -> "Brouillon"
    SessionStatus.IN_PROGRESS -> "En cours"
    SessionStatus.COMPLETED -> "Terminée"
}
private fun ArrowImpact.scoreLabel() = when {
    isMiss -> "M"
    isX -> "${scoreFinal}X"
    else -> scoreFinal.toString()
}
