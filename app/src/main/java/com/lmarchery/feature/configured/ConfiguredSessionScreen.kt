package com.lmarchery.feature.configured

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lmarchery.core.model.session.*
import com.lmarchery.core.scoring.profiles.BuiltInTargetRegistry
import com.lmarchery.feature.scoring.*
import com.lmarchery.feature.sessions.*

@Composable
fun ConfiguredSessionScreen(controller: ConfiguredSessionController) {
    val state by controller.state.collectAsStateWithLifecycle()
    var leave by rememberSaveable { mutableStateOf(false) }
    var cancellationLeaveToken by rememberSaveable { mutableStateOf<Long?>(null) }
    state.cancellation?.let { dialog ->
        ImpactCancellationConfirmation(dialog, state.busy, state.pending != null, state.problem?.message, state.canRetryExact,
            controller::cancellationComment, controller::closeCancellation, controller::confirmCancellation, controller::reconcile, controller::retryExact,
            { token -> if (controller.canAbandonCancellationDisplay(token)) { cancellationLeaveToken = token; leave = true } })
    }
    val creation = state.form as? ConfiguredForm.Creation
    val presets = state.presets
    if (creation != null && presets?.nameDraft != null) {
        val token = creation.token
        val dismiss = { controller.cancelPresetName(token) }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Enregistrer un preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(presets.nameDraft, { controller.presetName(token, it) }, enabled = state.ready,
                        label = { Text("Nom du preset") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Les réglages actuels seront disponibles sur cet appareil.")
                    presets.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button({ controller.savePreset(token) }, enabled = state.ready && presets.nameDraft.isNotBlank()) {
                    Text("Enregistrer le preset")
                }
            },
            dismissButton = { TextButton(dismiss, enabled = state.ready) { Text("Annuler") } },
        )
    }
    var finish by rememberSaveable { mutableStateOf(false) }
    val back = { if (!state.busy) {
        if (state.impactPicking != null) controller.cancelImpactPicking(state.impactPicking!!.context)
        else if (state.pending != null || state.form != null || state.hasProposal) leave = true else controller.close()
    } }
    BackHandler(onBack = back)
    val height = LocalConfiguration.current.screenHeightDp.toFloat()
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().preciseTouchGuard(controller, { controller.state.value.guardTargetTouches },
            { controller.state.value.activeAim?.token }, controller::cancelAim).padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text("Séance configurable", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = back, enabled = !state.busy) { Text("Retour aux séances") } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.problem?.let { problem -> item {
                Text("${problem.kind} : ${problem.message}", color = MaterialTheme.colorScheme.error)
            } }
            state.confirmation?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            state.pending?.let { pending -> item {
                Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Intention conservée : ${pending.description}")
                    Text("UUID ${pending.request.proposed.uuid} · révision ${pending.request.proposed.updatedAt}")
                    pending.context.edit?.let { correction ->
                        correction.candidate?.let { Text("Position proposée conservée : $it · motif : ${correction.reason}") }
                        if (correction.proposal is ImpactProposal.ExceptionalCorrection) Text("Correction de score conservée : ${correction.proposal.selected} · motif : ${correction.proposal.reason}")
                        if (correction.proposal is ImpactProposal.BoundaryJudgment) Text("Jugement conservé : ${correction.proposal.boundary} · choix : ${correction.proposal.selected}")
                    }
                    Text("L’affichage durable conserve le dernier état confirmé. Aucune relance automatique.")
                    OutlinedButton(controller::reconcile, enabled = !state.busy) { Text("Relire et comparer l’état enregistré") }
                    Button(controller::retryExact, enabled = state.canRetryExact) { Text("Confirmer à nouveau la même intention") }
                    state.compared?.let { Text("État relu : ${it.uuid} · ${it.updatedAt} · ${state.reconciliation}") }
                } }
            } }
            state.form?.let { form -> item { ConfiguredFormFields(form, state, controller) } }
            state.saved?.let { session ->
                item { ConfiguredSessionSummary(session, readOnly = session.status == SessionStatus.COMPLETED) }
                if (session.status != SessionStatus.COMPLETED) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (session.status == SessionStatus.DRAFT) Button(controller::start, enabled = state.canForm) { Text("Démarrer la séance") }
                        if (session.status == SessionStatus.IN_PROGRESS) {
                            Button(controller::nextEnd, enabled = state.canForm) { Text("Créer une prochaine volée") }
                            OutlinedButton({ finish = true }, enabled = state.canForm) { Text("Clôturer la séance") }
                        }
                        OutlinedButton(controller::editMetadata, enabled = state.canForm) { Text("Modifier les métadonnées et le matériel") }
                    }
                }
                items(session.ends.sortedBy { it.end.index }, key = { "select-${it.uuid}" }) { captured ->
                    OutlinedButton({ controller.selectEnd(captured.uuid) }, enabled = state.canNavigate) {
                        Text("${if (state.selectedEndUuid == captured.uuid) "✓ " else ""}Volée ${captured.end.index} · ${captured.end.status} · ${captured.configuration.distanceM} m")
                    }
                }
                state.selectedEnd?.let { captured ->
                    val end = captured.end
                    val context = state.targetContext!!
                    item(key = "target-${end.uuid}") {
                        Text("Volée ${end.index} · ${end.activeImpacts.size} / ${captured.configuration.arrowCountPerEnd?.toString() ?: "libre"} flèches")
                        configurationLines(captured.configuration).forEach { Text(it) }
                        Text("Configuration enregistrée immuable · ${captured.origin.label()}")
                        ImpactPickingControls(state.impactPicking, end.activeImpacts, state.canBeginImpactPicking,
                            { controller.beginImpactPicking(context) }, controller::cancelImpactPicking, controller::choosePickedImpact)
                        if (state.editableEnd || state.impactPicking != null) {
                            OutlinedButton({ controller.precision(context, !state.precision) },
                                enabled = state.impactDrag == null && state.editableEnd && state.position == null && (state.edit == null || state.canReposition)) {
                                Text(if (state.precision) "Visée précise · passer en rapide" else "Activer la visée précise")
                            }
                        }
                        key(context) {
                            TargetCanvas(captured.configuration.targetType, end.activeImpacts, when {
                                state.impactPicking != null -> TargetInteraction.SELECT_IMPACT
                                state.impactDrag != null && state.canReposition -> TargetInteraction.DRAG_IMPACT
                                state.canReposition -> TargetInteraction.REPOSITION
                                state.canAdd || state.canAim -> TargetInteraction.ADD_IMPACT
                                else -> TargetInteraction.READ_ONLY
                            }, end.uuid, { controller.tapped(context, it) },
                                Modifier.widthIn(max = if (state.precision) precisionTargetMaxWidth(height).dp else 480.dp).fillMaxWidth().aspectRatio(1f),
                                state.selectedImpact?.uuid, state.activeAim?.candidate?.point ?: state.position?.point ?: state.edit?.candidate,
                                state.precision && state.impactDrag == null, { controller.beginAim(context, it) }, controller::moveAim, controller::finishAim, controller::cancelAim,
                                state.impactPicking?.context, controller::pickImpact,
                                state.impactDrag, controller::beginImpactDrag, controller::moveImpactDrag, controller::finishImpactDrag, controller::cancelAim)
                        }
                        Text(if (state.impactDrag != null) "Déplacement direct : glissez le marqueur sélectionné. Aperçu violet non enregistré ; confirmation séparée. Faites défiler hors du marqueur."
                            else if (state.impactPicking != null) "Mode sélection : aucun ajout ni déplacement. Annuler revient au mode de saisie précédent."
                            else if (state.precision) "Maintenir puis relâcher propose une position sans l’enregistrer. Loupe ×4 ; faites défiler hors du blason."
                            else "Un appui en mode rapide enregistre une nouvelle flèche, lorsque la saisie est disponible.")
                        if (captured.configuration.arrowCountMode == ArrowCountMode.FIXED && end.activeImpacts.size < captured.configuration.arrowCountPerEnd!!)
                            Text("Capacité non atteinte : information uniquement. La validation n’impose pas de compléter cette capacité.")
                        PrecisionAimFields(state.position, state.activeAim?.candidate, NewImpactNotice("Nouvelle flèche non enregistrée", "Confirmez une seule fois ou annulez cette proposition."),
                            state.canConfirmPosition, state.ready, { controller.confirmPosition(context) }, { controller.cancelPosition(context) })
                        if (state.editableEnd) Button(controller::validateEnd, enabled = state.canNavigate) { Text("Valider la volée") }
                    }
                    item(key = "cancelled-${end.uuid}") { ImpactCancellationHistory(end, captured.configuration.regulatoryEdition) }
                    items(end.activeImpacts, key = { "impact-${it.uuid}" }) { impact ->
                        OutlinedButton({ controller.selectImpact(context, impact.uuid) }, enabled = state.canSelectImpact) {
                            Text("Flèche #${impact.index} : ${impact.scoreLabel()} · ${impact.repositions.size} repositionnement(s)")
                        }
                    }
                    if (session.status == SessionStatus.IN_PROGRESS && end.status == EndStatus.EDITING) item(key = "impact-edit-${end.uuid}") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val edit = state.edit
                            // Capture this rendering's ticket; a delayed click must never read a newer mode.
                            val drag = state.impactDrag
                            Text("Correction d’impact", style = MaterialTheme.typography.titleMedium)
                            ImpactEditActions(state.canSelectImpact && state.selectedImpact != null,
                                state.canSelectImpact && state.selectedImpact != null, state.ready && edit != null,
                                { controller.beginReposition(context) }, { controller.beginDecision(context) }, { controller.closeSelection(context) },
                                state.canSelectImpact && state.selectedImpact != null, { controller.beginDragReposition(context) },
                                drag != null && state.canConfirmReposition, {
                                    val point = edit?.candidate
                                    if (drag != null && point != null) controller.confirmImpactDrag(drag, point)
                                }, state.canSelectImpact && state.selectedImpact != null, { controller.beginCancellation(context) })
                            if (edit == null) {
                                Text("Sélectionnez une flèche pour corriger sa position ou son score.")
                                return@Column
                            }
                            Text(configuredImpactSelectionLabel(captured, edit))
                            if (state.canReposition) {
                                Text(if (state.impactDrag != null) "Glissez la flèche sélectionnée ; relâcher ne sauvegarde rien." else "Touchez une nouvelle position ; elle reste un candidat local.")
                                edit.candidate?.let { Text("Nouvelle géométrie : ${edit.preview?.scoreFinal}") }
                                OutlinedTextField(edit.reason, { controller.repositionReason(context, it) }, label = { Text("Motif (obligatoire si décision manuelle invalidée)") })
                                if (state.impactDrag == null) Button({ controller.confirmReposition(context) }, enabled = state.canConfirmReposition) { Text("Confirmer le repositionnement") }
                            }
                            if (state.scoreOptions != null && state.selectedImpact != null && (edit.proposal is ImpactProposal.BoundaryJudgment || edit.proposal is ImpactProposal.ExceptionalCorrection)) {
                                ScoreDecisionPanel(ScoreDecisionView(state.selectedImpact!!, edit, state.scoreOptions!!, state.selectedBoundary,
                                    state.proposedScore, state.canScoreDecision, state.canConfirmScore),
                                    ScoreDecisionActions({ controller.boundary(context, it) }, { controller.scoreValue(context, it) },
                                        { controller.showBoundaries(context) }, { controller.exceptional(context, true) }, { controller.exceptional(context, false) },
                                        { controller.decisionReason(context, it) }, { controller.confirmDecision(context) }))
                            }
                        }
                    }
                }
                items(session.ends.sortedBy { it.end.index }, key = { "details-${it.uuid}" }) { ConfiguredEndCard(session, it) }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false; cancellationLeaveToken = null }, title = { Text(if (state.pending != null) "Quitter une écriture non confirmée ?" else "Abandonner le candidat local ?") },
        text = { Text(if (state.pending != null) "Le commit peut avoir eu lieu. Quitter n’annule aucune écriture. Relisez la séance avant toute nouvelle opération ; l’intention locale sera perdue."
            else "Ce formulaire ou cette correction locale n’a pas été soumis. Aucun enregistrement n’est créé en l’abandonnant.") },
        confirmButton = { TextButton({
            val ticket = cancellationLeaveToken
            leave = false; cancellationLeaveToken = null
            if (ticket == null || controller.canAbandonCancellationDisplay(ticket)) {
                if (controller.state.value.pending != null) controller.abandonSubmittedAndClose() else controller.abandonLocalAndClose()
            }
        }) { Text("Abandonner l’affichage et revenir") } },
        dismissButton = { TextButton({ leave = false; cancellationLeaveToken = null }) { Text("Conserver") } })
    if (finish) AlertDialog(onDismissRequest = { finish = false }, title = { Text("Clôturer définitivement ?") },
        text = { Text("Les volées provisoires restent provisoires et deviennent immuables. Aucun score ni configuration ne sera modifié ou validé automatiquement.") },
        confirmButton = { TextButton({ finish = false; controller.complete() }) { Text("Clôturer") } },
        dismissButton = { TextButton({ finish = false }) { Text("Annuler") } })
}

