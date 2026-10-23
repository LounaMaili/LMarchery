package com.lmarchery.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.core.model.session.*
import com.lmarchery.core.model.scoring.ScoreDecisionKind
import kotlinx.coroutines.launch
import com.lmarchery.feature.scoring.TargetCanvas
import com.lmarchery.feature.scoring.TargetInteraction
import com.lmarchery.feature.scoring.precisionTargetMaxWidth
import com.lmarchery.feature.sessions.*
import com.lmarchery.feature.equipment.EquipmentController
import com.lmarchery.feature.equipment.EquipmentScreen
import com.lmarchery.feature.archiveimport.ImportCoordinator
import com.lmarchery.feature.archiveimport.ImportPanel
import com.lmarchery.feature.archiveimport.ImportSelectionEffect
import com.lmarchery.feature.export.ExportCoordinator
import com.lmarchery.feature.export.ExportPanel
import com.lmarchery.feature.export.ExportDestinationEffect

@Composable
fun LMarcheryApp(controller: SessionController, exportCoordinator: ExportCoordinator, importCoordinator: ImportCoordinator, equipmentController: EquipmentController? = null) {
    ExportDestinationEffect(exportCoordinator)
    ImportSelectionEffect(importCoordinator)
    val state by controller.state.collectAsStateWithLifecycle()
    val equipmentState = equipmentController?.state?.collectAsStateWithLifecycle()?.value
    if (equipmentState?.open == true) {
        EquipmentScreen(equipmentController)
        return
    }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmCancelAim by rememberSaveable { mutableStateOf(false) }
    var confirmCancelCorrection by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = {
        if (!state.busy && !state.archiveOperationLocked) {
            when {
                state.pending != null -> confirmLeave = true
                state.impactEdit != null -> confirmCancelCorrection = true
                state.hasAimProposal -> confirmCancelAim = true
                state.equipmentEdit != null -> controller.cancelEquipmentEdit()
                else -> controller.returnToList()
            }
        }
    }
    BackHandler(enabled = state.screen != SessionScreen.LIST || state.busy || state.pending != null || state.archiveOperationLocked || state.hasAimProposal, onBack = back)
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val scrollStates = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    // Key only navigation identity: SQL acknowledgements must not recreate scroll state.
    scrollStates.SaveableStateProvider("${state.screen}:${(state.session?.uuid ?: state.configuredSaved?.uuid).orEmpty()}") {
        val listState = rememberLazyListState()
        val showEnd: (String) -> Unit = { uuid ->
            if (state.canNavigate) {
                controller.selectEnd(uuid)
                scope.launch { listState.animateScrollToItem(0) }
            }
        }
        Scaffold { padding ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().precisionTouchGuard(controller).padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                if (state.screen == SessionScreen.LIST) item(key = "header") {
                    Text("LMarchery", style = MaterialTheme.typography.headlineMedium)
                    Text("Séances locales sur cet appareil", style = MaterialTheme.typography.bodySmall)
                }
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
                when (state.screen) {
                    SessionScreen.LIST -> {
                        item(key = "list-actions") {
                            if (equipmentController != null) OutlinedButton(onClick = equipmentController::open, enabled = state.canNavigate) { Text("Matériel") }
                            Button(onClick = controller::configure, enabled = state.ready) { Text("Nouvelle séance") }
                            OutlinedButton(onClick = controller::refresh, enabled = state.ready) { Text("Actualiser la liste") }
                            if (!state.busy && state.entries.isEmpty() && state.problem == null) Text("Aucune séance enregistrée.")
                        }
                        val history = presentSessionHistory(state.entries)
                        val sections = listOf(
                            "Séances en cours ou à consulter" to history.resumable,
                            "Historique" to history.completed,
                            "Séances nécessitant une intervention" to history.intervention,
                        )
                        sections.forEach { (heading, entries) ->
                            item(key = heading) {
                                Text(heading, style = MaterialTheme.typography.titleLarge)
                                if (entries.isEmpty()) Text("Aucune séance dans cette section.", style = MaterialTheme.typography.bodySmall)
                            }
                            items(entries, key = { it.uuid }) { entry ->
                                SessionHistoryCard(entry, state.canNavigate) { controller.openSession(entry.uuid) }
                            }
                        }
                        // Always expanded: incidents and confirmations stay visible without a disclosure toggle.
                        item(key = "backups-heading") { Text("Sauvegardes", style = MaterialTheme.typography.titleLarge) }
                        item(key = "export") { ExportPanel(exportCoordinator, state) }
                        item(key = "import") { ImportPanel(importCoordinator, state) }
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
                            if (state.equipmentChoicesLoading) Text("Chargement facultatif du catalogue… Vous pouvez créer sans matériel.")
                            EquipmentChoiceFields(state.equipmentChoices, state.creationEquipment, state.ready, choose = controller::chooseCreationEquipment)
                            Button(onClick = controller::createSession, enabled = state.ready) { Text("Créer et démarrer") }
                            TextButton(onClick = back, enabled = !state.busy && !state.archiveOperationLocked) { Text("Retour aux séances") }
                        }
                    }
                    SessionScreen.SESSION -> {
                        state.configuredSaved?.let { configured ->
                            item(key = "configured-summary") { ConfiguredSessionSummary(configured) }
                            item(key = "configured-back") { TextButton(onClick = back, enabled = state.canNavigate) { Text("Retour aux séances") } }
                            if (configured.ends.isEmpty()) item(key = "configured-empty") { Text("Aucune volée enregistrée ; configuration initiale conservée.") }
                            items(configured.ends.sortedBy { it.end.index }, key = { "configured-${it.uuid}" }) { captured ->
                                ConfiguredEndCard(configured, captured)
                            }
                        }
                        val session = state.session
                        if (session != null) {
                            item(key = "session-summary") {
                                Text(session.title, style = MaterialTheme.typography.titleLarge)
                                Text(session.status.label())
                                Text("Score validé : ${state.validatedTotal}", style = MaterialTheme.typography.titleMedium)
                                if (session.status != SessionStatus.COMPLETED && state.editingEnd != null)
                                    Text("Volée en cours : ${state.provisionalTotal} point(s) provisoires")
                                if (session.status == SessionStatus.COMPLETED) Text("Scores en lecture seule. La séance est terminée ; seules les associations de matériel restent modifiables.")
                                else if (!state.supportedConfiguration) Text("Configuration consultable en lecture seule dans cet éditeur.")
                            }
                            item(key = "session-equipment") { SessionEquipmentPanel(state, controller) }
                            state.displayedEnd?.let { end -> item(key = "target") {
                                Text("Volée ${end.index} · ${if (end.status == EndStatus.VALIDATED) "Validée · lecture seule" else "En saisie"} · ${end.impacts.size}/${session.arrowCountPerEnd ?: "libre"} flèches", style = MaterialTheme.typography.titleMedium)
                                val interaction = when {
                                    state.canProposeReposition -> TargetInteraction.REPOSITION
                                    state.canAddImpact || state.canAimNewImpact -> TargetInteraction.ADD_IMPACT
                                    else -> TargetInteraction.READ_ONLY
                                }
                                if (state.canEdit && state.isEditingDisplayed) {
                                    OutlinedButton(onClick = { controller.setPrecisionAim(!state.precisionAimEnabled) }, enabled = state.canChangeAimMode) {
                                        Text(if (state.precisionAimEnabled) "Visée précise activée · revenir au mode rapide" else "Activer la visée précise")
                                    }
                                    if (state.precisionAimEnabled) Text("Maintenez et déplacez le doigt. Loupe ×4 déportée ; relâcher ne sauvegarde pas. Faites défiler hors du blason.", style = MaterialTheme.typography.bodySmall)
                                }
                                val aimContext = AimContext(session.uuid, end.uuid, state.impactEdit?.impactUuid)
                                TargetCanvas(session.targetType, end.impacts, interaction, end.uuid, controller::targetTapped,
                                    Modifier.widthIn(max = if (state.precisionAimEnabled) precisionTargetMaxWidth(screenHeightDp).dp else 480.dp)
                                        .fillMaxWidth().aspectRatio(1f), state.selectedImpact?.uuid,
                                    state.aimedPoint,
                                    state.precisionAimEnabled, { point -> controller.beginPreciseAim(aimContext, point) }, controller::movePreciseAim,
                                    controller::finishPreciseAim, controller::cancelPreciseAim)
                                Text(when {
                                    state.precisionAimEnabled && state.canAim -> "Visée précise · proposition non enregistrée. Les coins extérieurs restent M."
                                    state.canProposeReposition -> "Touchez pour proposer une position. Aucun impact ne sera ajouté."
                                    state.canAddImpact -> "Touchez pour enregistrer la flèche ${end.impacts.size + 1}/3. Les coins extérieurs comptent M."
                                    else -> "${end.impacts.size} impact(s) enregistré(s)."
                                }, style = MaterialTheme.typography.bodySmall)
                                val row = presentEndScore(end)
                                Text(row.arrowsLabel)
                                Text(row.totalLabel)
                            } }
                            if (state.hasAimProposal) item(key = "precision-candidate") { PrecisionAimPanel(state, controller) }
                            item(key = "navigation") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (session.status == SessionStatus.IN_PROGRESS && state.editingEnd != null && !state.isEditingDisplayed)
                                        OutlinedButton(onClick = { showEnd(state.editingEnd!!.uuid) }, enabled = state.canNavigate) { Text("Revenir à la volée en cours") }
                                    TextButton(onClick = back, enabled = !state.busy && !state.archiveOperationLocked) { Text("Retour aux séances") }
                                    if (session.status == SessionStatus.DRAFT && state.supportedConfiguration)
                                        Button(onClick = controller::startSession, enabled = state.ready) { Text("Démarrer") }
                                }
                            }
                            if (state.impactEdit != null) item(key = "impact-edit") { ImpactEditPanel(state, controller) }
                            if (state.supportedConfiguration && session.status == SessionStatus.IN_PROGRESS && state.isEditingDisplayed) {
                                items(state.displayedEnd!!.impacts, key = { "select-${it.uuid}" }) { impact ->
                                    OutlinedButton(onClick = { controller.selectImpact(state.displayedEnd!!.uuid, impact.uuid) },
                                        enabled = state.canSelectImpact, modifier = Modifier.fillMaxWidth()) {
                                        Text("Sélectionner flèche #${impact.index} : ${impact.scoreLabel()}")
                                    }
                                }
                            }
                            if (session.status == SessionStatus.IN_PROGRESS && state.supportedConfiguration) item(key = "session-actions") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = controller::validateEnd, enabled = state.canValidate) { Text("Valider la volée (3 flèches)") }
                                    Button(onClick = controller::newEnd, enabled = state.canNewEnd) { Text("Nouvelle volée") }
                                    OutlinedButton(onClick = controller::completeSession, enabled = state.canComplete) { Text("Terminer la séance") }
                                    if (state.impactEdit != null) Text("Fermez la sélection ou annulez la correction avant de valider ou changer de volée.")
                                    if (state.editingEnd?.impacts?.isNotEmpty() == true) Text("Validez les trois flèches avant de terminer la séance.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            state.confirmation?.let { message -> item(key = "confirmation") { Text(message, color = MaterialTheme.colorScheme.primary) } }
                            item(key = "score-table-heading") { Text("Tableau des scores", style = MaterialTheme.typography.titleLarge) }
                            if (session.ends.isEmpty()) item(key = "no-ends") { Text("Aucune volée enregistrée.") }
                            items(session.ends, key = { it.uuid }) { end ->
                                EndScoreCard(presentEndScore(end), end.uuid == state.displayedEnd?.uuid, state.canNavigate) { showEnd(end.uuid) }
                            }
                            item(key = "score-details") { SessionScorePanel(session) }
                            item(key = "session-details") {
                                Text("${session.targetType.name} · ${session.distanceM} m")
                                Text(session.civilDateLabel(), style = MaterialTheme.typography.bodySmall)
                            }
                            item(key = "limitations") { Text("Corrections explicites avant validation : position ou score. Aucune suppression ni modification après validation.", style = MaterialTheme.typography.bodySmall) }
                        }
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
    if (confirmCancelAim && state.hasAimProposal) AlertDialog(onDismissRequest = { confirmCancelAim = false },
        title = { Text("Annuler la nouvelle flèche et revenir ?") },
        text = { Text("La position proposée n’est pas enregistrée. Revenir abandonnera ce candidat sans écrire.") },
        confirmButton = { TextButton(onClick = {
            confirmCancelAim = false
            controller.cancelNewImpactCandidate()
            controller.returnToList()
        }) { Text("Annuler et revenir") } },
        dismissButton = { TextButton(onClick = { confirmCancelAim = false }) { Text("Conserver la proposition") } })
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
                equipmentAssociationLines(state.equipmentChoices, EquipmentSelection(stored.bowUuid, stored.arrowSetUuid)).forEach { Text(it) }
                Text("Révision enregistrée UTC : ${stored.updatedAt}")
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