@Composable
private fun ConfiguredFormFields(form: ConfiguredForm, state: ConfiguredEditorState, c: ConfiguredSessionController) {
    val token = form.token
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when (form) { is ConfiguredForm.Creation -> "Nouveau brouillon configurable"; is ConfiguredForm.NextEnd -> "Configuration de la prochaine volée"; else -> "Métadonnées" }, style = MaterialTheme.typography.titleLarge)
        if (form is ConfiguredForm.Creation && state.presets != null) {
            PresetPicker(form.token, state.presets, state.ready, c)
        }
        if (form is ConfiguredForm.NextEnd) {
            OutlinedButton({ c.explicitNext(token, false) }, enabled = state.ready) { Text("${if (!form.explicit) "✓ " else ""}Hériter de la précédente (ou de l’initiale)") }
            OutlinedButton({ c.explicitNext(token, true) }, enabled = state.ready) { Text("${if (form.explicit) "✓ " else ""}Définir explicitement, même à valeurs égales") }
        }
        val config = when (form) { is ConfiguredForm.Creation -> form.configuration; is ConfiguredForm.NextEnd -> form.configuration; else -> null }
        config?.let { draft ->
            val enabled = state.ready && (form !is ConfiguredForm.NextEnd || form.explicit)
            Text("Blason : ${draft.profile.definition.name} · édition : ${draft.profile.regulatoryEdition ?: "historique non réglementaire"}")
            BuiltInTargetRegistry.all.forEach { profile ->
                OutlinedButton({ c.chooseProfile(token, profile.definition.uuid) }, enabled = enabled) { Text("${if (profile == draft.profile) "✓ " else ""}${profile.definition.name} · ${profile.regulatoryEdition ?: "historique"}") }
            }
            OutlinedTextField(draft.distance, { c.distance(token, it) }, enabled = enabled, label = { Text("Distance en mètres (entier positif)") })
            EndArrowCountShortcut.entries.forEach { shortcut ->
                OutlinedButton({ c.shortcut(token, shortcut) }, enabled = enabled) { Text(when (shortcut) { EndArrowCountShortcut.THREE -> "3 flèches"; EndArrowCountShortcut.SIX -> "6 flèches"; else -> "Libre" }) }
            }
            if (draft.arrowCountMode == ArrowCountMode.FIXED) OutlinedTextField(draft.fixedCount, { c.count(token, it) }, enabled = enabled, label = { Text("Capacité FIXED personnalisée") })
            Text("Mode : ${draft.arrowCountMode} · ${if (draft.indoor) "indoor" else "outdoor"} · ${draft.scoringMode}")
            OutlinedButton({ c.indoor(token, !draft.indoor) }, enabled = enabled) { Text("Choisir ${if (draft.indoor) "outdoor" else "indoor"}") }
            ScoringMode.entries.forEach { mode -> OutlinedButton({ c.scoring(token, mode) }, enabled = enabled) { Text("${if (mode == draft.scoringMode) "✓ " else ""}$mode") } }
            Text("Cette configuration personnalisée ne définit aucune nouvelle épreuve réglementaire. Les coordonnées et scores utilisent le profil exact sélectionné.")
        }
        val metadata = when (form) { is ConfiguredForm.Creation -> form.metadata; is ConfiguredForm.Metadata -> form.metadata; else -> null }
        metadata?.let { draft ->
            OutlinedTextField(draft.title, { c.title(token, it) }, enabled = state.ready, label = { Text("Titre obligatoire") })
            OutlinedTextField(draft.plannedEnds, { c.planned(token, it) }, enabled = state.ready, label = { Text("Volées prévues (facultatif)") })
            OutlinedTextField(draft.location.orEmpty(), { c.location(token, it) }, enabled = state.ready, label = { Text("Lieu (facultatif)") })
            OutlinedTextField(draft.notes.orEmpty(), { c.notes(token, it) }, enabled = state.ready, label = { Text("Notes (facultatif)") })
            val originalEquipment = (form as? ConfiguredForm.Creation)?.presetEquipment
                ?: state.saved?.metadata?.let { EquipmentSelection(it.bowUuid, it.arrowSetUuid) }
                ?: EquipmentSelection()
            EquipmentChoiceFields(state.equipmentChoices, draft.equipment, state.ready, original = originalEquipment,
                choose = { kind, uuid -> c.chooseEquipment(token, kind, uuid) })
        }
        if (form is ConfiguredForm.Creation && state.presets?.items != null) {
            OutlinedButton({ c.namePreset(token) }, enabled = state.ready) { Text("Enregistrer comme preset") }
            state.presets.savedName?.let { Text("Preset enregistré : $it", color = MaterialTheme.colorScheme.primary) }
        }
        Button({ c.confirmForm(token) }, enabled = state.ready) { Text("Confirmer et enregistrer") }
        TextButton({ c.cancelForm(token) }, enabled = state.ready) { Text("Annuler sans enregistrer") }
    }
}

@Composable
private fun PresetPicker(token: Long, state: SessionPresetsState, enabled: Boolean, c: ConfiguredSessionController) {
    var open by rememberSaveable(token) { mutableStateOf(false) }
    when {
        state.loadFailed -> {
            Text("Impossible de lire les presets enregistrés.", color = MaterialTheme.colorScheme.error)
            TextButton({ c.reloadPresets(token) }, enabled = enabled) { Text("Recharger les presets") }
        }
        state.items == null -> Text("Chargement des presets…")
        state.items.isEmpty() -> Text("Aucun preset. Vous pouvez enregistrer vos réglages en bas du formulaire.")
        else -> {
            OutlinedButton({ open = true }, enabled = enabled) { Text("Choisir un preset") }
            if (open) AlertDialog(
                onDismissRequest = { open = false },
                title = { Text("Choisir un preset") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.items, key = { it.uuid }) { preset ->
                            OutlinedButton(onClick = {
                                c.applyPreset(token, preset.uuid)
                                open = false
                            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(preset.name)
                                    Text("${preset.configuration.targetType.name} · ${preset.configuration.distanceM} m",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ open = false }) { Text("Fermer") } },
            )
        }
    }
}
